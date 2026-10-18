package com.lhsg.HuiAnswer.rag.retrieval.rewrite;

import cn.hutool.core.util.StrUtil;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.lhsg.HuiAnswer.framework.exception.ServiceException;
import com.lhsg.HuiAnswer.framework.monitor.TimeAnnotation;
import com.lhsg.HuiAnswer.rag.config.ApiUrl;
import com.lhsg.HuiAnswer.rag.memory.dto.search.SearchMemoryDTO;
import com.lhsg.HuiAnswer.rag.retrieval.dto.RewriteResultDTO;
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
    @Value("${rag.rewrite.backup-model:}")
    private String backupModel;
    // 复用向量化、重排序使用的硅基流动密钥。
    @Value("${rag.ai.siliconflow.api-key}")
    private String backupApiKey;
    @Value("${rag.rewrite.backup-timeout-seconds:5}")
    private int backupTimeoutSeconds;

    /** 一次完成改写和拆分；失败时将原文作为唯一子问题。 */
    @TimeAnnotation(method = "问题改写与拆分")
    public RewriteResultDTO rewrite(String question, List<SearchMemoryDTO> history) {
        RewriteResultDTO fallback = new RewriteResultDTO()
                .setRewrittenQuestion(question)
                .setSubQuestions(List.of(question));
        // 第一轮也可能有多个需求，因此没有历史时仍然调用模型。
        String modelContent = null;
        try {
            try {
                modelContent = callModel(question, history, false);
                return parseResult(modelContent);
            } catch (Exception primaryFailure) {
                // 主模型失败后只尝试一次备用模型，未配置时直接降级。
                if (StrUtil.isBlank(backupModel) || StrUtil.isBlank(backupApiKey)
                        || Thread.currentThread().isInterrupted()) {
                    throw new ServiceException("模型请求参数异常");
                }
                modelContent = callModel(question, history, true);
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

    /** 调用指定模型并取回正文；HTTP 异常、截断和空正文都交由上层统一降级。 */
    private String callModel(String question, List<SearchMemoryDTO> history,
                             boolean useBackup) throws IOException {
        Request request = getRequest(question, history, useBackup);
        Call call = httpClient.newCall(request);
        call.timeout().timeout(useBackup ? backupTimeoutSeconds : timeoutSeconds, TimeUnit.SECONDS);
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
            return choice.getAsJsonObject("message").get("content").getAsString();
        }
    }

    /** 兼容 JSON 外的说明和代码围栏；残缺、多对象或缺字段仍然走失败降级。 */
    private RewriteResultDTO parseResult(String content) {
        if (StrUtil.isBlank(content)) {
            throw new IllegalStateException("改写模型返回空正文");
        }
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}') + 1;
        if (start < 0 || end < start) {
            throw new IllegalStateException("改写模型未返回完整 JSON 对象");
        }
        RewriteResultDTO result = gson.fromJson(content.substring(start, end), RewriteResultDTO.class);
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

    private Request getRequest(String question, List<SearchMemoryDTO> history, boolean useBackup) {
        JsonArray messages = new JsonArray();
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", REWRITE_QUESTION);
        messages.add(system);

        // 历史是待分析数据，不作为本次 API 对话中的 assistant 发言，避免续答旧对话。
        List<SearchMemoryDTO> recentHistory = history == null ? List.of() : history;
        int from = Math.max(0, recentHistory.size() - MAX_HISTORY_MESSAGES);
        JsonArray historyData = new JsonArray();
        for (SearchMemoryDTO memory : recentHistory.subList(from, recentHistory.size())) {
            JsonObject message = new JsonObject();
            message.addProperty("role", memory.getRole());
            message.addProperty("content", memory.getContent());
            historyData.add(message);
        }
        JsonObject input = new JsonObject();
        input.add("history", historyData);
        input.addProperty("question", question);
        JsonObject current = new JsonObject();
        current.addProperty("role", "user");
        current.addProperty("content", input.toString());
        messages.add(current);

        JsonObject body = new JsonObject();
        body.addProperty("model", useBackup ? backupModel : chatModel);
        body.add("messages", messages);
        body.addProperty("stream", false);
        body.addProperty("max_tokens", maxTokens);
        body.addProperty("temperature", temperature);
        if (useBackup) {
            body.addProperty("enable_thinking", false);
        } else {
            JsonObject thinking = new JsonObject();
            thinking.addProperty("type", "disabled");
            body.add("thinking", thinking);
        }
        // JSON 格式由提示词和示例约束；不启用 response_format，避免模型返回空正文。
        return new Request.Builder()
                .url(useBackup
                        ? apiUrl.getUrl().replaceAll("/+$", "") + "/v1/chat/completions"
                        : apiUrl.chatCompletionsUrl())
                .header("Authorization", "Bearer " + (useBackup ? backupApiKey : apiKey))
                .post(RequestBody.create(body.toString(), JSON))
                .build();
    }
}
