package com.lhsg.HuiAnswer.rag.retrieval.intent;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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

    /** 单个子问题独立路由，后端关联下标；模型失败仅降级当前子问题。 */
    @TimeAnnotation(method = "单问题意图路由")
    public IntentResultDTO identifyOne(String question, int index, List<KnowledgeCatalogDTO> catalog) {
        Set<Long> allowedIds = catalog.stream().map(KnowledgeCatalogDTO::getKbId)
                .collect(Collectors.toSet());
        // 本地请求构造和超时配置错误正常抛出，不伪装成模型调用失败。
        Call call = httpClient.newCall(getRequest(question, catalog));
        call.timeout().timeout(timeoutSeconds, TimeUnit.SECONDS);
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
            String content = choice.getAsJsonObject("message").get("content").getAsString();
            IntentRespDTO result = gson.fromJson(content, IntentRespDTO.class);
            return validateResult(result, index, allowedIds);
        } catch (Exception e) {
            log.warn("子问题 {} 模型意图路由失败，降级为 NO_MATCH，异常类型：{}",
                    index, e.getClass().getSimpleName());
            return new IntentResultDTO().setIndex(index)
                    .setIntent(IntentEnum.NO_MATCH).setKbIds(List.of())
                    .setFallback(true).setErrorType(e.getClass().getSimpleName());
        }
    }

    /** 只查询知识库的名称和描述；目录供同一轮的各个子问题共享。 */
    public List<KnowledgeCatalogDTO> loadCatalog() {
        return baseMapper.selectList(Wrappers.<KnowledgeBaseDO>lambdaQuery()
                        .select(KnowledgeBaseDO::getId, KnowledgeBaseDO::getName, KnowledgeBaseDO::getDescription)
                        .orderByAsc(KnowledgeBaseDO::getId)).stream()
                .map(base -> new KnowledgeCatalogDTO().setKbId(base.getId())
                        .setName(base.getName()).setDescription(base.getDescription()))
                .toList();
    }

    private Request getRequest(String question, List<KnowledgeCatalogDTO> catalog) {
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
        JsonObject thinking = new JsonObject();
        thinking.addProperty("type", "disabled");
        body.add("thinking", thinking);
        JsonObject responseFormat = new JsonObject();
        responseFormat.addProperty("type", "json_object");
        body.add("response_format", responseFormat);
        return new Request.Builder().url(apiUrl.chatCompletionsUrl())
                .header("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(body.toString(), JSON)).build();
    }

    private IntentResultDTO validateResult(IntentRespDTO response, int index, Set<Long> allowedIds) {
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
