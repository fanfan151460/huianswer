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

    @Bean(destroyMethod = "shutdown")
    public ThreadPoolExecutor threadPoolExecutor() {
        return new ThreadPoolExecutor(
                40,
                80,
                60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100),
                // 队列满时拒绝任务，Controller 发 error，避免在请求线程里跑完整问答。
                new ThreadPoolExecutor.AbortPolicy()
        );
    }
}
