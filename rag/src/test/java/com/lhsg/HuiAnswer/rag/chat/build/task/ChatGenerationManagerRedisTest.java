package com.lhsg.HuiAnswer.rag.chat.build.task;

import com.google.gson.Gson;
import com.lhsg.HuiAnswer.framework.exception.ClientException;
import com.lhsg.HuiAnswer.framework.exception.ServiceException;
import com.sun.net.httpserver.HttpServer;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.redisson.config.Config;
import org.redisson.spring.data.connection.RedissonConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 两个独立任务管理实例共享临时 Redis，不连接业务 Redis，也不调用第三方模型。 */
@EnabledIfEnvironmentVariable(named = "HUIANSWER_STOP_TEST_REDIS_PORT", matches = "16479")
class ChatGenerationManagerRedisTest {
    private static RedissonConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;
    private final List<ChatGenerationTask> created = new ArrayList<>();
    private ChatGenerationManager instanceA;
    private ChatGenerationManager instanceB;

    @BeforeAll
    static void connectRedis() throws Exception {
        Config config = new Config();
        config.setThreads(1).setNettyThreads(2);
        config.useSingleServer().setAddress("redis://127.0.0.1:16479")
                .setConnectionMinimumIdleSize(1).setConnectionPoolSize(4).setRetryAttempts(0);
        connectionFactory = new RedissonConnectionFactory(config);
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void closeRedis() throws Exception {
        connectionFactory.destroy();
    }

    @BeforeEach
    void createInstances() {
        instanceA = new ChatGenerationManager(redis, new Gson());
        instanceB = new ChatGenerationManager(redis, new Gson());
    }

    @AfterEach
    void cleanOnlyOwnedKeys() {
        created.forEach(instanceA::finish);
    }

    @Test
    void remoteStopValidatesOwnerAndOnlyCancelsTheSelectedGeneration() {
        ChatGenerationTask selected = register();
        ChatGenerationTask other = register();
        Call selectedCall = mock(Call.class);
        Call otherCall = mock(Call.class);
        selected.bindCall(selectedCall);
        other.bindCall(otherCall);

        assertThrows(ClientException.class, () ->
                instanceB.requestStop(10L, 1L, selected.getGenerationId()));
        assertThrows(ClientException.class, () ->
                instanceB.requestStop(9L, 2L, selected.getGenerationId()));
        instanceA.checkRemoteStops();
        verify(selectedCall, never()).cancel();

        instanceB.requestStop(9L, 1L, selected.getGenerationId());
        assertFalse(selected.getStopped().get(), "B 不能直接操作 A 的本地对象");
        instanceA.checkRemoteStops();

        assertTrue(selected.getStopped().get());
        verify(selectedCall).cancel();
        verify(otherCall, never()).cancel();
        assertFalse(other.getStopped().get());
    }

    @Test
    void earlyRemoteStopIsRememberedAndFinishingRemovesBothKeys() {
        ChatGenerationTask task = register();
        String infoKey = "huiAnswer:chat:generation:" + task.getGenerationId();
        String stopKey = "huiAnswer:chat:stop:" + task.getGenerationId();
        assertTrue(redis.getExpire(infoKey) > 0);

        instanceB.requestStop(9L, 1L, task.getGenerationId());
        assertThrows(ServiceException.class, () -> instanceA.checkStopped(task));
        Call call = mock(Call.class);
        assertThrows(ServiceException.class, () -> task.bindCall(call));
        verify(call).cancel();

        instanceA.finish(task);
        assertFalse(Boolean.TRUE.equals(redis.hasKey(infoKey)));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(stopKey)));
        assertDoesNotThrow(() -> instanceB.requestStop(9L, 1L, task.getGenerationId()));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(stopKey)));
    }

    @Test
    void remoteStopInterruptsARealBlockedModelStreamRead() throws Exception {
        CountDownLatch releaseServer = new CountDownLatch(1);
        CountDownLatch waitingForNextLine = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/stream", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write("data: first\n\n".getBytes(StandardCharsets.UTF_8));
                output.flush();
                releaseServer.await(15, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        ExecutorService reader = Executors.newSingleThreadExecutor();
        OkHttpClient http = new OkHttpClient.Builder().readTimeout(0, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false).build();
        ChatGenerationTask task = register();
        Call call = http.newCall(new Request.Builder()
                .url("http://127.0.0.1:" + server.getAddress().getPort() + "/stream").build());
        task.bindCall(call);
        try {
            Future<Boolean> readResult = reader.submit(() -> {
                try (Response response = call.execute()) {
                    var source = response.body().source();
                    assertEquals("data: first", source.readUtf8Line());
                    assertEquals("", source.readUtf8Line());
                    waitingForNextLine.countDown();
                    source.readUtf8Line(); // 服务端不再发送数据，持续阻塞。
                    return false;
                } catch (IOException e) {
                    return call.isCanceled();
                }
            });
            assertTrue(waitingForNextLine.await(5, TimeUnit.SECONDS));
            assertFalse(readResult.isDone());

            instanceB.requestStop(9L, 1L, task.getGenerationId());
            instanceA.checkRemoteStops();

            assertTrue(readResult.get(3, TimeUnit.SECONDS));
            assertTrue(call.isCanceled());
        } finally {
            releaseServer.countDown();
            call.cancel();
            server.stop(0);
            reader.shutdownNow();
            http.connectionPool().evictAll();
            http.dispatcher().executorService().shutdownNow();
        }
    }

    private ChatGenerationTask register() {
        ChatGenerationTask task = instanceA.register(9L, 1L, mock(SseEmitter.class));
        created.add(task);
        return task;
    }
}