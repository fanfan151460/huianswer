package com.lhsg.HuiAnswer.rag.chat.service.impl;

import cn.hutool.core.util.StrUtil;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.lhsg.HuiAnswer.framework.exception.ClientException;
import com.lhsg.HuiAnswer.framework.exception.ServiceException;
import com.lhsg.HuiAnswer.rag.chat.dto.LLMChatResp;
import com.lhsg.HuiAnswer.rag.chat.dto.QuestionReq;
import com.lhsg.HuiAnswer.rag.chat.service.IChatService;
import com.lhsg.HuiAnswer.rag.retrieval.dto.References;
import com.lhsg.HuiAnswer.rag.retrieval.search.SearchChecks;
import lombok.RequiredArgsConstructor;
import okhttp3.*;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

import static com.lhsg.HuiAnswer.rag.chat.dto.prompt.System.BASE_PROMPT;

@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements IChatService {
    private final SearchChecks searchChecks;
    private final OkHttpClient httpClient;
    private final Gson gson;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    @Value("${rag.ai.siliconflow.chat-model}")
    private String chatModel;
    @Value("${rag.topK}")
    private int topK;
    @Value("${rag.ai.siliconflow.url}")
    private String url;
    @Value("${rag.ai.siliconflow.api-key}")
    private String apiKey;

    @Override
    public String chat(QuestionReq req){
        String question = req.getQuestion();
        long kbId = req.getKbId();
        List<References> references = searchChecks.search(kbId, question, topK);
        if (references.isEmpty()) {
            throw new ClientException("未检索到知识库或知识库资料为空");
        }
        JsonObject body = getBody(question, references);
        Request request = getRequest(body);
        Call call = httpClient.newCall(request);
        String text = "";
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
        String answer = getAnswer(text);
        if (StrUtil.isBlank(answer)) {
            throw new ServiceException("服务器繁忙中");
        }
        return answer;
    }

    private JsonObject getBody(String question, List<References> references) {
        String context = references.stream()
                .map(r -> "【%s】%s".formatted(r.getDocName(), r.getContent()))
                .collect(Collectors.joining("\n"));
        JsonObject body = new JsonObject();
        JsonArray input = getJsonElements(question, context);
        body.addProperty("model", chatModel);
        body.add("messages", input);
        body.addProperty("temperature", 0.2);
        body.addProperty("max_tokens", 1024);
        body.addProperty("stream", false);
        return body;
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
        reference.addProperty("content", context);
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

    private String getAnswer(String text) {
        JsonObject resp = gson.fromJson(text, JsonObject.class);
        JsonObject choices = resp.getAsJsonArray("choices").get(0)
                .getAsJsonObject();
        int tokens = resp.getAsJsonObject("usage").get("total_tokens").getAsInt();
        String model = resp.get("model").getAsString();
        String finishReason = choices.get("finish_reason").getAsString();
        String answer = choices.getAsJsonObject("message").get("content").getAsString();
        // 后续可能使用
        LLMChatResp chatResp = new LLMChatResp().setAnswer(answer).setModel(model)
                .setFinishReason(finishReason).setTokens(tokens);
        return answer;
    }
}
