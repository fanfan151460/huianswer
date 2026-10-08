package com.lhsg.HuiAnswer.rag.chat.service.impl;

import cn.hutool.core.util.StrUtil;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.lhsg.HuiAnswer.framework.exception.ClientException;
import com.lhsg.HuiAnswer.framework.exception.ServiceException;
import com.lhsg.HuiAnswer.rag.chat.dto.LLMChatResp;
import com.lhsg.HuiAnswer.rag.chat.dto.NeedChunksResp;
import com.lhsg.HuiAnswer.rag.chat.dto.ParseReferences;
import com.lhsg.HuiAnswer.rag.chat.dto.QuestionReq;
import com.lhsg.HuiAnswer.rag.chat.service.IChatService;
import com.lhsg.HuiAnswer.rag.retrieval.dto.Reference;
import com.lhsg.HuiAnswer.rag.retrieval.search.SearchChunks;
import com.lhsg.HuiAnswer.rag.retrieval.rerank.RerankChunks;
import lombok.RequiredArgsConstructor;
import okhttp3.*;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

import static com.lhsg.HuiAnswer.rag.chat.dto.prompt.System.BASE_PROMPT;

@Service
@RequiredArgsConstructor
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
