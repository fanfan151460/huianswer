package com.lhsg.HuiAnswer.rag.retrieval.intent;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.lhsg.HuiAnswer.framework.exception.ServiceException;
import com.lhsg.HuiAnswer.framework.monitor.TimeAnnotation;
import com.lhsg.HuiAnswer.rag.config.ApiUrl;
import com.lhsg.HuiAnswer.rag.knowledge.dao.entity.KnowledgeBaseDO;
import com.lhsg.HuiAnswer.rag.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.lhsg.HuiAnswer.rag.retrieval.dto.IntentRespDTO;
import com.lhsg.HuiAnswer.rag.retrieval.dto.IntentResultDTO;
import com.lhsg.HuiAnswer.rag.retrieval.dto.KnowledgeCatalogDTO;
import com.lhsg.HuiAnswer.rag.retrieval.intent.enums.IntentEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.lhsg.HuiAnswer.rag.retrieval.intent.prompt.IDENTIFY_INTENT_PROMPT;

@Component
@RequiredArgsConstructor
@Slf4j
public class Identify {
    private final KnowledgeBaseMapper baseMapper;
    private final OkHttpClient httpClient;
    private final Gson gson;
    private final ApiUrl apiUrl;

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    @Value("${rag.ai.deepseek.api-key}")
    private String apiKey;
    @Value("${rag.ai.deepseek.chat-model}")
    private String chatModel;
    @Value("${rag.intent.max-tokens}")
    private int maxTokens;
    @Value("${rag.intent.temperature}")
    private double temperature;
    @Value("${rag.intent.timeout-seconds}")
    private int timeoutSeconds;
    @Value("${rag.rewrite.backup-model:}")
    private String backupModel;
    // 复用向量化、重排序使用的硅基流动密钥。
    @Value("${rag.ai.siliconflow.api-key}")
    private String backupApiKey;
    @Value("${rag.rewrite.backup-timeout-seconds:5}")
    private int backupTimeoutSeconds;


    /**
     * 单个子问题独立路由，后端关联下标；模型失败仅降级当前子问题。
     */
    @TimeAnnotation(method = "单问题意图路由")
    public IntentResultDTO identifyOne(String question, int index, List<KnowledgeCatalogDTO> catalog) {
        Set<Long> allowedIds = catalog.stream().map(KnowledgeCatalogDTO::getKbId)
                .collect(Collectors.toSet());
        try {
            try {
                String callModel = callModel(question, catalog, false);
                return validateResult(callModel, index, allowedIds);
            } catch (Exception e) {
                if (StrUtil.isBlank(backupModel) || StrUtil.isBlank(backupApiKey)
                        || Thread.currentThread().isInterrupted()) {
                    throw new ServiceException("模型请求参数异常");
                }
                log.warn("主模型：{} 意图路由失败，question：{}, 异常信息：{}",chatModel, question, e.getMessage());
                String callModel = callModel(question, catalog, true);
                return validateResult(callModel, index, allowedIds);
            }
        } catch (Exception e) {
            log.warn("子问题 {} 模型意图路由失败，降级为 NO_MATCH，异常类型：{}",
                    index, e.getClass().getSimpleName());
            return new IntentResultDTO().setIndex(index)
                    .setIntent(IntentEnum.NO_MATCH).setKbIds(List.of());
        }
    }

    private String callModel(String question, List<KnowledgeCatalogDTO> catalog, boolean useBackup) throws IOException {
        Request request = getRequest(question, catalog, useBackup);
        Call call = httpClient.newCall(request);
        call.timeout().timeout(useBackup ? timeoutSeconds : backupTimeoutSeconds, TimeUnit.SECONDS);
        String content;
        try (Response response = call.execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                throw new IOException("意图路由接口返回 HTTP " + response.code());
            }
            JsonObject json = gson.fromJson(body.string(), JsonObject.class);
            JsonObject choice = json.getAsJsonArray("choices").get(0).getAsJsonObject();
            if (!"stop".equals(choice.get("finish_reason").getAsString())) {
                throw new IllegalStateException("意图路由未完整结束");
            }
            content = choice.getAsJsonObject("message").get("content").getAsString();
        }
        return content;
    }

    /**
     * 只查询知识库的名称和描述；目录供同一轮的各个子问题共享。
     */
    public List<KnowledgeCatalogDTO> loadCatalog() {
        return baseMapper.selectList(Wrappers.<KnowledgeBaseDO>lambdaQuery()
                        .select(KnowledgeBaseDO::getId, KnowledgeBaseDO::getName, KnowledgeBaseDO::getDescription)
                        .orderByAsc(KnowledgeBaseDO::getId)).stream()
                .map(base -> new KnowledgeCatalogDTO().setKbId(base.getId())
                        .setName(base.getName()).setDescription(base.getDescription()))
                .toList();
    }

    private Request getRequest(String question, List<KnowledgeCatalogDTO> catalog, boolean useBackup) {
        JsonObject input = new JsonObject();
        input.addProperty("question", question);
        input.add("knowledgeBases", gson.toJsonTree(catalog));
        JsonArray messages = new JsonArray();
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", IDENTIFY_INTENT_PROMPT);
        messages.add(system);
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", input.toString());
        messages.add(user);

        JsonObject body = new JsonObject();
        body.addProperty("model", chatModel);
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

    private IntentResultDTO validateResult(String content, int index, Set<Long> allowedIds) {
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}') + 1;
        if (start < 0 || end < start) {
            throw new IllegalStateException("改写模型未返回完整 JSON 对象");
        }
        IntentRespDTO response = gson.fromJson(content.substring(start, end), IntentRespDTO.class);
        if (response == null || response.getIntent() == null || response.getKbIds() == null) {
            throw new IllegalStateException("意图路由结果不完整");
        }
        List<Long> ids = response.getKbIds().stream().distinct().toList();
        if (response.getIntent() == IntentEnum.KNOWLEDGE) {
            if (ids.isEmpty() || !allowedIds.containsAll(ids)) {
                throw new IllegalStateException("意图路由返回了无效知识库");
            }
        } else if (!ids.isEmpty()) {
            throw new IllegalStateException("CHAT、CLARIFY、NO_MATCH 不能携带知识库 ID");
        }
        return new IntentResultDTO().setIndex(index).setIntent(response.getIntent()).setKbIds(ids);
    }
}
