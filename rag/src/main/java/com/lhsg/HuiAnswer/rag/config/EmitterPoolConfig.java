package com.lhsg.HuiAnswer.rag.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 流式输出异步线程池
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
                new ThreadPoolExecutor.CallerRunsPolicy()
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
