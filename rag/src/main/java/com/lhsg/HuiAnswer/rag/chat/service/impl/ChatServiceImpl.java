package com.lhsg.HuiAnswer.rag.chat.service.impl;

import cn.hutool.core.util.StrUtil;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.lhsg.HuiAnswer.framework.exception.ClientException;
import com.lhsg.HuiAnswer.framework.exception.ServiceException;
import com.lhsg.HuiAnswer.framework.monitor.TimeAnnotation;
import com.lhsg.HuiAnswer.rag.chat.dto.LLMChatResp;
import com.lhsg.HuiAnswer.rag.chat.dto.NeedChunksResp;
import com.lhsg.HuiAnswer.rag.chat.dto.ParseReferences;
import com.lhsg.HuiAnswer.rag.chat.dto.QuestionReq;
import com.lhsg.HuiAnswer.rag.chat.service.IChatService;
import com.lhsg.HuiAnswer.rag.retrieval.dto.Reference;
import com.lhsg.HuiAnswer.rag.retrieval.search.SearchChunks;
import com.lhsg.HuiAnswer.rag.retrieval.rerank.RerankChunks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import okio.BufferedSource;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.lhsg.HuiAnswer.rag.chat.dto.prompt.System.BASE_PROMPT;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatServiceImpl implements IChatService {
    private final SearchChunks searchChecks;
    private final RerankChunks rerankChunks;
    private final OkHttpClient httpClient;
    private final Gson gson;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
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

    @Override
    public LLMChatResp chat(QuestionReq req){
        String question = req.getQuestion();
        long kbId = req.getKbId();
        List<Reference> references = searchChecks.mixSearch(kbId, question, topK);
        if (references.isEmpty()) {
            throw new ClientException("未检索到知识库或知识库资料为空");
        }
        references = rerankChunks.rerank(question, references, rerankTopN);
        ParseReferences parseReferences = getBodyAndResp(question, references);
        JsonObject body = parseReferences.getBody();
        List<NeedChunksResp> needChunks = parseReferences.getNeedChunks();
        Request request = getRequest(body);
        Call call = httpClient.newCall(request);
        String text;
        try (Response response = call.execute()) {
            ResponseBody responseBody = response.body();
            if (response.isSuccessful() && responseBody != null) {
                text = responseBody.string();
            } else {
                throw new ServiceException("模型连接失败");
            }
        } catch (Exception e) {
            throw new ServiceException("模型连接异常，请稍后再试");
        }
        LLMChatResp chatResp = getAnswer(text, needChunks);
        if (StrUtil.isBlank(chatResp.getAnswer())) {
            throw new ServiceException("服务器繁忙中");
        }
        return chatResp;
    }

    @Override
    @TimeAnnotation(method = "流式问答处理")
    public LLMChatResp chatStream(QuestionReq req, SseEmitter emitter) {
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

        try {
            // SSE 注释不属于正文；也能发现排队期间已经结束的连接，避免继续调用模型。
            emitter.send(SseEmitter.event().comment("connected"));
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
            ParseReferences parsed = getBodyAndResp(question, references);
            JsonObject body = parsed.getBody();
            body.addProperty("stream", true);
            Call call = httpClient.newCall(getRequest(body));
            activeCall.set(call);
            // 防止连接刚关闭、回调还没看到 call 的竞态。
            if (closed.get()) {
                call.cancel();
                throw new ServiceException("流式连接已关闭");
            }
            call.timeout().timeout(60, TimeUnit.SECONDS);
            try (Response response = call.execute()) {
                ResponseBody responseBody = response.body();
                if (!response.isSuccessful() || responseBody == null) {
                    throw new ServiceException("模型连接失败");
                }
                return readStream(responseBody, parsed.getNeedChunks(), emitter);
            }
        } catch (Exception e) {
            cancelCall.run();
            // 不记录请求头或完整响应，避免密钥和文档正文进入日志。
            log.warn("流式问答未完成，异常类型：{}", e.getClass().getSimpleName());
            throw new ServiceException("模型连接异常，请稍后再试");
        }
    }

    /** 一条 SSE 事件以空行结束；data 可以有多行，先拼完整事件再解析 JSON。 */
    private LLMChatResp readStream(ResponseBody body, List<NeedChunksResp> needChunks,
                                   SseEmitter emitter) throws IOException {
        LLMChatResp resp = new LLMChatResp().setModel(chatModel).setNeedChunks(needChunks);
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
                readChunk(data, resp,  emitter);
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
        return resp;
    }

    private void readChunk(String data, LLMChatResp resp,
                           SseEmitter emitter) throws IOException {
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
        // 只发送正文 content，角色信息和 reasoning_content 不作为正文展示。
        emitter.send(SseEmitter.event().name("delta").data(fragment));
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

    /**
     * 非流式输出转换返回实体方法
     */
    private LLMChatResp getAnswer(String text, List<NeedChunksResp> needChunks) {
        JsonObject resp = gson.fromJson(text, JsonObject.class);
        JsonObject choices = resp.getAsJsonArray("choices").get(0)
                .getAsJsonObject();
        int tokens = resp.getAsJsonObject("usage").get("total_tokens").getAsInt();
        String model = resp.get("model").getAsString();
        String finishReason = choices.get("finish_reason").getAsString();
        String answer = choices.getAsJsonObject("message").get("content").getAsString();

        if (!finishReason.equals("stop")) {
            throw new ServiceException("模型回答异常终止");
        }
        return new LLMChatResp().setAnswer(answer).setModel(model)
                .setFinishReason(finishReason).setTokens(tokens).setNeedChunks(needChunks);
    }
}
