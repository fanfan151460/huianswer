package com.lhsg.HuiAnswer.rag.chat.build.task;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.lhsg.HuiAnswer.framework.exception.ClientException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import static com.lhsg.HuiAnswer.rag.constant.constant.TASK_KEY;

@Component
@RequiredArgsConstructor
@Slf4j
public class ChatGenerationManager {
    private final StringRedisTemplate redis;
    private final Gson gson;
    private final ConcurrentHashMap<String, ChatGenerationTask> tasks = new ConcurrentHashMap<>();

    @Value("${rag.chat.generation-ttl-seconds:600}")
    private long generationTtlSeconds = 600;
    @Value("${rag.chat.max-sse:300000}")
    private long maxSseMillis = 300000;

    @PostConstruct
    private void validateConfiguration() {
        if (generationTtlSeconds <= 0
                || Duration.ofSeconds(generationTtlSeconds).toMillis() <= maxSseMillis) {
            throw new IllegalArgumentException("生成任务TTL必须大于SSE连接时限");
        }
    }

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
        // 在同一个示例直接取消
        ChatGenerationTask localTask = tasks.get(id);
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

    /** 单个调度任务扫描本实例的活动生成，能取消正在阻塞读取的最终 Call。 */
    @Scheduled(fixedDelayString = "${rag.chat.stop-check-interval-ms:1000}")
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