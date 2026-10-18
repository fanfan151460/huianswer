package com.lhsg.HuiAnswer.rag.retrieval.rewrite;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.lhsg.HuiAnswer.rag.config.ApiUrl;
import com.lhsg.HuiAnswer.rag.memory.dto.search.SearchMemoryDTO;
import com.lhsg.HuiAnswer.rag.retrieval.dto.RewriteResultDTO;
import okhttp3.*;
import okio.Buffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RewriteQuestionTest {
    private final Gson gson = new Gson();
    private final AtomicReference<String> modelOutput = new AtomicReference<>();
    private final AtomicReference<String> backupOutput = new AtomicReference<>();
    private final List<Request> capturedRequests = new ArrayList<>();
    private final List<JsonObject> capturedBodies = new ArrayList<>();
    private final AtomicReference<String> finishReason = new AtomicReference<>("stop");
    private final AtomicReference<JsonObject> capturedBody = new AtomicReference<>();
    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicInteger responseStatus = new AtomicInteger(200);
    private RewriteQuestion rewrite;
    private boolean blankWhenJsonMode;

    @BeforeEach
    void setUp() {
        // 在内存中返回模型响应，不访问外部接口，也不使用真实密钥。
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            requestCount.incrementAndGet();
            Buffer buffer = new Buffer();
            chain.request().body().writeTo(buffer);
            capturedBody.set(gson.fromJson(buffer.readUtf8(), JsonObject.class));
            capturedRequests.add(chain.request());
            capturedBodies.add(capturedBody.get());
            boolean backup = "backup.test".equals(chain.request().url().host());

            JsonObject message = new JsonObject();
            String content = backup ? backupOutput.get() : modelOutput.get();
            // 模拟本次对照现象：启用强制 JSON 时只返回空白。
            message.addProperty("content", blankWhenJsonMode && capturedBody.get().has("response_format")
                    ? "   " : content);
            JsonObject choice = new JsonObject();
            choice.addProperty("finish_reason", backup ? "stop" : finishReason.get());
            choice.add("message", message);
            JsonArray choices = new JsonArray();
            choices.add(choice);
            JsonObject body = new JsonObject();
            body.add("choices", choices);
            body.addProperty("model", capturedBody.get().get("model").getAsString());
            // 有 completion token 不代表正文可用；空白响应照样必须触发备用。
            JsonObject usage = new JsonObject();
            usage.addProperty("completion_tokens", 72);
            body.add("usage", usage);
            return new Response.Builder()
                    .request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(backup ? 200 : responseStatus.get()).message("test-response")
                    .body(ResponseBody.create(body.toString(), MediaType.get("application/json")))
                    .build();
        }).build();
        ApiUrl apiUrl = new ApiUrl();
        apiUrl.setChatUrl("http://rewrite.test");
        apiUrl.setChatPath("/v1/chat/completions");
        apiUrl.setUrl("http://backup.test/");
        rewrite = new RewriteQuestion(client, gson, apiUrl);
        ReflectionTestUtils.setField(rewrite, "apiKey", "fake-key-for-local-test");
        ReflectionTestUtils.setField(rewrite, "chatModel", "test-model");
        ReflectionTestUtils.setField(rewrite, "maxTokens", 512);
        ReflectionTestUtils.setField(rewrite, "temperature", 0.1);
        ReflectionTestUtils.setField(rewrite, "timeoutSeconds", 10);
        ReflectionTestUtils.setField(rewrite, "backupModel", "");
        ReflectionTestUtils.setField(rewrite, "backupApiKey", "fake-backup-key");
        ReflectionTestUtils.setField(rewrite, "backupTimeoutSeconds", 5);
    }

    @Test
    void firstRoundCanReturnMultipleSubQuestionsInOneRequest() {
        String input = "会议室预约怎么取消？差旅报销需要什么材料？";
        List<String> subQuestions = List.of("会议室预约怎么取消？", "差旅报销需要什么材料？");
        modelOutput.set(modelResult(input, subQuestions));

        RewriteResultDTO result = rewrite.rewrite(input, List.of());

        assertEquals(input, result.getRewrittenQuestion());
        assertEquals(subQuestions, result.getSubQuestions());
        assertEquals(1, requestCount.get());
        JsonObject body = capturedBody.get();
        assertEquals(512, body.get("max_tokens").getAsInt());
        assertFalse(body.get("stream").getAsBoolean());
        assertEquals("disabled", body.getAsJsonObject("thinking").get("type").getAsString());
        assertFalse(body.has("enable_thinking"));
        JsonArray messages = body.getAsJsonArray("messages");
        assertEquals(2, messages.size());
        assertEquals(input, messages.get(1).getAsJsonObject().get("content").getAsString());
    }

    @Test
    void plainOutputWithJsonExampleAndFenceDoesNotTriggerBlankJsonMode() {
        String input = "SL-A100基础版屏幕显示E07提示时，应该先检查哪里？";
        blankWhenJsonMode = true;
        modelOutput.set("改写结果：\n```json\n" + modelResult(input, List.of(input)) + "\n```");

        RewriteResultDTO result = rewrite.rewrite(input, List.of());

        assertFalse(result.isFallback());
        assertFalse(result.isBackupUsed());
        assertEquals(input, result.getRewrittenQuestion());
        assertEquals(List.of(input), result.getSubQuestions());
        assertEquals(1, requestCount.get());
        assertFalse(capturedBody.get().has("response_format"));
    }

    @Test
    void extractsCompleteObjectFromFenceOrExplanation() {
        String input = "查看申请进度";
        String json = modelResult(input, List.of(input));
        for (String output : List.of("```json\n" + json + "\n```", "改写结果如下：\n" + json + "\n以上为完整结果。")) {
            modelOutput.set(output);
            RewriteResultDTO result = rewrite.rewrite(input, List.of());
            assertFalse(result.isFallback());
            assertEquals(input, result.getRewrittenQuestion());
            assertEquals(List.of(input), result.getSubQuestions());
        }
    }

    @Test
    void multipleObjectsStillFallBackInsteadOfTakingOneSilently() {
        String input = "查看申请进度";
        String json = modelResult(input, List.of(input));
        modelOutput.set(json + "\n" + json);
        RewriteResultDTO result = rewrite.rewrite(input, List.of());
        assertTrue(result.isFallback());
        assertEquals(input, result.getRewrittenQuestion());
    }

    @Test
    void nullHistoryStillCallsModel() {
        String input = "你好";
        modelOutput.set(modelResult(input, List.of(input)));

        RewriteResultDTO result = rewrite.rewrite(input, null);

        assertEquals(input, result.getRewrittenQuestion());
        assertEquals(List.of(input), result.getSubQuestions());
        assertEquals(1, requestCount.get());
        assertEquals(2, capturedBody.get().getAsJsonArray("messages").size());
    }

    @Test
    void sendsOnlyLatestTwoRoundsAndPreservesOriginalInput() {
        String input = "那已有的预约还能签到吗？";
        String rewritten = "信用分低于60分时，已有的会议室预约还能签到吗？";
        modelOutput.set(modelResult(rewritten, List.of(rewritten)));
        List<SearchMemoryDTO> history = List.of(
                memory("user", "不应发送的旧问题"), memory("assistant", "不应发送的旧回答"),
                memory("user", "信用分低于60分时，还能预约会议室吗？"),
                memory("assistant", "不能自行创建新预约"),
                memory("user", "之前预约的还能查看吗？"), memory("assistant", "可以查看"));

        RewriteResultDTO result = rewrite.rewrite(input, history);

        assertEquals(rewritten, result.getRewrittenQuestion());
        assertEquals(List.of(rewritten), result.getSubQuestions());
        JsonArray messages = capturedBody.get().getAsJsonArray("messages");
        assertEquals(6, messages.size());
        assertFalse(messages.toString().contains("不应发送"));
        assertEquals(input, messages.get(5).getAsJsonObject().get("content").getAsString());
        assertEquals(6, history.size());
    }

    @Test
    void acceptsOneComparisonWithoutQuestionMark() {
        String input = "我想比较同步解析和消息队列异步解析的维护成本";
        modelOutput.set(modelResult(input, List.of(input)));

        RewriteResultDTO result = rewrite.rewrite(input, oneRound());

        assertEquals(input, result.getRewrittenQuestion());
        assertEquals(List.of(input), result.getSubQuestions());
    }

    @Test
    void singleItemUsesCompleteRewriteAndTrimsText() {
        modelOutput.set(modelResult("  介绍会议室预约规则  ", List.of("介绍预约规则")));

        RewriteResultDTO result = rewrite.rewrite("介绍预约规则", oneRound());

        assertEquals("介绍会议室预约规则", result.getRewrittenQuestion());
        assertEquals(List.of("介绍会议室预约规则"), result.getSubQuestions());
    }

    @Test
    void malformedOrIncompleteOutputFallsBackToOriginalAsOneItem() {
        String input = "我觉得这样安排更合理";
        List<String> invalidOutputs = List.of(
                "  ",
                "不按协议返回的普通文本",
                "{",
                "null",
                "{\"rewrittenQuestion\":\"改写文本\"}",
                "{\"rewrittenQuestion\":\"改写文本\",\"subQuestions\":[]}",
                "{\"rewrittenQuestion\":\" \",\"subQuestions\":[\"问题\"]}",
                "{\"rewrittenQuestion\":\"改写文本\",\"subQuestions\":[\" \"]}");

        for (String output : invalidOutputs) {
            modelOutput.set(output);
            RewriteResultDTO result = rewrite.rewrite(input, oneRound());
            assertEquals(input, result.getRewrittenQuestion());
            assertEquals(List.of(input), result.getSubQuestions());
        }
    }

    @Test
    void truncatedOutputFallsBackEvenWhenJsonLooksComplete() {
        String input = "会议室预约怎么取消？差旅报销需要什么材料？";
        modelOutput.set(modelResult("只剩第一个问题", List.of("只剩第一个问题")));
        finishReason.set("length");

        RewriteResultDTO result = rewrite.rewrite(input, List.of());

        assertEquals(input, result.getRewrittenQuestion());
        assertEquals(List.of(input), result.getSubQuestions());
    }

    @Test
    void upstreamHttpErrorFallsBackToOriginal() {
        String input = "它怎么取消？";
        modelOutput.set(modelResult("不应采用的结果", List.of("不应采用的结果")));
        responseStatus.set(503);

        RewriteResultDTO result = rewrite.rewrite(input, oneRound());

        assertEquals(input, result.getRewrittenQuestion());
        assertEquals(List.of(input), result.getSubQuestions());
    }

    @Test
    void successfulPrimaryDoesNotCallEnabledBackup() {
        enableBackup();
        String input = "你好";
        modelOutput.set(modelResult(input, List.of(input)));

        RewriteResultDTO result = rewrite.rewrite(input, List.of());

        assertEquals(1, requestCount.get());
        assertFalse(result.isFallback());
        assertFalse(result.isBackupUsed());
        assertEquals("test-model", result.getModel());
        assertEquals(capturedBody.get().get("model").getAsString(), result.getModel());
        assertEquals("rewrite.test", capturedRequests.get(0).url().host());
        assertNull(result.getPrimaryErrorType());
    }

    @Test
    void invalidPrimaryUsesBackupWithSameHistoryAndCorrectProviderParameters() {
        enableBackup();
        String input = "那已有的预约呢？";
        String complete = "信用分低于60分时，已有的会议室预约还能查看吗？";
        backupOutput.set(modelResult(complete, List.of(complete)));
        List<String> invalid = List.of("  ", "{bad JSON}",
                "{\"rewrittenQuestion\":\"缺少子问题\"}",
                "{\"rewrittenQuestion\":\"空子问题\",\"subQuestions\":[]}");
        for (String content : invalid) {
            modelOutput.set(content);
            int before = requestCount.get();
            RewriteResultDTO result = rewrite.rewrite(input, oneRound());

            assertEquals(before + 2, requestCount.get());
            assertFalse(result.isFallback());
            assertTrue(result.isBackupUsed());
            assertEquals(complete, result.getRewrittenQuestion());
            assertEquals(List.of(complete), result.getSubQuestions());
            assertEquals("zai-org/GLM-5.3-Flash", result.getModel());
            assertNotNull(result.getPrimaryErrorType());
            assertNotNull(result.getPrimaryErrorMessage());
            JsonObject primary = capturedBodies.get(before);
            JsonObject backup = capturedBodies.get(before + 1);
            assertEquals(primary.get("messages"), backup.get("messages"));
            assertEquals("zai-org/GLM-5.3-Flash", backup.get("model").getAsString());
            assertFalse(primary.has("response_format"));
            assertFalse(backup.has("response_format"));
            assertFalse(backup.get("enable_thinking").getAsBoolean());
            assertFalse(backup.has("thinking"));
            assertEquals("http://backup.test/v1/chat/completions", capturedRequests.get(before + 1).url().toString());
            assertEquals("Bearer fake-backup-key", capturedRequests.get(before + 1).header("Authorization"));
            assertEquals("Bearer fake-key-for-local-test", capturedRequests.get(before).header("Authorization"));
        }
    }

    @Test
    void truncatedPrimaryUsesBackupRatherThanLosingPartOfTheQuestion() {
        enableBackup();
        String input = "会议室怎么预约，差旅报销要什么材料？";
        modelOutput.set(modelResult("会议室怎么预约？", List.of("会议室怎么预约？")));
        finishReason.set("length");
        backupOutput.set(modelResult(input, List.of("会议室怎么预约？", "差旅报销要什么材料？")));

        RewriteResultDTO result = rewrite.rewrite(input, List.of());

        assertEquals(2, requestCount.get());
        assertFalse(result.isFallback());
        assertTrue(result.isBackupUsed());
        assertEquals(2, result.getSubQuestions().size());
        assertTrue(result.getPrimaryErrorMessage().contains("length"));
    }

    @Test
    void bothModelsFailOnlyThenUseOriginalAndDoNotRetryAgain() {
        enableBackup();
        String input = "这个提示该先检查哪里？";
        modelOutput.set("     ");
        backupOutput.set("{bad JSON}");

        RewriteResultDTO result = rewrite.rewrite(input, oneRound());

        assertEquals(2, requestCount.get());
        assertTrue(result.isFallback());
        assertTrue(result.isBackupUsed());
        assertEquals(input, result.getRewrittenQuestion());
        assertEquals(List.of(input), result.getSubQuestions());
        assertEquals("改写模型返回空正文", result.getPrimaryErrorMessage());
        assertEquals("JsonSyntaxException", result.getErrorType());
    }

    @Test
    void upstreamHttpFailureAlsoUsesTheSingleBackupAttempt() {
        enableBackup();
        String input = "怎么取消预约？";
        modelOutput.set("");
        responseStatus.set(503);
        backupOutput.set(modelResult(input, List.of(input)));

        RewriteResultDTO result = rewrite.rewrite(input, List.of());

        assertEquals(2, requestCount.get());
        assertFalse(result.isFallback());
        assertTrue(result.isBackupUsed());
        assertEquals("IOException", result.getPrimaryErrorType());
    }

    private void enableBackup() {
        ReflectionTestUtils.setField(rewrite, "backupModel", "zai-org/GLM-5.3-Flash");
    }

    private String modelResult(String rewrittenQuestion, List<String> subQuestions) {
        return gson.toJson(new RewriteResultDTO()
                .setRewrittenQuestion(rewrittenQuestion)
                .setSubQuestions(subQuestions));
    }

    private List<SearchMemoryDTO> oneRound() {
        return List.of(memory("user", "信用分低于60分时，还能预约会议室吗？"),
                memory("assistant", "不能自行创建新预约；已有预约仍可查看和签到。"));
    }

    private SearchMemoryDTO memory(String role, String content) {
        return new SearchMemoryDTO().setRole(role).setContent(content);
    }
}

