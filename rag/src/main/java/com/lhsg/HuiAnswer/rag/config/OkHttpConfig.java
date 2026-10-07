package com.lhsg.HuiAnswer.rag.config;

import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/** 全局共享的 HTTP 客户端。连接池与调度线程池随 bean 复用，不要每次调用 new 一个。 */
@Configuration
public class OkHttpConfig {

    /** TCP/TLS 握手的最长时间。readTimeout 设为 0 后，它是唯一能兜住「连不上的主机」的配置。 */
    private static final long CONNECT_TIMEOUT_SECONDS = 10;

    @Bean
    public OkHttpClient okHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                // 0 表示读不设上限。流式输出下两次数据之间可能间隔很久，
                // 任何固定的读超时都会在模型生成到一半时掐断连接。
                .readTimeout(0, TimeUnit.SECONDS)
                // 请求体只有几 KB，正常瞬间写完。整体耗时上限交由调用方用 call.timeout() 控制，
                // 这样同一条连接既能跑「一问一答」也能跑流式。
                .writeTimeout(0, TimeUnit.SECONDS)
                // 不跟随重定向：Authorization 头会随重定向一起发往新主机，有泄露 API key 的风险。
                .followRedirects(false)
                .followSslRedirects(false)
                // OkHttp 默认在连接失败时静默重试。模型调用是非幂等 POST，
                // 静默重试会导致重复计费、重复生成，重试一律由业务层显式控制。
                .retryOnConnectionFailure(false)
                .build();
    }
}
