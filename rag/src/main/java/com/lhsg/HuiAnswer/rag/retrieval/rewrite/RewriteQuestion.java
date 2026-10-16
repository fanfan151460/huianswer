package com.lhsg.HuiAnswer.rag.retrieval.rewrite;

import cn.hutool.core.util.StrUtil;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.lhsg.HuiAnswer.framework.monitor.TimeAnnotation;
import com.lhsg.HuiAnswer.rag.config.ApiUrl;
import com.lhsg.HuiAnswer.rag.retrieval.dto.RewriteResultDTO;
import com.lhsg.HuiAnswer.rag.memory.dto.search.SearchMemoryDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.lhsg.HuiAnswer.rag.retrieval.rewrite.prompt.REWRITE_QUESTION;

@Component
@RequiredArgsConstructor
@Slf4j
public class RewriteQuestion {
    private final OkHttpClient httpClient;
    private final Gson gson;
    private final ApiUrl apiUrl;

    // 两轮完整问答，共四条消息；这里只使用近期原文，不使用摘要。
    private static final int MAX_HISTORY_MESSAGES = 4;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    @Value("${rag.ai.deepseek.api-key}")
    private String apiKey;
    @Value("${rag.ai.deepseek.chat-model}")
    private String chatModel;
    @Value("${rag.rewrite.max-tokens}")
    private int maxTokens;
    @Value("${rag.rewrite.temperature}")
    private double temperature;
    @Value("${rag.rewrite.timeout-seconds}")
    private int timeoutSeconds;
    @Value("${rag.evaluation.enabled:false}")
    private boolean enabledAssess;

    /** 一次完成改写和拆分；失败时将原文作为唯一子问题。 */
    @TimeAnnotation(method = "问题改写与拆分")
    public RewriteResultDTO rewrite(String question, List<SearchMemoryDTO> history) {
        RewriteResultDTO fallback = new RewriteResultDTO()
                .setRewrittenQuestion(question)
                .setSubQuestions(List.of(question));
        // 第一轮也可能有多个需求，因此没有历史时仍然调用模型。
        String modelContent = null;
        try {
            Request request = getRequest(question, history);
            Call call = httpClient.newCall(request);
            call.timeout().timeout(timeoutSeconds, TimeUnit.SECONDS);
            try (Response response = call.execute()) {
                ResponseBody responseBody = response.body();
                if (!response.isSuccessful() || responseBody == null) {
                    throw new IOException("问题改写接口返回 HTTP " + response.code());
                }
                JsonObject json = gson.fromJson(responseBody.string(), JsonObject.class);
                JsonObject choice = json.getAsJsonArray("choices").get(0).getAsJsonObject();
                // 截断的改写可能丢掉问题条件，不能拿去检索。
                if (!"stop".equals(choice.get("finish_reason").getAsString())) {
                    throw new IllegalStateException("问题改写未完整结束");
                }
                modelContent = choice.getAsJsonObject("message").get("content").getAsString();
                return parseResult(modelContent);
            }
        } catch (Exception e) {
            if (enabledAssess) {
                // 仅本地评测开启时记录失败正文，便于区分围栏、缺字段和损坏的 JSON。
                String preview = modelContent == null ? "未读取到正文"
                        : modelContent.substring(0, Math.min(modelContent.length(), 2000));
                log.warn("问题改写与拆分失败，使用原问题，异常类型：{}，原因：{}，模型正文：{}",
                        e.getClass().getSimpleName(), e.getMessage(), preview);
            } else {
                log.warn("问题改写与拆分失败，使用原问题，异常类型：{}", e.getClass().getSimpleName());
            }
            return fallback;
        }
    }

    /** 兼容 JSON 外的说明和代码围栏；残缺、多对象或缺字段仍然走失败降级。 */
    private RewriteResultDTO parseResult(String content) {
        if (StrUtil.isBlank(content)) {
            throw new IllegalStateException("改写模型返回空正文");
        }
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new IllegalStateException("改写模型未返回完整 JSON 对象");
        }
        RewriteResultDTO result = gson.fromJson(content.substring(start, end + 1), RewriteResultDTO.class);
        if (result == null || StrUtil.isBlank(result.getRewrittenQuestion())
                || result.getSubQuestions() == null || result.getSubQuestions().isEmpty()
                || result.getSubQuestions().stream().anyMatch(StrUtil::isBlank)) {
            throw new IllegalStateException("改写与拆分结果不完整");
        }
        result.setRewrittenQuestion(result.getRewrittenQuestion().trim())
                .setSubQuestions(result.getSubQuestions().stream().map(String::trim).toList());
        // 未拆分时统一使用完整改写，保证两处文本一致。
        if (result.getSubQuestions().size() == 1) {
            result.setSubQuestions(List.of(result.getRewrittenQuestion()));
        }
        return result;
    }

    private Request getRequest(String question, List<SearchMemoryDTO> history) {
        JsonArray messages = new JsonArray();
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", REWRITE_QUESTION);
        messages.add(system);

        // history 已由消息服务筛成完整成功的问答对；subList 只读，不修改原集合。
        List<SearchMemoryDTO> recentHistory = history == null ? List.of() : history;
        int from = Math.max(0, recentHistory.size() - MAX_HISTORY_MESSAGES);
        for (SearchMemoryDTO memory : recentHistory.subList(from, recentHistory.size())) {
            JsonObject message = new JsonObject();
            message.addProperty("role", memory.getRole());
            message.addProperty("content", memory.getContent());
            messages.add(message);
        }
        JsonObject current = new JsonObject();
        current.addProperty("role", "user");
        current.addProperty("content", question);
        messages.add(current);

        JsonObject body = new JsonObject();
        body.addProperty("model", chatModel);
        body.add("messages", messages);
        body.addProperty("stream", false);
        body.addProperty("max_tokens", maxTokens);
        body.addProperty("temperature", temperature);
        JsonObject thinking = new JsonObject();
        thinking.addProperty("type", "disabled");
        body.add("thinking", thinking);
        JsonObject responseFormat = new JsonObject();
        responseFormat.addProperty("type", "json_object");
        body.add("response_format", responseFormat);
        return new Request.Builder()
                .url(apiUrl.chatCompletionsUrl())
                .header("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(body.toString(), JSON))
                .build();
    }
}
