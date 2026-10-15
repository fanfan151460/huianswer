package com.lhsg.HuiAnswer.rag.chat.build.task;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.lhsg.HuiAnswer.framework.exception.ClientException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.lhsg.HuiAnswer.rag.constant.constant.STOP_KEY;
import static com.lhsg.HuiAnswer.rag.constant.constant.STOP_TOPIC;
import static com.lhsg.HuiAnswer.rag.constant.constant.TASK_KEY;

@Component
@RequiredArgsConstructor
@Slf4j
public class ChatGenerationManager {
    private final StringRedisTemplate redis;
    private final Gson gson;
    private final RedissonClient redissonClient;
    private final ConcurrentHashMap<String, ChatGenerationTask> tasks = new ConcurrentHashMap<>();
    private RTopic stopTopic;
    private Integer stopListenerId;

    @Value("${rag.chat.generation-ttl-seconds:600}")
    private long generationTtlSeconds = 600;
    @Value("${rag.chat.max-sse:300000}")
    private long maxSseMillis = 300000;

    @PostConstruct
    void init() {
        if (generationTtlSeconds <= 0
                || Duration.ofSeconds(generationTtlSeconds).toMillis() <= maxSseMillis) {
            throw new IllegalArgumentException("生成任务TTL必须大于SSE连接时限");
        }
        // 每个实例订阅同一频道，只取消存在于本实例的生成任务。
        stopTopic = redissonClient.getTopic(STOP_TOPIC, StringCodec.INSTANCE);
        stopListenerId = stopTopic.addListener(String.class,
                (channel, generationId) -> cancelLocalTask(generationId));
    }

    @PreDestroy
    void removeStopListener() {
        if (stopTopic != null && stopListenerId != null) {
            try {
                // 只移除本管理器注册的监听，不影响其他订阅者。
                stopTopic.removeListener(stopListenerId);
                stopListenerId = null;
            } catch (Exception e) {
                log.warn("移除生成停止监听失败，异常类型：{}", e.getClass().getSimpleName());
            }
        }
    }

    // 注册任务
    public ChatGenerationTask register(Long userId, Long conversationId, SseEmitter emitter) {
        ChatGenerationTask task = new ChatGenerationTask()
                .setGenerationId(UUID.randomUUID().toString())
                .setUserId(userId).setConversationId(conversationId).setEmitter(emitter);
        // Redis 仅保存可跨实例查询的归属信息，不序列化 Call 或 emitter。
        JsonObject info = new JsonObject();
        info.addProperty("userId", userId);
        info.addProperty("conversationId", conversationId);
        redis.opsForValue().set(TASK_KEY.formatted(task.getGenerationId()),
                info.toString(), Duration.ofSeconds(generationTtlSeconds));
        tasks.put(task.getGenerationId(), task);
        return task;
    }


    // 设置停止标记
    public void requestStop(Long userId, Long conversationId, String generationId) {
        String id = normalizeId(generationId);
        String infoJson = redis.opsForValue().get(TASK_KEY.formatted(id));
        // 正常结束后归属记录会被移除，重复停止直接返回。
        if (infoJson == null) {
            return;
        }
        JsonObject info = gson.fromJson(infoJson, JsonObject.class);
        if (!Objects.equals(userId, info.get("userId").getAsLong())
                || !Objects.equals(conversationId, info.get("conversationId").getAsLong())) {
            throw new ClientException("生成任务不存在或无权操作");
        }
        redis.opsForValue().set(STOP_KEY.formatted(id), "1",
                Duration.ofSeconds(generationTtlSeconds));
        // 同实例立即取消；跨实例通过订阅通知取消。
        cancelLocalTask(id);
        try {
            stopTopic.publish(id);
        } catch (Exception e) {
            // 停止标记已写入，发布失败仍可由定时扫描补处理。
            log.warn("发布生成停止通知失败，generationId={}，异常类型：{}",
                    id, e.getClass().getSimpleName());
        }
    }

    private void cancelLocalTask(String generationId) {
        ChatGenerationTask localTask = tasks.get(generationId);
        if (localTask != null) {
            localTask.cancel();
        }
    }

    /** 在开始任务、调用最终模型和提交正常结果前读取一次，覆盖提前停止。 */
    public void checkStopped(ChatGenerationTask task) {
        task.checkStopped();
        if ("1".equals(redis.opsForValue().get(STOP_KEY.formatted(task.getGenerationId())))) {
            task.cancel();
        }
        task.checkStopped();
    }

    /** 低频扫描兜底：补处理发布失败或订阅断线时漏掉的停止通知。 */
    @Scheduled(fixedDelayString = "${rag.chat.stop-check-interval-ms:5000}")
    public void checkRemoteStops() {
        List<ChatGenerationTask> snapshot = new ArrayList<>(tasks.values());
        if (snapshot.isEmpty()) {
            return;
        }
        try {
            List<String> keys = snapshot.stream()
                    .map(task -> STOP_KEY.formatted(task.getGenerationId())).toList();
            List<String> flags = redis.opsForValue().multiGet(keys);
            if (flags == null) {
                return;
            }
            for (int i = 0; i < snapshot.size(); i++) {
                if ("1".equals(flags.get(i))) {
                    snapshot.get(i).cancel();
                }
            }
        } catch (Exception e) {
            // Redis 暂时不可用时不误停全部任务；仍受 SSE 和模型时限约束。
            log.warn("读取生成停止标记失败，异常类型：{}", e.getClass().getSimpleName());
        }
    }

    public void finish(ChatGenerationTask task) {
        task.finish();
        tasks.remove(task.getGenerationId(), task);
        try {
            redis.delete(List.of(TASK_KEY.formatted(task.getGenerationId()),
                    STOP_KEY.formatted(task.getGenerationId())));
        } catch (Exception e) {
            // 本地对象必须先清理；远端记录清理失败时由 TTL 回收。
            log.warn("清理生成任务失败，generationId={}，异常类型：{}",
                    task.getGenerationId(), e.getClass().getSimpleName());
        }
    }

    private String normalizeId(String generationId) {
        try {
            UUID id = UUID.fromString(generationId);
            if (!id.toString().equalsIgnoreCase(generationId)) {
                throw new IllegalArgumentException();
            }
            return id.toString();
        } catch (Exception e) {
            throw new ClientException("生成ID格式不正确");
        }
    }
}