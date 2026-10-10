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
import com.lhsg.HuiAnswer.framework.monitor.TimeAnnotation;
import com.lhsg.HuiAnswer.framework.result.errorcode.BaseErrorCode;
import com.lhsg.HuiAnswer.rag.admin.user.UsersContext;
import com.lhsg.HuiAnswer.rag.chat.dto.LLMChatResp;
import com.lhsg.HuiAnswer.rag.chat.dto.NeedChunksResp;
import com.lhsg.HuiAnswer.rag.chat.dto.ParseReferences;
import com.lhsg.HuiAnswer.rag.chat.dto.QuestionReq;
import com.lhsg.HuiAnswer.rag.chat.service.IChatService;
import com.lhsg.HuiAnswer.rag.memory.Conversation.service.IConversationService;
import com.lhsg.HuiAnswer.rag.memory.dto.req.StoreDialogueDTO;
import com.lhsg.HuiAnswer.rag.memory.memoryStore.SaveDialogue;
import com.lhsg.HuiAnswer.rag.retrieval.dto.Reference;
import com.lhsg.HuiAnswer.rag.retrieval.rerank.RerankChunks;
import com.lhsg.HuiAnswer.rag.retrieval.search.SearchChunks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import okio.BufferedSource;
import org.jetbrains.annotations.NotNull;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.lhsg.HuiAnswer.rag.chat.dto.prompt.System.BASE_PROMPT;
import static com.lhsg.HuiAnswer.rag.constant.constant.HUI_ANSWER_LOCK_CONVERSATION;
import static org.springframework.http.MediaType.APPLICATION_JSON;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatServiceImpl implements IChatService {
    private final SearchChunks searchChecks;
    private final RerankChunks rerankChunks;
    private final OkHttpClient httpClient;
    private final Gson gson;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final SaveDialogue saveDialogue;
    private final RedissonClient redissonClient;
    private final ThreadPoolExecutor threadPoolExecutor;
    private final IConversationService conversationService;

    @Value("${rag.ai.siliconflow.chat-model}")
    private String chatModel;
    @Value("${rag.topK}")
    private int topK;
    @Value("${rag.rerank.top-n}")
    private int rerankTopN;
    @Value("${rag.ai.siliconflow.url}")
    private String url;
    @Value("${rag.ai.siliconflow.api-key}")
    private String apiKey;
    @Value("${rag.chat.max-model-wait}")
    private Long modelWait;

    @Override
    @TimeAnnotation(method = "流式问答处理")
    public void chat(QuestionReq req, SseEmitter emitter) {
        Long userId = UsersContext.requireUserId();
        if (req.getKbId() <= 0 || StrUtil.isBlank(req.getQuestion())) {
            throw new ClientException("请选择知识库并输入问题");
        }
        Long conversationId = req.getConversationId();
        if (conversationId != null && conversationId <= 0) {
            throw new ClientException("会话 ID 不正确");
        }
        if (conversationId == null) {
            conversationId = conversationService.addConversation(req.getKbId(), req.getQuestion());
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
            threadPoolExecutor.execute(() -> {
                try {
                    List<NeedChunksResp> needChunks = chatStream(req, emitter, userId);
                    emitter.send(SseEmitter.event()
                            .name("done")
                            .data(needChunks, APPLICATION_JSON));
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

    @Override
    public List<NeedChunksResp> chatStream(QuestionReq req, SseEmitter emitter, Long userId) {
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
            if (closed.get()) {
                throw new ServiceException("流式连接已关闭");
            }

            // 持久化用户问题到信息表并返回此次对话轮次
            turnNo = saveDialogue.saveQuestion(req.getConversationId(), userId,
                    req.getKbId(), req.getQuestion());

            String question = req.getQuestion();
            List<Reference> references = searchChecks.mixSearch(req.getKbId(), question, topK);
            if (closed.get()) {
                throw new ServiceException("流式连接已关闭");
            }
            if (references.isEmpty()) {
                throw new ClientException("未检索到知识库或知识库资料为空");
            }
            references = rerankChunks.rerank(question, references, rerankTopN);
            if (closed.get()) {
                throw new ServiceException("流式连接已关闭");
            }
            // 获取请求体和本次参考资料
            ParseReferences parsed = getBodyAndResp(question, references);
            List<NeedChunksResp> needChunks = parsed.getNeedChunks();
            // 回答参考资料
            resp.setNeedChunks(needChunks);
            JsonObject body = parsed.getBody();
            body.addProperty("stream", true);
            Call call = httpClient.newCall(getRequest(body));
            activeCall.set(call);
            if (closed.get()) {
                throw new ServiceException("流式连接已关闭");
            }
            // 建立模型连接开始读取回答响应，之前先检查用户是否已经关闭防止额外计费
            call.timeout().timeout(modelWait, TimeUnit.SECONDS);
            try (Response response = call.execute()) {
                ResponseBody responseBody = response.body();
                if (!response.isSuccessful() || responseBody == null) {
                    throw new ServiceException("模型连接失败");
                }
                readStream(responseBody, resp, answer, emitter);
                if (closed.get()) {
                    throw new ServiceException("流式连接已关闭");
                }
                // 持久化对话到信息表中为对话历史
                saveDialogue.storeDialogue(toStoreDTO(req, userId, turnNo, resp, "success", null));
                return needChunks;
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
                    saveDialogue.storeDialogue(toStoreDTO(req, userId, turnNo, resp, "failed", message));
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
        // 提前断流、输出长度受限等情况都不能发送成功的 done 事件。
        if (!"stop".equals(resp.getFinishReason())) {
            throw new ServiceException("模型回答异常终止");
        }
        resp.setAnswer(answer.toString());
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

    /** 写回前端失败，用于和「上游模型出错」区分开。 */
    private static final class ClientGoneException extends RuntimeException {
        ClientGoneException(Throwable cause) {
            super(cause);
        }
    }

    private ParseReferences getBodyAndResp(String question, List<Reference> references) {
        StringBuilder contextBuilder = new StringBuilder();
        List<NeedChunksResp> needChunksRespList = new ArrayList<>();
        for (int j = 0; j < references.size(); j++) {
            int num = j + 1;
            Reference reference = references.get(j);
            String docName = reference.getDocName();
            long docId = reference.getDocId();
            contextBuilder.append("【%s】[%s]%s".formatted(docName, num, reference.getContent()))
                    .append("\n");
            needChunksRespList.add(new NeedChunksResp(num, docId, docName, reference.getChunkId()));
        }
        String context = contextBuilder.toString();
        JsonObject body = new JsonObject();
        JsonArray input = getJsonElements(question, context);
        body.addProperty("model", chatModel);
        body.add("messages", input);
        body.addProperty("temperature", 0.2);
        body.addProperty("max_tokens", 1024);
        body.addProperty("stream", false);
        return new ParseReferences(body, needChunksRespList);
    }

    @NotNull
    private static JsonArray getJsonElements(String question, String context) {
        JsonArray input = new JsonArray();
        JsonObject system = new JsonObject();
        JsonObject reference = new JsonObject();
        JsonObject user = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", BASE_PROMPT);
        reference.addProperty("role", "user");
        reference.addProperty("content", "【参考资料】" + context);
        user.addProperty("role", "user");
        user.addProperty("content", question);
        input.add(system);
        input.add(reference);
        input.add(user);
        return input;
    }

    private Request getRequest(JsonObject body) {
        Request.Builder request = new Request.Builder()
                .url(url + "/v1/chat/completions")
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
