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

    @Value("${rag.ai.siliconflow.chat-model}")
    private String chatModel;
    @Value("${rag.ai.siliconflow.api-key}")
    private String apiKey;
    @Value("${rag.chat.max-model-wait}")
    private Long modelWait;
    @Value("${rag.chat.answer-max-tokens}")
    private int answerMaxTokens;

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
        try {
            generationManager.checkStopped(task);
            chatStream(req, task);
            emitter.send(SseEmitter.event().name("done").data("[DONE]"));
            emitter.complete();
            try {
                memorySummaryExecutor.execute(() ->
                        summaryService.addSummary(task.getConversationId(), task.getUserId()));
            } catch (RejectedExecutionException e) {
                // 正文已成功落库、发送完成，摘要排队失败不改成问答失败。
                log.warn("摘要线程池繁忙，conversationId={}", task.getConversationId());
            }
        } catch (Exception e) {
            if (task.getStopped().get()) {
                sendStopped(task);
            } else {
                sendError(emitter, userMessage(e));
            }
        } finally {
            generationManager.finish(task);
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
        RLock conversationLock = redissonClient.getLock(
                HUI_ANSWER_LOCK_CONVERSATION.formatted(task.getConversationId()));
        Integer turnNo = null;
        boolean answerStored = false;
        // 正文及模型统计只由工作线程维护，停止接口不跨线程入库。
        LLMChatResp resp = new LLMChatResp().setModel(chatModel).setNeedChunks(List.of());
        StringBuilder answer = new StringBuilder();

        // 加锁和解锁都在当前工作线程，不能在容器回调中跨线程解锁。
        boolean locked = conversationLock.tryLock();
        if (!locked) {
            throw new ClientException("本会话正在生成回答，请等待结束后再提问");
        }
        try {
            emitter.send(SseEmitter.event().comment("connected"));
            task.checkStopped();
            // 持久化用户问题到信息表并返回此次对话轮次
            String question = req.getQuestion();
            turnNo = messageService.saveQuestion(req.getConversationId(), userId, question);
            // 读取历史以及摘要
            SearchSummaryMemoryDTO history = summaryService.summaryMemory(req.getConversationId(), userId);
            task.checkStopped();
            // 一次完成问题改写与拆分
            RewriteResultDTO rewriteResult = rewriteQuestion.rewrite(question, history.getMemory());
            task.checkStopped();
            // 所有子问题并行意图路由和检索
            List<SubQuestionResultDTO> results = subQuestionProcessor.process(rewriteResult.getSubQuestions());
            task.checkStopped();
            // 合并、去重多路召回的片段
            List<Reference> references = mergeReferences(results);
            task.checkStopped();
            // 使用完整改写问题进行一次全局重排序
            if (!references.isEmpty()) {
                references = rerankChunks.rerank(rewriteResult.getRewrittenQuestion(), references);
                task.checkStopped();
            }
            // 原始输入、历史和统一资料交给模型；意图结果仅用于后端决定检索路径。
            Messages messages = new Messages().setHistory(history)
                    .setReferences(references).setQuestion(question);
            ChatRequest chatRequest = new ChatRequest().setMessages(messages)
                    .setThinking(isRag(results))
                    .setTemperature(isRag(results) ? 0.3 : 0.8)
                    .setModel(chatModel).setMaxTokens(answerMaxTokens);
            JsonObject body = buildRequest(chatRequest);
            generationManager.checkStopped(task);
            Call call = httpClient.newCall(getRequest(body));
            call.timeout().timeout(modelWait, TimeUnit.SECONDS);
            task.bindCall(call);
            try (Response response = call.execute()) {
                ResponseBody responseBody = response.body();
                if (!response.isSuccessful() || responseBody == null) {
                    throw new ServiceException("模型连接失败");
                }
                // 解析响应
                readStream(responseBody, resp, answer, task);
                generationManager.checkStopped(task);
                resp.setNeedChunks(toSources(references));
                task.beginCompletion();
                messageService.storeDialogue(toStoreDTO(req, userId, turnNo, resp, "success", null));
                answerStored = true;
            }
        } catch (Exception e) {
            if (answerStored) {
                // 已提交的成功回答不因迟到的关闭异常再插一条失败记录。
                log.debug("回答已保存，忽略后续关闭异常");
                return;
            }
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
        } finally {
            try {
                task.finish();
            } finally {
                conversationLock.unlock();
            }
        }
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
    private void readStream(ResponseBody body, LLMChatResp resp, StringBuilder answer,
                            ChatGenerationTask task) throws IOException {
        StringBuilder eventData = new StringBuilder();
        BufferedSource source = body.source();
        while (true) {
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
        // 截断(length)也算生成完成：正文是完整的，只是没写完，存 success 并留在历史里。
        String finishReason = resp.getFinishReason();
        if (!"stop".equals(finishReason) && !"length".equals(finishReason)) {
            throw new ServiceException("模型回答异常终止");
        }
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
        JsonObject usage = chunk.getAsJsonObject("usage");
        if (usage != null && usage.has("total_tokens") && !usage.get("total_tokens").isJsonNull()) {
            // usage 是累计统计：每次覆盖，只保留最终值，不逐条相加。
            resp.setTokens(usage.get("total_tokens").getAsInt());
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
