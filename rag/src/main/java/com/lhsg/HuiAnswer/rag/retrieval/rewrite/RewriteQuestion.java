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
    @Value("${rag.rewrite.backup-model:}")
    private String backupModel;
    // 复用向量化、重排序使用的硅基流动密钥。
    @Value("${rag.ai.siliconflow.api-key}")
    private String backupApiKey;
    @Value("${rag.rewrite.backup-timeout-seconds:5}")
    private int backupTimeoutSeconds;

    /** 主模型一次、备用模型一次；都失败才退回原问题。 */
    @TimeAnnotation(method = "问题改写与拆分")
    public RewriteResultDTO rewrite(String question, List<SearchMemoryDTO> history) {
        RewriteResultDTO fallback = new RewriteResultDTO()
                .setRewrittenQuestion(question)
                .setSubQuestions(List.of(question)).setFallback(true).setModel(chatModel);
        try {
            return rewriteOnce(question, history, false);
        } catch (Exception primaryFailure) {
            fallback.setErrorType(primaryFailure.getClass().getSimpleName())
                    .setPrimaryErrorType(primaryFailure.getClass().getSimpleName())
                    .setPrimaryErrorMessage(primaryFailure.getMessage());
            if (StrUtil.isBlank(backupModel) || StrUtil.isBlank(backupApiKey)
                    || Thread.currentThread().isInterrupted()) {
                return fallback;
            }
            log.warn("改写主模型失败，切换备用模型 {}", backupModel);
            try {
                // 备用成功也是正常改写；fallback 只表示最终退回原问题。
                return rewriteOnce(question, history, true)
                        .setPrimaryErrorType(primaryFailure.getClass().getSimpleName())
                        .setPrimaryErrorMessage(primaryFailure.getMessage());
            } catch (Exception backupFailure) {
                log.warn("问题改写与拆分主备模型均失败，使用原问题，异常类型：{}",
                        backupFailure.getClass().getSimpleName());
                return fallback.setBackupUsed(true).setModel(backupModel)
                        .setErrorType(backupFailure.getClass().getSimpleName());
            }
        }
    }

    /** 两个模型共用消息、JSON 协议和校验，只切换模型、鉴权及各平台的请求参数。 */
    private RewriteResultDTO rewriteOnce(String question, List<SearchMemoryDTO> history,
                                         boolean useBackup) throws IOException {
        String model = useBackup ? backupModel : chatModel;
        String modelContent = null;
        String finishReason = null;
        String host = null;
        JsonObject modelResponse = null;
        Integer reasoningLength = null;
        int timeout = useBackup ? backupTimeoutSeconds : timeoutSeconds;
        long start = System.nanoTime();
        try {
            // 模型名只选择一次，同时传给请求体和日志，避免显示与实际调用错位。
            Request request = getRequest(question, history, useBackup, model);
            host = request.url().host();
            if (enabledAssess) {
                log.info("开始改写调用，model={}，host={}，backup={}，timeoutSeconds={}",
                        model, host, useBackup, timeout);
            }
            Call call = httpClient.newCall(request);
            call.timeout().timeout(timeout, TimeUnit.SECONDS);
            try (Response response = call.execute()) {
                ResponseBody responseBody = response.body();
                if (!response.isSuccessful() || responseBody == null) {
                    // 错误体常含模型名、鉴权或余额信息；不读出来就只剩一个 HTTP 码。
                    String errorBody = "";
                    if (responseBody != null) {
                        try {
                            errorBody = responseBody.string();
                        } catch (IOException readFailure) {
                            errorBody = "<读取响应体失败：" + readFailure.getMessage() + ">";
                        }
                    }
                    throw new IOException("问题改写接口返回 HTTP " + response.code()
                            + "，响应体：" + StrUtil.maxLength(errorBody, 1000));
                }
                modelResponse = gson.fromJson(responseBody.string(), JsonObject.class);
                JsonObject choice = modelResponse.getAsJsonArray("choices").get(0).getAsJsonObject();
                finishReason = choice.get("finish_reason").getAsString();
                JsonObject message = choice.getAsJsonObject("message");
                modelContent = message.get("content").getAsString();
                if (message.has("reasoning_content") && !message.get("reasoning_content").isJsonNull()) {
                    reasoningLength = message.get("reasoning_content").getAsString().length();
                }
                // 截断的改写可能丢掉问题条件，不能拿去检索。
                if (!"stop".equals(finishReason)) {
                    throw new IllegalStateException("问题改写未完整结束：" + finishReason);
                }
                RewriteResultDTO result = parseResult(modelContent).setFallback(false).setErrorType(null)
                        .setModel(model).setBackupUsed(useBackup)
                        .setPrimaryErrorType(null).setPrimaryErrorMessage(null);
                if (enabledAssess) {
                    log.info("改写调用成功，model={}，host={}，responseModel={}，backup={}，elapsedMs={}，usage={}",
                            model, host, modelResponse.get("model"), useBackup,
                            (System.nanoTime() - start) / 1_000_000, modelResponse.get("usage"));
                }
                return result;
            }
        } catch (Exception e) {
            if (enabledAssess) {
                String preview = modelContent == null ? null
                        : modelContent.substring(0, Math.min(modelContent.length(), 2000));
                // JSON 转义后可以看清纯空白输出；只在评测时打印模型正文。
                log.warn("改写模型调用失败，model={}，host={}，responseModel={}，backup={}，elapsedMs={}，异常类型：{}，原因：{}，finishReason={}，contentLength={}，reasoningLength={}，usage={}，模型正文：{}",
                        model, host, modelResponse == null ? null : modelResponse.get("model"), useBackup,
                        (System.nanoTime() - start) / 1_000_000,
                        e.getClass().getSimpleName(), e.getMessage(), finishReason,
                        modelContent == null ? null : modelContent.length(), reasoningLength,
                        modelResponse == null ? null : modelResponse.get("usage"), gson.toJson(preview));
            } else {
                log.warn("改写模型调用失败，model={}，异常类型：{}，原因：{}",
                        model, e.getClass().getSimpleName(), e.getMessage());
            }
            throw e;
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

    private Request getRequest(String question, List<SearchMemoryDTO> history, boolean useBackup, String model) {
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
        body.addProperty("model", model);
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
        // JSON 格式由提示词和示例约束；兼容模型可能附带的说明或代码围栏。
        // 此处不启用强制 JSON 模式，避免当前多轮改写输入反复返回纯空白。
        return new Request.Builder()
                .url(useBackup ? apiUrl.getUrl().replaceAll("/+$", "") + "/v1/chat/completions"
                        : apiUrl.chatCompletionsUrl())
                .header("Authorization", "Bearer " + (useBackup ? backupApiKey : apiKey))
                .post(RequestBody.create(body.toString(), JSON))
                .build();
    }
}
