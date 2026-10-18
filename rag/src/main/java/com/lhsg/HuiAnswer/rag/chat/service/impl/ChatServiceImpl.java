package com.lhsg.HuiAnswer.rag.chat.service.impl;

import cn.hutool.core.util.StrUtil;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lhsg.HuiAnswer.framework.exception.AbstractException;
import com.lhsg.HuiAnswer.framework.exception.ClientException;
import com.lhsg.HuiAnswer.framework.exception.RemoteException;
import com.lhsg.HuiAnswer.framework.exception.ServiceException;
import com.lhsg.HuiAnswer.framework.result.errorcode.BaseErrorCode;
import com.lhsg.HuiAnswer.rag.admin.user.UsersContext;
import com.lhsg.HuiAnswer.rag.chat.build.message.ChatRequest;
import com.lhsg.HuiAnswer.rag.chat.build.message.Messages;
import com.lhsg.HuiAnswer.rag.chat.dto.req.QuestionReq;
import com.lhsg.HuiAnswer.rag.chat.build.task.ChatGenerationTask;
import com.lhsg.HuiAnswer.rag.chat.build.task.ChatGenerationManager;
import com.lhsg.HuiAnswer.rag.chat.dto.resp.LLMChatResp;
import com.lhsg.HuiAnswer.rag.chat.dto.resp.NeedChunksResp;
import com.lhsg.HuiAnswer.rag.chat.service.IChatService;
import com.lhsg.HuiAnswer.rag.config.ApiUrl;
import com.lhsg.HuiAnswer.rag.memory.dto.req.StoreDialogueDTO;
import com.lhsg.HuiAnswer.rag.memory.dto.search.SearchSummaryMemoryDTO;
import com.lhsg.HuiAnswer.rag.memory.service.IConversationService;
import com.lhsg.HuiAnswer.rag.memory.service.IMessageService;
import com.lhsg.HuiAnswer.rag.memory.service.ISummaryService;
import com.lhsg.HuiAnswer.rag.retrieval.dto.Reference;
import com.lhsg.HuiAnswer.rag.retrieval.dto.RewriteResultDTO;
import com.lhsg.HuiAnswer.rag.retrieval.dto.SubQuestionResultDTO;
import com.lhsg.HuiAnswer.rag.retrieval.process.SubQuestionProcessor;
import com.lhsg.HuiAnswer.rag.retrieval.rerank.RerankChunks;
import com.lhsg.HuiAnswer.rag.retrieval.rewrite.RewriteQuestion;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import okio.BufferedSource;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.lhsg.HuiAnswer.rag.chat.build.request.BuildRequest.buildRequest;
import static com.lhsg.HuiAnswer.rag.chat.build.request.IdentifyIndentForModel.isRag;
import static com.lhsg.HuiAnswer.rag.constant.constant.HUI_ANSWER_LOCK_CONVERSATION;
import static com.lhsg.HuiAnswer.rag.retrieval.intent.enums.IntentEnum.CHAT;
import static com.lhsg.HuiAnswer.rag.retrieval.intent.enums.IntentEnum.CLARIFY;
import static org.springframework.http.MediaType.APPLICATION_JSON;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatServiceImpl implements IChatService {
    private final SubQuestionProcessor subQuestionProcessor;
    private final RerankChunks rerankChunks;
    private final RewriteQuestion rewriteQuestion;
    private final OkHttpClient httpClient;
    private final Gson gson;
    private final IMessageService messageService;
    private final RedissonClient redissonClient;
    private final IConversationService conversationService;
    private final ISummaryService summaryService;

    private final ApiUrl apiUrl;
    private final ChatGenerationManager generationManager;
    @Resource(name = "chatExecutor")
    private ThreadPoolExecutor chatExecutor;
    @Resource(name = "memorySummaryExecutor")
    private final ThreadPoolExecutor memorySummaryExecutor;

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    @Value("${rag.ai.deepseek.chat-model}")
    private String chatModel;
    @Value("${rag.ai.deepseek.api-key}")
    private String apiKey;
    @Value("${rag.chat.max-model-wait}")
    private Long modelWait;
    @Value("${rag.chat.answer-max-tokens}")
    private int answerMaxTokens;
    @Value("${rag.evaluation.enabled:false}")
    private boolean enabledAssess;
    /** B 组开关：只执行完整改写问题；关闭后恢复 C 组的子问题并行流程。 */
    @Value("${rag.evaluation.single-question:false}")
    private boolean singleQuestionAssess;

    @Override
    public void chat(QuestionReq req, SseEmitter emitter) {
        Long userId = UsersContext.requireUserId();
        if (StrUtil.isBlank(req.getQuestion())) {
            throw new ClientException("请输入问题");
        }
        Long conversationId = req.getConversationId();
        if (conversationId != null && conversationId <= 0) {
            throw new ClientException("会话 ID 不正确");
        }
        if (conversationId == null) {
            conversationId = conversationService.addConversation(req.getQuestion());
            req.setConversationId(conversationId);
        }
        if (!conversationService.ifYourConversation(conversationId)) {
            throw new ClientException("会话信息有误");
        }
        ChatGenerationTask task = generationManager.register(userId, conversationId, emitter);
        // 在返回 emitter、提交线程池前登记回调，覆盖排队期间结束连接的情况。
        emitter.onCompletion(() -> task.cancel("流式连接已关闭"));
        emitter.onError(error -> task.cancel("流式连接已关闭"));
        emitter.onTimeout(() -> {
            task.cancel("流式连接超时");
            emitter.complete();
        });
        try {
            emitter.send(SseEmitter.event().name("meta")
                    .data(Map.of("conversationId", conversationId,
                            "generationId", task.getGenerationId()), APPLICATION_JSON));
            chatExecutor.execute(() -> runGeneration(req, task));
        } catch (IOException e) {
            generationManager.finish(task);
            emitter.completeWithError(e);
        } catch (RejectedExecutionException e) {
            generationManager.finish(task);
            sendError(emitter, "服务繁忙，请稍后再试");
        } catch (Exception e) {
            generationManager.finish(task);
            sendError(emitter, userMessage(e));
        }
    }

    @Override
    public void stop(Long conversationId, String generationId) {
        Long userId = UsersContext.requireUserId();
        if (conversationId == null || conversationId <= 0
                || !conversationService.ifYourConversation(conversationId)) {
            throw new ClientException("会话不存在或无权访问");
        }
        generationManager.requestStop(userId, conversationId, generationId);
    }

    private void runGeneration(QuestionReq req, ChatGenerationTask task) {
        SseEmitter emitter = task.getEmitter();
        RLock conversationLock = null;
        boolean locked = false;
        try {
            try {
                generationManager.checkStopped(task);
                // 加锁和解锁都在当前工作线程，覆盖本轮问答及任务收尾。
                conversationLock = redissonClient.getLock(
                        HUI_ANSWER_LOCK_CONVERSATION.formatted(task.getConversationId()));
                locked = conversationLock.tryLock();
                if (!locked) {
                    throw new ClientException("本会话正在生成回答，请等待结束后再提问");
                }
                chatStream(req, task);
            } finally {
                try {
                    generationManager.finish(task);
                } finally {
                    if (locked) {
                        conversationLock.unlock();
                    }
                }
            }
            // 收尾并解锁后发送结束标记，前端收到后即可继续提问。
            emitter.send(SseEmitter.event().name("done").data("[DONE]"));
            emitter.complete();
            try {
                memorySummaryExecutor.execute(() ->
                        summaryService.addSummary(task.getConversationId(), task.getUserId()));
            } catch (RejectedExecutionException e) {
                log.warn("摘要线程池繁忙，conversationId={}", task.getConversationId());
            }
        } catch (Exception e) {
            if (task.getStopped().get()) {
                sendStopped(task);
            } else {
                sendError(emitter, userMessage(e));
            }
        }
    }

    private void sendStopped(ChatGenerationTask task) {
        try {
            task.getEmitter().send(SseEmitter.event().name("stopped")
                    .data(Map.of("generationId", task.getGenerationId(),
                            "message", task.getStopReason()), APPLICATION_JSON));
            task.getEmitter().complete();
        } catch (Exception ignored) {
            // 连接已经关闭时，停止记录仍由工作线程保存。
        }
    }

    private void chatStream(QuestionReq req, ChatGenerationTask task) {
        SseEmitter emitter = task.getEmitter();
        Long userId = task.getUserId();
        Integer turnNo = null;
        // 正文及模型统计只由工作线程维护，停止接口不跨线程入库。
        LLMChatResp resp = new LLMChatResp().setModel(chatModel).setNeedChunks(List.of());
        StringBuilder answer = new StringBuilder();
        Map<String, Double> stageMs = new LinkedHashMap<>();
        long preparationStart = System.nanoTime();
        try {
            emitter.send(SseEmitter.event().comment("connected"));
            task.checkStopped();
            // 持久化用户问题到信息表并返回此次对话轮次
            String question = req.getQuestion();
            turnNo = messageService.saveQuestion(req.getConversationId(), userId, question);
            // 读取历史以及摘要
            long stageStart = System.nanoTime();
            SearchSummaryMemoryDTO history = summaryService.summaryMemory(req.getConversationId(), userId);
            stageMs.put("history", elapsedMs(stageStart));
            task.checkStopped();
            boolean singleQuestion = enabledAssess && singleQuestionAssess;
            // 一次完成问题改写与拆分；B 组只使用完整改写，不分别执行子问题。
            stageStart = System.nanoTime();
            RewriteResultDTO rewriteResult = rewriteQuestion.rewrite(question, history.getMemory());
            stageMs.put("rewrite", elapsedMs(stageStart));
            task.checkStopped();
            List<String> searchQuestions = singleQuestion
                    ? List.of(rewriteResult.getRewrittenQuestion()) : rewriteResult.getSubQuestions();
            // 复用现有任务：每个问题先路由，再检索；B 组只提交一个完整问题。
            stageStart = System.nanoTime();
            List<SubQuestionResultDTO> results = subQuestionProcessor.process(searchQuestions);
            stageMs.put("subquestions_wall", elapsedMs(stageStart));
            task.checkStopped();
            List<Reference> references = mergeReferences(results);
            task.checkStopped();
            // 使用完整改写问题进行一次全局重排序
            stageStart = System.nanoTime();
            if (!references.isEmpty()) {
                references = rerankChunks.rerank(rewriteResult.getRewrittenQuestion(), references);
                task.checkStopped();
            }
            stageMs.put("rerank", elapsedMs(stageStart));
            // 原始输入、历史和统一资料交给模型；意图结果仅用于后端决定检索路径。
            Messages messages = new Messages().setHistory(history)
                    .setReferences(references).setQuestion(question);
            boolean ragQuestion = isRag(results);
            ChatRequest chatRequest = new ChatRequest().setMessages(messages)
                    .setThinking(ragQuestion)
                    .setTemperature(ragQuestion ? 0.3 : 0.8)
                    .setModel(chatModel).setMaxTokens(answerMaxTokens);
            JsonObject body = buildRequest(chatRequest);
            if (enabledAssess) {
                stageMs.put("preparation", elapsedMs(preparationStart));
                emitter.send(SseEmitter.event().name("evaluation").data(references, APPLICATION_JSON));
                emitter.send(SseEmitter.event().name("evaluation_trace")
                        .data(evaluationTrace(rewriteResult, results, stageMs, chatRequest, singleQuestion), APPLICATION_JSON));
            }
            generationManager.checkStopped(task);
            Call call = httpClient.newCall(getRequest(body));
            call.timeout().timeout(modelWait, TimeUnit.SECONDS);
            task.bindCall(call);
            long modelStart = System.nanoTime();
            try (Response response = call.execute()) {
                ResponseBody responseBody = response.body();
                if (!response.isSuccessful() || responseBody == null) {
                    throw new ServiceException("模型连接失败");
                }
                // 解析响应
                Double modelFirstDeltaMs = readStream(responseBody, resp, answer, task, modelStart);
                generationManager.checkStopped(task);
                if (enabledAssess) {
                    Map<String, Object> stats = new LinkedHashMap<>();
                    stats.put("answer_model", resp.getModel());
                    stats.put("answer_temperature", chatRequest.getTemperature());
                    stats.put("thinking", chatRequest.getThinking());
                    stats.put("max_tokens", chatRequest.getMaxTokens());
                    stats.put("finish_reason", resp.getFinishReason());
                    stats.put("completion_tokens", resp.getCompletionTokens());
                    stats.put("reasoning_tokens", resp.getReasoningTokens());
                    stats.put("model_first_delta_ms", modelFirstDeltaMs);
                    stats.put("model_stream_ms", elapsedMs(modelStart));
                    emitter.send(SseEmitter.event().name("evaluation_stats").data(stats, APPLICATION_JSON));
                }
                resp.setNeedChunks(toSources(references));
                // 标记结果为已完成
                task.beginCompletion();
                messageService.storeDialogue(toStoreDTO(req, userId, turnNo, resp, "success", null));
            }
        } catch (Exception e) {
            boolean aborted = task.getStopped().get() || e instanceof ClientGoneException;
            if (e instanceof ClientGoneException) {
                task.cancel("流式连接已关闭");
            }
            resp.setAnswer(answer.toString());
            // 保存对话失败记录
            if (turnNo != null) {
                String message = aborted ? task.getStopReason()
                        : e instanceof AbstractException ae ? ae.getErrorMessage() : "回答生成失败，请稍后再试";
                try {
                    messageService.storeDialogue(toStoreDTO(req, userId, turnNo, resp, "failed", message));
                } catch (Exception saveFailure) {
                    // 保存失败不掩盖原始生成异常；数据库不可用时无法保证失败记录落库。
                    log.error("保存失败回答失败，会话 {}，轮次 {}，异常类型 {}",
                            req.getConversationId(), turnNo, saveFailure.getClass().getSimpleName());
                }
            }
            if (aborted) {
                log.debug("流式问答中止：{}", task.getStopReason());
                throw new ServiceException(task.getStopReason());
            }
            if (e instanceof AbstractException ae) {
                throw ae;
            }
            log.warn("流式问答失败，异常类型：{}，原因：{}", e.getClass().getSimpleName(), e.getMessage());
            if (e instanceof InterruptedIOException) {
                throw new ServiceException("模型服务响应超时，请稍后再试", e, BaseErrorCode.SERVICE_TIMEOUT_ERROR);
            }
            throw new RemoteException("模型服务暂时不可用，请稍后再试", e, BaseErrorCode.REMOTE_ERROR);
        }
    }

    private static double elapsedMs(long start) {
        return (System.nanoTime() - start) / 1_000_000.0;
    }

    /** 仅评测开关开启时发送；候选只记录 ID，不重复推送正文。 */
    private Map<String, Object> evaluationTrace(RewriteResultDTO rewrite,
            List<SubQuestionResultDTO> results, Map<String, Double> stageMs, ChatRequest request,
            boolean singleQuestion) {
        List<Map<String, Object>> routes = new ArrayList<>();
        for (SubQuestionResultDTO result : results) {
            Map<String, Object> route = new LinkedHashMap<>();
            route.put("index", result.getIndex());
            route.put("question", result.getQuestion());
            route.put("intent", result.getIntent());
            route.put("route_status", "executed");
            route.put("kb_ids", result.getKbIds());
            route.put("kb_names", result.getKbNames());
            route.put("fallback", result.isRouteFallback());
            route.put("route_error_type", result.getRouteErrorType());
            route.put("route_ms", result.getRouteMs());
            route.put("search_status", result.getSearchStatus());
            route.put("search_error_type", result.getSearchErrorType());
            route.put("search_ms", result.getSearchMs());
            route.put("candidate_chunk_ids", result.getReferences().stream().map(Reference::getChunkId).toList());
            routes.add(route);
        }
        return Map.of("version", "generation-trace-v1", "rewrite", rewrite, "routes", routes,
                "stage_ms", stageMs, "configured_model", chatModel,
                "answer_temperature", request.getTemperature(),
                "evaluation_mode", singleQuestion ? "B" : "C",
                "description", singleQuestion ? "完整问题改写后执行一次意图路由和检索，不按子问题分别检索"
                        : "问题改写拆分后，各子问题独立路由检索，合并后统一重排序",
                "rewrite_enabled", true, "intent_routing_enabled", true);
    }

    /** 返回信息表插入所需实体 */
    private StoreDialogueDTO toStoreDTO(QuestionReq req, Long userId, int turnNo,
                                         LLMChatResp resp, String status, String errorMessage) {
        return new StoreDialogueDTO().setConversationId(req.getConversationId()).setUserId(userId)
                .setTurnNo(turnNo).setContent(resp.getAnswer()).setStatus(status)
                .setSources(resp.getNeedChunks()).setModel(resp.getModel())
                .setTotalTokens(resp.getTokens() > 0 ? resp.getTokens() : null)
                .setFinishReason(resp.getFinishReason()).setErrorMessage(errorMessage);
    }

    /**
     * 一条 SSE 事件以空行结束；data 可以有多行，先拼完整事件再解析 JSON。
     */
    private Double readStream(ResponseBody body, LLMChatResp resp, StringBuilder answer,
                            ChatGenerationTask task, long modelStart) throws IOException {
        Double firstDeltaMs = null;
        StringBuilder eventData = new StringBuilder();
        BufferedSource source = body.source();
        while (true) {
            // 打断下一行的读取，如果缓冲区有数据，cancel不会取消连接必须依靠检查标志位直接停止
            task.checkStopped();
            String line = source.readUtf8Line();
            if (line == null) {
                break;
            }
            if (line.isEmpty()) {
                if (eventData.isEmpty()) {
                    continue;
                }
                String data = eventData.toString();
                eventData.setLength(0);
                if ("[DONE]".equals(data)) {
                    break;
                }
                readChunk(data, resp, answer, task.getEmitter());
                if (firstDeltaMs == null && !answer.toString().isBlank()) {
                    firstDeltaMs = elapsedMs(modelStart);
                }
            } else if (line.startsWith("data:")) {
                String data = line.substring(5);
                if (data.startsWith(" ")) {
                    data = data.substring(1);
                }
                if (!eventData.isEmpty()) {
                    eventData.append('\n');
                }
                eventData.append(data);
            }
        }
        resp.setAnswer(answer.toString());
        // length 可以保留已生成的部分正文；没有正文时不能作为成功回答入库。
        String finishReason = resp.getFinishReason();
        if (!"stop".equals(finishReason) && !"length".equals(finishReason)) {
            throw new ServiceException("模型回答异常终止");
        }
        if (answer.toString().isBlank()) {
            log.warn("模型返回空正文，model={}，finishReason={}，answerMaxTokens={}，completionTokens={}，reasoningTokens={}",
                    resp.getModel(), finishReason, answerMaxTokens,
                    resp.getCompletionTokens(), resp.getReasoningTokens());
            throw new ServiceException("模型未生成回答正文，请稍后再试");
        }
        return firstDeltaMs;
    }

    private void readChunk(String data, LLMChatResp resp, StringBuilder answer,
                           SseEmitter emitter) {
        JsonObject chunk = gson.fromJson(data, JsonObject.class);
        if (chunk.has("error") && !chunk.get("error").isJsonNull()) {
            throw new ServiceException("模型返回错误");
        }
        JsonElement model = chunk.get("model");
        if (model != null && !model.isJsonNull()) {
            resp.setModel(model.getAsString());
        }
        JsonElement usageElement = chunk.get("usage");
        if (usageElement != null && usageElement.isJsonObject()) {
            JsonObject usage = usageElement.getAsJsonObject();
            // usage 是累计统计：每次覆盖，只保留最终值，不逐条相加。
            JsonElement totalTokens = usage.get("total_tokens");
            if (totalTokens != null && !totalTokens.isJsonNull()) {
                resp.setTokens(totalTokens.getAsInt());
            }
            JsonElement completionTokens = usage.get("completion_tokens");
            if (completionTokens != null && !completionTokens.isJsonNull()) {
                resp.setCompletionTokens(completionTokens.getAsInt());
            }
            JsonElement details = usage.get("completion_tokens_details");
            if (details != null && details.isJsonObject()) {
                JsonElement reasoningTokens = details.getAsJsonObject().get("reasoning_tokens");
                if (reasoningTokens != null && !reasoningTokens.isJsonNull()) {
                    resp.setReasoningTokens(reasoningTokens.getAsInt());
                }
            }
        }
        JsonArray choices = chunk.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            return;
        }
        JsonObject choice = choices.get(0).getAsJsonObject();
        JsonElement finishReason = choice.get("finish_reason");
        if (finishReason != null && !finishReason.isJsonNull()) {
            resp.setFinishReason(finishReason.getAsString());
        }
        JsonObject delta = choice.getAsJsonObject("delta");
        if (delta == null) {
            return;
        }
        JsonElement content = delta.get("content");
        if (content == null || content.isJsonNull()) {
            return;
        }
        String fragment = content.getAsString();
        if (fragment.isEmpty()) {
            return;
        }
        answer.append(fragment);
        // 只发送正文 content，角色信息和 reasoning_content 不作为正文展示。
        try {
            emitter.send(SseEmitter.event().name("delta").data(fragment));
        } catch (Exception sendFailure) {
            throw new ClientGoneException(sendFailure);
        }
    }

    /** 写回前端失败，用于和“上游模型出错”区分开。 */
    private static final class ClientGoneException extends RuntimeException {
        ClientGoneException(Throwable cause) {
            super(cause);
        }
    }

    /** 各子问题候选按 chunkId 去重，再按 RRF 分数粗排。 */
    private List<Reference> mergeReferences(List<SubQuestionResultDTO> results) {
        Map<Long, Reference> merged = new HashMap<>();
        for (SubQuestionResultDTO result : results) {
            if (result.getIntent().equals(CHAT) || result.getIntent().equals(CLARIFY)) {
                continue;
            }
            for (Reference reference : result.getReferences()) {
                merged.putIfAbsent(reference.getChunkId(), reference);
            }
        }
        if (merged.isEmpty()) {
            return Collections.emptyList();
        }
        return merged.values().stream()
                .sorted(Comparator.comparingDouble(Reference::getScore).reversed())
                .collect(Collectors.toList());
    }

    /** 入库用的出处列表：只做字段映射，不带编号一类的展示格式。 */
    private static List<NeedChunksResp> toSources(List<Reference> references) {
        return references.stream()
                .map(reference -> new NeedChunksResp(reference.getDocId(), reference.getDocName(),
                        reference.getChunkId()))
                .toList();
    }

    private Request getRequest(JsonObject body) {
        Request.Builder request = new Request.Builder()
                .url(apiUrl.chatCompletionsUrl())
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(body.toString(), JSON));
        return request.build();
    }


    private String userMessage(Exception e) {
        if (e instanceof AbstractException ae) {
            return ae.getErrorMessage();
        }
        return "回答生成失败，请稍后再试";
    }

    private void sendError(SseEmitter emitter, String message) {
        try {
            // 流已经开始后，用 error 事件通知客户端，不能再返回普通 Result。
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(Map.of("message", message), APPLICATION_JSON));
            emitter.complete();
        } catch (Exception ignored) {
            // 客户端可能已经断开；容器会完成连接清理，不再尝试往响应里写数据。
        }
    }

}
