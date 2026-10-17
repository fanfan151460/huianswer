package com.lhsg.HuiAnswer.rag.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 问答相关的异步线程池
 */
@Configuration
public class ThreadPoolConfig {

    // 默认沿用本地配置；服务器通过外置 YAML 缩小并发与队列。
    @Value("${rag.executor.chat.core-size:40}")
    private int chatCoreSize = 40;
    @Value("${rag.executor.chat.max-size:80}")
    private int chatMaxSize = 80;
    @Value("${rag.executor.chat.queue-capacity:100}")
    private int chatQueueCapacity = 100;

    @Value("${rag.executor.sub-question.core-size:40}")
    private int subQuestionCoreSize = 40;
    @Value("${rag.executor.sub-question.max-size:80}")
    private int subQuestionMaxSize = 80;
    @Value("${rag.executor.sub-question.queue-capacity:100}")
    private int subQuestionQueueCapacity = 100;

    @Value("${rag.executor.summary.core-size:40}")
    private int summaryCoreSize = 40;
    @Value("${rag.executor.summary.max-size:80}")
    private int summaryMaxSize = 80;
    @Value("${rag.executor.summary.queue-capacity:100}")
    private int summaryQueueCapacity = 100;


    // 流式输出上下文
    @Bean(destroyMethod = "shutdown", name = "chatExecutor")
    public ThreadPoolExecutor chatExecutor() {
        return new ThreadPoolExecutor(
                chatCoreSize,
                chatMaxSize,
                60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(chatQueueCapacity),
                // 满线程让控制层线程去跑，降级流量
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    // 独立执行完整子问题任务，避免聊天任务等待同一个池中的子任务。
    @Bean(destroyMethod = "shutdown", name = "subQuestionExecutor")
    public ThreadPoolExecutor subQuestionExecutor() {
        return new ThreadPoolExecutor(
                subQuestionCoreSize,
                subQuestionMaxSize,
                60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(subQuestionQueueCapacity),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    // 压缩上下文线程池
    @Bean(destroyMethod = "shutdown", name = "memorySummaryExecutor")
    public ThreadPoolExecutor memorySummaryExecutor () {
        return new ThreadPoolExecutor(
                summaryCoreSize,
                summaryMaxSize,
                60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(summaryQueueCapacity),
                // 线程池满压缩抛异常，否则可能控制层跑全程了，而且下次回答一样会触发压缩
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    // 概括标题线程池
}
