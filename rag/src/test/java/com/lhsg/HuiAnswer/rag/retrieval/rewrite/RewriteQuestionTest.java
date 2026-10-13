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

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RewriteQuestionTest {
    private final Gson gson = new Gson();
    private final AtomicReference<String> modelOutput = new AtomicReference<>();
    private final AtomicReference<String> finishReason = new AtomicReference<>("stop");
    private final AtomicReference<JsonObject> capturedBody = new AtomicReference<>();
    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicInteger responseStatus = new AtomicInteger(200);
    private RewriteQuestion rewrite;

    @BeforeEach
    void setUp() {
        // 在内存中返回模型响应，不访问外部接口，也不使用真实密钥。
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            requestCount.incrementAndGet();
            Buffer buffer = new Buffer();
            chain.request().body().writeTo(buffer);
            capturedBody.set(gson.fromJson(buffer.readUtf8(), JsonObject.class));

            JsonObject message = new JsonObject();
            message.addProperty("content", modelOutput.get());
            JsonObject choice = new JsonObject();
            choice.addProperty("finish_reason", finishReason.get());
            choice.add("message", message);
            JsonArray choices = new JsonArray();
            choices.add(choice);
            JsonObject body = new JsonObject();
            body.add("choices", choices);
            return new Response.Builder()
                    .request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(responseStatus.get()).message("test-response")
                    .body(ResponseBody.create(body.toString(), MediaType.get("application/json")))
                    .build();
        }).build();
        ApiUrl apiUrl = new ApiUrl();
        apiUrl.setUrl("http://rewrite.test");
        apiUrl.setChatPath("/v1/chat/completions");
        rewrite = new RewriteQuestion(client, gson, apiUrl);
        ReflectionTestUtils.setField(rewrite, "apiKey", "fake-key-for-local-test");
        ReflectionTestUtils.setField(rewrite, "chatModel", "test-model");
        ReflectionTestUtils.setField(rewrite, "maxTokens", 512);
        ReflectionTestUtils.setField(rewrite, "temperature", 0.1);
        ReflectionTestUtils.setField(rewrite, "timeoutSeconds", 10);
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
        JsonArray messages = body.getAsJsonArray("messages");
        assertEquals(2, messages.size());
        assertEquals(input, messages.get(1).getAsJsonObject().get("content").getAsString());
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

