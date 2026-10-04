package com.lhsg.HuiAnswer.rag.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lhsg.HuiAnswer.framework.exception.ClientException;
import com.lhsg.HuiAnswer.framework.exception.ServiceException;
import com.lhsg.HuiAnswer.rag.knowledge.embeddingUtil.EmbeddingChunks;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/** 本地 HTTP 模拟服务验证真实请求/解析流程，使用假 key，不消耗模型额度。 */
class EmbeddingChunksTest {
    private final ObjectMapper json = new ObjectMapper();
    private final BlockingQueue<RecordedRequest> requests = new LinkedBlockingQueue<>();
    private HttpServer server;
    private ExecutorService executor;
    private EmbeddingChunks client;
    private volatile Function<JsonNode, Reply> responder;

    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        responder = input -> new Reply(200, orderedResponse(input).toString());
        server.createContext("/v1/embeddings", exchange -> {
            try {
                JsonNode body = json.readTree(exchange.getRequestBody());
                requests.add(new RecordedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                        exchange.getRequestHeaders().getFirst("Authorization"),
                        exchange.getRequestHeaders().getFirst("Content-Type"), body));
                Reply reply = responder.apply(body);
                byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                if (reply.status() == 302) {
                    exchange.getResponseHeaders().set("Location", "/v1/embeddings");
                }
                exchange.sendResponseHeaders(reply.status(), bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally {
                exchange.close();
            }
        });
        server.start();
        client = new EmbeddingChunks();
        client.setUrl("http://127.0.0.1:" + server.getAddress().getPort());
        client.setApiKey("test-embedding-key");
        client.setEmbeddingModel("Qwen/Qwen3-Embedding-8B");
    }

    @AfterEach
    void teardown() {
        server.stop(0);
        executor.shutdownNow();
    }

    @Test
    void shouldSendConfiguredRequestAndRestoreIndexOrder() throws Exception {
        List<float[]> vectors = client.doEmbedding(List.of("片段10", "片段20"));
        assertEquals(2, vectors.size());
        assertEquals(10f, vectors.get(0)[0]);
        assertEquals(20f, vectors.get(1)[0]);
        assertEquals(4096, vectors.get(0).length);
        RecordedRequest request = requests.poll(2, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("POST", request.method());
        assertEquals("/v1/embeddings", request.path());
        assertEquals("Bearer test-embedding-key", request.authorization());
        assertEquals("application/json; charset=utf-8", request.contentType());
        assertEquals("Qwen/Qwen3-Embedding-8B", request.body().get("model").asText());
        assertEquals("float", request.body().get("encoding_format").asText());
        assertEquals(4096, request.body().get("dimensions").asInt());
        assertEquals("片段10", request.body().get("input").get(0).asText());
    }

    @Test
    void shouldCombineBatchesWithoutMixingLocalIndexes() throws Exception {
        List<String> chunks = IntStream.range(0, 33).mapToObj(i -> "片段" + i).toList();
        List<float[]> vectors = client.doEmbedding(chunks);
        assertEquals(33, vectors.size());
        for (int i = 0; i < vectors.size(); i++) {
            assertEquals((float) i, vectors.get(i)[0]);
        }
        assertEquals(32, requests.poll(2, TimeUnit.SECONDS).body().get("input").size());
        assertEquals(1, requests.poll(2, TimeUnit.SECONDS).body().get("input").size());
        assertTrue(requests.isEmpty());
    }

    @Test
    void shouldPreserveTextWithJsonSpecialCharacters() throws Exception {
        String text = "中文\"引号\"\\路径\n下一行 <tag> &";
        responder = input -> new Reply(200, "{\"data\":[{\"index\":0,\"embedding\":[0.25,0.5]}]}");
        List<float[]> vectors = client.doEmbedding(List.of(text));
        assertArrayEquals(new float[]{0.25f, 0.5f}, vectors.get(0));
        RecordedRequest request = requests.poll(2, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals(text, request.body().get("input").get(0).asText());
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 429, 503, 302})
    void shouldHandleHttpErrorsWithoutLeakingUpstreamBody(int status) {
        responder = input -> new Reply(status, "PRIVATE_UPSTREAM_DETAIL test-embedding-key");
        ServiceException failure = assertThrows(ServiceException.class, () -> client.doEmbedding(List.of("片段0")));
        assertFalse(failure.getMessage().contains("PRIVATE_UPSTREAM_DETAIL"));
        assertFalse(failure.getMessage().contains("test-embedding-key"));
        if (status == 401 || status == 403) {
            assertTrue(failure.getMessage().contains("鉴权失败"));
        } else if (status == 429) {
            assertTrue(failure.getMessage().contains("限流"));
        } else {
            assertTrue(failure.getMessage().contains(Integer.toString(status)));
        }
        assertEquals(1, requests.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"count", "duplicate", "range", "negative", "missing", "fraction", "vector"})
    void shouldRejectResponsesThatCannotBePaired(String defect) {
        responder = input -> {
            ObjectNode root = orderedResponse(input);
            ArrayNode data = (ArrayNode) root.get("data");
            ObjectNode first = (ObjectNode) data.get(0);
            switch (defect) {
                case "count" -> data.remove(0);
                case "duplicate" -> first.put("index", 0);
                case "range" -> first.put("index", 2);
                case "negative" -> first.put("index", -1);
                case "missing" -> first.remove("index");
                case "fraction" -> first.put("index", 0.5);
                case "vector" -> first.remove("embedding");
                default -> throw new AssertionError();
            }
            return new Reply(200, root.toString());
        };
        assertThrows(ServiceException.class, () -> client.doEmbedding(List.of("片段10", "片段20")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PRIVATE_UPSTREAM_DETAIL test-embedding-key", "null", "{\"error\":\"PRIVATE_UPSTREAM_DETAIL\"}"})
    void shouldRejectInvalidJsonAndProviderErrorsSafely(String body) {
        responder = input -> new Reply(200, body);
        ServiceException failure = assertThrows(ServiceException.class, () -> client.doEmbedding(List.of("片段0")));
        assertFalse(failure.getMessage().contains("PRIVATE_UPSTREAM_DETAIL"));
        assertFalse(failure.getMessage().contains("test-embedding-key"));
        assertNull(failure.getCause());
    }

    @Test
    void shouldFailEntireCallWhenLaterBatchFails() {
        client.setEmbeddingBatchSize(1);
        responder = input -> input.get("input").get(0).asText().equals("片段0")
                ? new Reply(200, orderedResponse(input).toString()) : new Reply(503, "unavailable");
        assertThrows(ServiceException.class, () -> client.doEmbedding(List.of("片段0", "片段1", "片段2")));
        assertEquals(2, requests.size());
    }

    @Test
    void shouldTimeoutSlowResponse() {
        client.setEmbeddingTimeoutSeconds(1);
        CountDownLatch release = new CountDownLatch(1);
        responder = input -> {
            try {
                release.await(4, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new Reply(200, orderedResponse(input).toString());
        };
        try {
            ServiceException failure = assertThrows(ServiceException.class, () -> client.doEmbedding(List.of("片段0")));
            assertTrue(failure.getMessage().contains("超时"));
        } finally {
            release.countDown();
        }
    }

    @Test
    void shouldRestoreInterruptFlag() {
        Thread.currentThread().interrupt();
        try {
            ServiceException failure = assertThrows(ServiceException.class, () -> client.doEmbedding(List.of("片段0")));
            assertTrue(failure.getMessage().contains("中断"));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void shouldRejectMissingConfigurationBeforeSending() {
        client.setApiKey("");
        assertThrows(ServiceException.class, () -> client.doEmbedding(List.of("片段0")));
        client.setApiKey("test-embedding-key");
        client.setEmbeddingBatchSize(0);
        assertThrows(ServiceException.class, () -> client.doEmbedding(List.of("片段0")));
        client.setEmbeddingBatchSize(32);
        client.setUrl("not-a-url");
        assertThrows(ServiceException.class, () -> client.doEmbedding(List.of("片段0")));
        assertTrue(requests.isEmpty());
    }

    @Test
    void shouldNotExposeInvalidApiKeyFromHeaderBuilder() {
        client.setApiKey("test-embedding-key\ninvalid");
        ServiceException failure = assertThrows(ServiceException.class, () -> client.doEmbedding(List.of("片段0")));
        assertFalse(failure.getMessage().contains("test-embedding-key"));
        assertNull(failure.getCause());
        assertTrue(requests.isEmpty());
    }

    @Test
    void shouldRejectEmptyTextsBeforeSending() {
        assertThrows(ClientException.class, () -> client.doEmbedding(null));
        assertThrows(ClientException.class, () -> client.doEmbedding(List.of()));
        assertThrows(ClientException.class, () -> client.doEmbedding(List.of(" ")));
        assertTrue(requests.isEmpty());
    }

    private ObjectNode orderedResponse(JsonNode request) {
        ArrayNode input = (ArrayNode) request.get("input");
        ObjectNode root = json.createObjectNode();
        ArrayNode data = root.putArray("data");
        // 故意倒序返回，确保实现真正使用 index，而非响应数组的位置。
        for (int i = input.size() - 1; i >= 0; i--) {
            float[] vector = new float[4096];
            vector[0] = Float.parseFloat(input.get(i).asText().substring(2));
            ObjectNode item = data.addObject();
            item.put("index", i);
            item.set("embedding", json.valueToTree(vector));
        }
        return root;
    }

    private record Reply(int status, String body) {}
    private record RecordedRequest(String method, String path, String authorization, String contentType, JsonNode body) {}
}
