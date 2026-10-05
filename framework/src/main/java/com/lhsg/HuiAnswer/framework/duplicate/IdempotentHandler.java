package com.lhsg.HuiAnswer.framework.duplicate;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class IdempotentHandler {
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 是否未开始消费
     */
    public Boolean consuming(String key) {
        return Boolean.TRUE.equals(stringRedisTemplate.opsForValue().setIfAbsent(key, "0", 5, TimeUnit.MINUTES));
    }

    /** 消费失败删除key */
    public void failConsume(String key) {
        stringRedisTemplate.delete(key);
    }

    /** 消费成功 */
    public void successConsume(String key) {
        stringRedisTemplate.opsForValue().set(key, "1", 10, TimeUnit.MINUTES);
    }

    /** 是否消费成功 */
    public Boolean success(String key) {
        return Objects.equals("1", stringRedisTemplate.opsForValue().get(key));
    }
}
