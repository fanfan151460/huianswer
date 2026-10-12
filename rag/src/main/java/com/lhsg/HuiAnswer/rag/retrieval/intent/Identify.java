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
import java.util.*;
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

    @Value("${rag.ai.siliconflow.api-key}")
    private String apiKey;
    @Value("${rag.ai.siliconflow.chat-model}")
    private String chatModel;
    @Value("${rag.intent.max-tokens}")
    private int maxTokens;
    @Value("${rag.intent.temperature}")
    private double temperature;
    @Value("${rag.intent.timeout-seconds}")
    private int timeoutSeconds;

    /** 一次分类全部子问题；只有 NO_MATCH 才走全库，歧义仍走 CLARIFY。 */
    @TimeAnnotation(method = "批量意图路由")
    public List<IntentResultDTO> identify(List<String> subQuestions) {
        if (subQuestions == null || subQuestions.isEmpty()) {
            return List.of();
        }
        // 数据库和本地配置错误正常抛出，不伪装成模型路由失败。
        List<KnowledgeCatalogDTO> catalog = loadCatalog();
        Set<Long> allowedIds = catalog.stream().map(KnowledgeCatalogDTO::getKbId)
                .collect(Collectors.toSet());
        Call call = httpClient.newCall(getRequest(subQuestions, catalog));
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
            return validateResults(result, subQuestions.size(), allowedIds);
        } catch (Exception e) {
            log.warn("模型意图路由失败，降级为 NO_MATCH 全库检索，异常类型：{}",
                    e.getClass().getSimpleName());
            return fallbackResults(subQuestions.size());
        }
    }

    /** 一次查询知识库表；模型按名称和业务描述选库，不读取文档目录或正文。 */
    private List<KnowledgeCatalogDTO> loadCatalog() {
        return baseMapper.selectList(Wrappers.<KnowledgeBaseDO>lambdaQuery()
                        .select(KnowledgeBaseDO::getId, KnowledgeBaseDO::getName, KnowledgeBaseDO::getDescription)
                        .orderByAsc(KnowledgeBaseDO::getId)).stream()
                .map(base -> new KnowledgeCatalogDTO().setKbId(base.getId())
                        .setName(base.getName()).setDescription(base.getDescription()))
                .toList();
    }

    private Request getRequest(List<String> subQuestions, List<KnowledgeCatalogDTO> catalog) {
        JsonArray questions = new JsonArray();
        for (int i = 0; i < subQuestions.size(); i++) {
            JsonObject question = new JsonObject();
            question.addProperty("index", i);
            question.addProperty("question", subQuestions.get(i));
            questions.add(question);
        }
        JsonObject input = new JsonObject();
        input.add("subQuestions", questions);
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
        JsonObject responseFormat = new JsonObject();
        responseFormat.addProperty("type", "json_object");
        body.add("response_format", responseFormat);
        return new Request.Builder().url(apiUrl.chatCompletionsUrl())
                .header("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(body.toString(), JSON)).build();
    }

    private List<IntentResultDTO> validateResults(IntentRespDTO response, int count, Set<Long> allowedIds) {
        if (response == null || response.getItems() == null || response.getItems().size() != count) {
            throw new IllegalStateException("意图路由结果数量不完整");
        }
        Map<Integer, IntentResultDTO> byIndex = new HashMap<>();
        for (IntentResultDTO item : response.getItems()) {
            if (item == null || item.getIndex() == null || item.getIndex() < 0 || item.getIndex() >= count
                    || item.getIntent() == null || item.getKbIds() == null
                    || byIndex.putIfAbsent(item.getIndex(), item) != null) {
                throw new IllegalStateException("意图路由返回了无效下标或分类");
            }
            List<Long> ids = item.getKbIds().stream().distinct().toList();
            if (item.getIntent() == IntentEnum.KNOWLEDGE) {
                if (ids.isEmpty() || !allowedIds.containsAll(ids)) {
                    throw new IllegalStateException("意图路由返回了无效知识库");
                }
            } else if (!ids.isEmpty()) {
                throw new IllegalStateException("CHAT、CLARIFY、NO_MATCH 不能携带知识库 ID");
            }
            item.setKbIds(ids);
        }
        List<IntentResultDTO> results = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            results.add(byIndex.get(i));
        }
        return results;
    }

    private List<IntentResultDTO> fallbackResults(int count) {
        List<IntentResultDTO> results = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            results.add(new IntentResultDTO().setIndex(i)
                    .setIntent(IntentEnum.NO_MATCH).setKbIds(List.of()));
        }
        return results;
    }
}
