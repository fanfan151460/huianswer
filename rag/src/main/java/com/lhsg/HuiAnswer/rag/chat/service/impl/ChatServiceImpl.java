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
import com.lhsg.HuiAnswer.rag.chat.build.req.ChatRequest;
import com.lhsg.HuiAnswer.rag.chat.build.req.Messages;
import com.lhsg.HuiAnswer.rag.chat.dto.req.QuestionReq;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
        try {
            // 在提交模型任务前返回会话 ID，后续生成失败或线程池繁忙也能关联到本次会话。
            emitter.send(SseEmitter.event()
                    .name("meta")
                    .data(Map.of("conversationId", conversationId), APPLICATION_JSON));
            // Controller 先返回 emitter，模型调用和发送事件由线程池继续执行。
            chatExecutor.execute(() -> {
                try {
                    chatStream(req, emitter, userId);
                    // 检查是否需要压缩对话
                    memorySummaryExecutor.execute(() -> summaryService.addSummary(req.getConversationId(), userId));
                    // 正文已由 delta 推完，done 只作结束信号；无 data 的事件浏览器不会派发。
                    emitter.send(SseEmitter.event()
                            .name("done")
                            .data("[DONE]"));
                    emitter.complete();
                } catch (Exception e) {
                    sendError(emitter, userMessage(e));
                }
            });
        } catch (IOException e) {
            // meta 未能写出时结束连接，不再提交模型任务。
            emitter.completeWithError(e);
        } catch (RejectedExecutionException e) {
            sendError(emitter, "服务繁忙，请稍后再试");
        }
    }

    private void linking(AtomicBoolean closed) {
        if (closed.get()) {
            throw new ServiceException("流式连接已关闭");
        }
    }
    @Override
    public void chatStream(QuestionReq req, SseEmitter emitter, Long userId) {
        RLock conversationLock = redissonClient.getLock(HUI_ANSWER_LOCK_CONVERSATION.formatted(req.getConversationId()));
        Integer turnNo = null;
        // 放在读取方法外，断流或异常时也能保存已生成的正文和模型统计。
        LLMChatResp resp = new LLMChatResp().setModel(chatModel).setNeedChunks(List.of());
        StringBuilder answer = new StringBuilder();
        AtomicBoolean closed = new AtomicBoolean();
        AtomicReference<Call> activeCall = new AtomicReference<>();
        // 容器回调与读取任务运行在不同线程，原子变量用于传递关闭状态和当前模型请求。
        Runnable cancelCall = () -> {
            closed.set(true);
            Call call = activeCall.get();
            if (call != null) {
                call.cancel();
            }
        };
        emitter.onCompletion(cancelCall);
        emitter.onError(error -> cancelCall.run());
        emitter.onTimeout(() -> {
            cancelCall.run();
            emitter.complete();
        });

        // 加锁和解锁都在当前工作线程，不能在容器回调中跨线程解锁。
        boolean locked = conversationLock.tryLock();
        if (!locked) {
            throw new ClientException("本会话正在生成回答，请等待结束后再提问");
        }
        try {
            emitter.send(SseEmitter.event().comment("connected"));
            linking(closed);
            // 持久化用户问题到信息表并返回此次对话轮次
            String question = req.getQuestion();
            turnNo = messageService.saveQuestion(req.getConversationId(), userId, question);
            // 读取历史以及摘要
            SearchSummaryMemoryDTO history = summaryService.summaryMemory(req.getConversationId(), userId);
            linking(closed);
            // 一次完成问题改写与拆分
            RewriteResultDTO rewriteResult = rewriteQuestion.rewrite(question, history.getMemory());
            linking(closed);
            // 所有子问题并行意图路由和检索
            List<SubQuestionResultDTO> results = subQuestionProcessor.process(rewriteResult.getSubQuestions());
            linking(closed);
            // 合并、去重多路召回的片段
            List<Reference> references = mergeReferences(results);
            // 使用完整改写问题进行一次全局重排序
            if (!references.isEmpty()) {
                references = rerankChunks.rerank(rewriteResult.getRewrittenQuestion(), references);
                linking(closed);
            }
            // 原始输入、历史和统一资料交给模型；意图结果仅用于后端决定检索路径。
            Messages messages = new Messages().setHistory(history)
                    .setReferences(references).setQuestion(question);
            ChatRequest chatRequest = new ChatRequest().setMessages(messages)
                    .setThinking(isRag(results))
                    .setTemperature(isRag(results) ? 0.3 : 0.8)
                    .setModel(chatModel).setMaxTokens(answerMaxTokens);
            JsonObject body = buildRequest(chatRequest);
            Call call = httpClient.newCall(getRequest(body));
            activeCall.set(call);
            linking(closed);
            // 建立模型连接开始读取回答响应，之前先检查用户是否已经关闭防止额外计费
            call.timeout().timeout(modelWait, TimeUnit.SECONDS);
            try (Response response = call.execute()) {
                ResponseBody responseBody = response.body();
                if (!response.isSuccessful() || responseBody == null) {
                    throw new ServiceException("模型连接失败");
                }
                // 解析响应
                readStream(responseBody, resp, answer, emitter);
                linking(closed);
                // 持久化对话到信息表中为对话历史
                resp.setNeedChunks(toSources(references));
                messageService.storeDialogue(toStoreDTO(req, userId, turnNo, resp, "success", null));
            }
        } catch (Exception e) {
            boolean aborted = closed.get() || e instanceof ClientGoneException;
            cancelCall.run();
            resp.setAnswer(answer.toString());
            // 保存对话失败记录
            if (turnNo != null) {
                String message = aborted ? "流式连接已关闭"
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
                log.debug("流式问答中止：连接已关闭");
                throw new ServiceException("流式连接已关闭");
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
            conversationLock.unlock();
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
                            SseEmitter emitter) throws IOException {
        StringBuilder eventData = new StringBuilder();
        BufferedSource source = body.source();
        String line;
        while ((line = source.readUtf8Line()) != null) {
            if (line.isEmpty()) {
                if (eventData.isEmpty()) {
                    continue;
                }
                String data = eventData.toString();
                eventData.setLength(0);
                if ("[DONE]".equals(data)) {
                    break;
                }
                readChunk(data, resp, answer, emitter);
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
