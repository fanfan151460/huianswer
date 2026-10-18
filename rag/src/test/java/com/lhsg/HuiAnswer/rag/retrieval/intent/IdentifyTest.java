package com.lhsg.HuiAnswer.rag.retrieval.intent;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.lhsg.HuiAnswer.rag.config.ApiUrl;
import com.lhsg.HuiAnswer.rag.knowledge.dao.entity.KnowledgeBaseDO;
import com.lhsg.HuiAnswer.rag.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.lhsg.HuiAnswer.rag.retrieval.dto.IntentResultDTO;
import com.lhsg.HuiAnswer.rag.retrieval.intent.enums.IntentEnum;
import okhttp3.*;
import okio.Buffer;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class IdentifyTest {
    private final Gson gson = new Gson();
    private final KnowledgeBaseMapper baseMapper = mock(KnowledgeBaseMapper.class);
    private final Map<String, String> outputs = new HashMap<>();
    private final Map<String, Integer> statuses = new HashMap<>();
    private final Map<String, String> backupOutputs = new HashMap<>();
    private final Map<String, Integer> backupStatuses = new HashMap<>();
    private final List<Request> capturedRequests = new ArrayList<>();
    private final List<Long> capturedTimeoutNanos = new ArrayList<>();
    private final List<JsonObject> capturedBodies = new ArrayList<>();
    private final AtomicInteger requestCount = new AtomicInteger();
    private String finishReason = "stop";
    private Identify identify;

    @BeforeAll
    static void initializeEntityMetadata() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "intent-test");
        assistant.setCurrentNamespace("intent-test");
        TableInfoHelper.initTableInfo(assistant, KnowledgeBaseDO.class);
    }

    @BeforeEach
    void setUp() {
        when(baseMapper.selectList(any())).thenReturn(List.of(
                new KnowledgeBaseDO().setId(2L).setName("会议室管理").setDescription("预约、签到、取消与信用分"),
                new KnowledgeBaseDO().setId(5L).setName("财务报销").setDescription("差旅费用和报销材料")));
        // 在内存中模拟每个子问题的 HTTP 响应，不连接外部服务。
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            requestCount.incrementAndGet();
            Buffer buffer = new Buffer();
            chain.request().body().writeTo(buffer);
            JsonObject request = gson.fromJson(buffer.readUtf8(), JsonObject.class);
            capturedBodies.add(request);
            capturedRequests.add(chain.request());
            capturedTimeoutNanos.add(chain.call().timeout().timeoutNanos());
            boolean backup = "backup.test".equals(chain.request().url().host());
            JsonObject input = gson.fromJson(request.getAsJsonArray("messages").get(1)
                    .getAsJsonObject().get("content").getAsString(), JsonObject.class);
            String question = input.get("question").getAsString();
            JsonObject message = new JsonObject();
            message.addProperty("content", (backup ? backupOutputs : outputs)
                    .getOrDefault(question, "{\"intent\":\"CHAT\",\"kbIds\":[]}"));
            JsonObject choice = new JsonObject();
            choice.addProperty("finish_reason", finishReason);
            choice.add("message", message);
            JsonArray choices = new JsonArray();
            choices.add(choice);
            JsonObject response = new JsonObject();
            response.add("choices", choices);
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code((backup ? backupStatuses : statuses).getOrDefault(question, 200)).message("test-response")
                    .body(ResponseBody.create(response.toString(), MediaType.get("application/json"))).build();
        }).build();
        ApiUrl apiUrl = new ApiUrl();
        apiUrl.setChatUrl("http://intent.test");
        apiUrl.setChatPath("/v1/chat/completions");
        apiUrl.setUrl("http://backup.test");
        identify = new Identify(baseMapper, client, gson, apiUrl);
        ReflectionTestUtils.setField(identify, "apiKey", "fake-key-for-local-test");
        ReflectionTestUtils.setField(identify, "chatModel", "test-model");
        ReflectionTestUtils.setField(identify, "maxTokens", 512);
        ReflectionTestUtils.setField(identify, "temperature", 0.0);
        ReflectionTestUtils.setField(identify, "timeoutSeconds", 10);
    }

    @Test
    @SuppressWarnings("unchecked")
    void singleCallsReuseCatalogAndValidateRequest() {
        outputs.put("会议室怎么取消？", "{\"intent\":\"KNOWLEDGE\",\"kbIds\":[2]}");
        outputs.put("报销需要什么材料？", "{\"intent\":\"KNOWLEDGE\",\"kbIds\":[5,5]}");

        var catalog = identify.loadCatalog();
        List<IntentResultDTO> results = List.of(
                identify.identifyOne("会议室怎么取消？", 0, catalog),
                identify.identifyOne("报销需要什么材料？", 1, catalog),
                identify.identifyOne("谢谢", 2, catalog));

        assertEquals(3, requestCount.get());
        assertEquals(List.of(0, 1, 2), results.stream().map(IntentResultDTO::getIndex).toList());
        assertEquals(List.of(2L), results.get(0).getKbIds());
        assertEquals(List.of(5L), results.get(1).getKbIds());
        assertEquals(IntentEnum.CHAT, results.get(2).getIntent());
        for (JsonObject body : capturedBodies) {
            assertFalse(body.has("response_format"));
            assertFalse(body.get("stream").getAsBoolean());
            assertEquals("disabled", body.getAsJsonObject("thinking").get("type").getAsString());
            assertFalse(body.has("enable_thinking"));
            JsonObject input = input(body);
            assertTrue(input.has("question"));
            assertFalse(input.has("subQuestions"));
            assertFalse(input.has("index"));
            assertEquals(2, input.getAsJsonArray("knowledgeBases").size());
            assertTrue(input.toString().contains("预约、签到、取消与信用分"));
            assertFalse(input.toString().contains("docNames"));
        }
        ArgumentCaptor<Wrapper<KnowledgeBaseDO>> queryCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(baseMapper, times(1)).selectList(queryCaptor.capture());
        LambdaQueryWrapper<KnowledgeBaseDO> query = (LambdaQueryWrapper<KnowledgeBaseDO>) queryCaptor.getValue();
        assertEquals("id,name,description", query.getSqlSelect());
    }

    @Test
    void singleEntryUsesBackendIndexAndDoesNotReloadSharedCatalog() {
        outputs.put("设备报修", "{\"index\":999,\"intent\":\"NO_MATCH\",\"kbIds\":[]}");

        IntentResultDTO result = identify.identifyOne("设备报修", 7, identify.loadCatalog());

        assertEquals(7, result.getIndex());
        assertEquals(IntentEnum.NO_MATCH, result.getIntent());
        assertEquals(1, requestCount.get());
        verify(baseMapper, times(1)).selectList(any());
        assertFalse(gson.toJson(result).contains("searchAll"));
        assertFalse(gson.toJson(result).contains("clarifyMessage"));
    }

    @Test
    void httpFailureOnlyFallsBackForThatQuestion() {
        outputs.put("会议室规则", "{\"intent\":\"KNOWLEDGE\",\"kbIds\":[2]}");
        statuses.put("报销规则", 503);

        var catalog = identify.loadCatalog();
        List<IntentResultDTO> results = List.of(
                identify.identifyOne("会议室规则", 0, catalog),
                identify.identifyOne("报销规则", 1, catalog),
                identify.identifyOne("谢谢", 2, catalog));

        assertEquals(IntentEnum.KNOWLEDGE, results.get(0).getIntent());
        assertEquals(IntentEnum.NO_MATCH, results.get(1).getIntent());
        assertEquals(IntentEnum.CHAT, results.get(2).getIntent());
        assertEquals(List.of(0, 1, 2), results.stream().map(IntentResultDTO::getIndex).toList());
        assertEquals(3, requestCount.get());
    }

    @Test
    void invalidJsonOnlyFallsBackForThatQuestion() {
        outputs.put("报销规则", "not-json");
        outputs.put("这个怎么办", "{\"intent\":\"CLARIFY\",\"kbIds\":[]}");

        var catalog = identify.loadCatalog();
        List<IntentResultDTO> results = List.of(
                identify.identifyOne("谢谢", 0, catalog),
                identify.identifyOne("报销规则", 1, catalog),
                identify.identifyOne("这个怎么办", 2, catalog));

        assertEquals(List.of(IntentEnum.CHAT, IntentEnum.NO_MATCH, IntentEnum.CLARIFY),
                results.stream().map(IntentResultDTO::getIntent).toList());
        assertEquals(3, requestCount.get());
    }

    @Test
    void invalidCategoryOrKbIdsFallBackToNoMatch() {
        List<String> invalid = List.of(
                "{}",
                "{\"intent\":\"OTHER\",\"kbIds\":[]}",
                "{\"intent\":\"KNOWLEDGE\",\"kbIds\":[999]}",
                "{\"intent\":\"KNOWLEDGE\",\"kbIds\":[]}",
                "{\"intent\":\"KNOWLEDGE\",\"kbIds\":[null]}",
                "{\"intent\":\"CHAT\",\"kbIds\":[2]}",
                "{\"intent\":\"CLARIFY\"}");
        var catalog = identify.loadCatalog();
        for (String output : invalid) {
            outputs.put("测试问题", output);
            IntentResultDTO result = identify.identifyOne("测试问题", 4, catalog);
            assertEquals(4, result.getIndex());
            assertEquals(IntentEnum.NO_MATCH, result.getIntent());
            assertTrue(result.getKbIds().isEmpty());
        }
    }

    @Test
    void emptyCatalogStillAllowsOrdinaryChat() {
        when(baseMapper.selectList(any())).thenReturn(List.of());
        assertEquals(IntentEnum.CHAT, identify.identifyOne("你好", 0, identify.loadCatalog()).getIntent());
    }

    @Test
    void truncatedResultFallsBackEvenWhenJsonIsValid() {
        outputs.put("会议室规则", "{\"intent\":\"KNOWLEDGE\",\"kbIds\":[2]}");
        finishReason = "length";
        assertEquals(IntentEnum.NO_MATCH, identify.identifyOne("会议室规则", 0, identify.loadCatalog()).getIntent());
    }

    @Test
    void databaseFailurePropagatesAndInvalidTimeoutFallsBackToNoMatch() {
        ReflectionTestUtils.setField(identify, "timeoutSeconds", -1);
        var catalog = identify.loadCatalog();
        IntentResultDTO result = identify.identifyOne("会议室规则", 0, catalog);
        assertEquals(IntentEnum.NO_MATCH, result.getIntent());
        when(baseMapper.selectList(any())).thenThrow(new IllegalStateException("test database unavailable"));
        assertThrows(IllegalStateException.class, identify::loadCatalog);
        assertEquals(0, requestCount.get());
    }

    @Test
    void backupUsesConfiguredModelProviderAndItsOwnTimeout() {
        enableBackup();
        ReflectionTestUtils.setField(identify, "timeoutSeconds", 3);
        ReflectionTestUtils.setField(identify, "backupTimeoutSeconds", 7);
        statuses.put("报销规则", 503);
        backupOutputs.put("报销规则", "{\"intent\":\"KNOWLEDGE\",\"kbIds\":[5]}");

        IntentResultDTO result = identify.identifyOne("报销规则", 2, identify.loadCatalog());

        assertEquals(IntentEnum.KNOWLEDGE, result.getIntent());
        assertEquals(List.of(5L), result.getKbIds());
        assertEquals(2, requestCount.get());
        assertEquals("test-model", capturedBodies.get(0).get("model").getAsString());
        assertEquals("zai-org/GLM-5.3-Flash", capturedBodies.get(1).get("model").getAsString());
        assertEquals("intent.test", capturedRequests.get(0).url().host());
        assertEquals("backup.test", capturedRequests.get(1).url().host());
        assertEquals("Bearer fake-backup-key", capturedRequests.get(1).header("Authorization"));
        assertFalse(capturedBodies.get(1).has("response_format"));
        assertFalse(capturedBodies.get(1).get("enable_thinking").getAsBoolean());
        assertEquals(List.of(TimeUnit.SECONDS.toNanos(3), TimeUnit.SECONDS.toNanos(7)), capturedTimeoutNanos);
    }

    @Test
    void exhaustedBackupFallsBackToNoMatch() {
        enableBackup();
        statuses.put("报销规则", 503);
        backupStatuses.put("报销规则", 503);

        IntentResultDTO result = identify.identifyOne("报销规则", 3, identify.loadCatalog());

        assertEquals(2, requestCount.get());
        assertEquals(IntentEnum.NO_MATCH, result.getIntent());
    }

    @Test
    void validNoMatchDoesNotCallBackup() {
        outputs.put("外部业务", "{\"intent\":\"NO_MATCH\",\"kbIds\":[]}");

        IntentResultDTO result = identify.identifyOne("外部业务", 0, identify.loadCatalog());

        assertEquals(IntentEnum.NO_MATCH, result.getIntent());
        assertEquals(1, requestCount.get());
    }

    private void enableBackup() {
        ReflectionTestUtils.setField(identify, "backupModel", "zai-org/GLM-5.3-Flash");
        ReflectionTestUtils.setField(identify, "backupApiKey", "fake-backup-key");
    }

    private JsonObject input(JsonObject body) {
        return gson.fromJson(body.getAsJsonArray("messages").get(1)
                .getAsJsonObject().get("content").getAsString(), JsonObject.class);
    }
}
