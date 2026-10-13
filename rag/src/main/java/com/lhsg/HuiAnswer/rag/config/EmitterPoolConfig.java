package com.lhsg.HuiAnswer.rag.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 问答相关的异步线程池
 */
@Configuration
public class EmitterPoolConfig {

    // 流式输出上下文
    @Bean(destroyMethod = "shutdown", name = "chatExecutor")
    public ThreadPoolExecutor chatExecutor() {
        return new ThreadPoolExecutor(
                40,
                80,
                60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100),
                // 满线程让控制层线程去跑，降级流量
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    // 独立执行完整子问题任务，避免聊天任务等待同一个池中的子任务。
    @Bean(destroyMethod = "shutdown", name = "subQuestionExecutor")
    public ThreadPoolExecutor subQuestionExecutor() {
        return new ThreadPoolExecutor(
                40,
                80,
                60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    // 压缩上下文线程池
    @Bean(destroyMethod = "shutdown", name = "memorySummaryExecutor")
    public ThreadPoolExecutor memorySummaryExecutor () {
        return new ThreadPoolExecutor(
                40,
                80,
                60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100),
                // 线程池满压缩抛异常，否则可能控制层跑全程了，而且下次回答一样会触发压缩
                new ThreadPoolExecutor.AbortPolicy()
        );
    }
}
