package com.lhsg.HuiAnswer.rag.config;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 对话走 DeepSeek 官网；向量化和重排序走硅基流动，分别配置地址。 */
@Data
@Component
public class ApiUrl {
    @Value("${rag.ai.deepseek.url}")
    private String chatUrl;

    @Value("${rag.ai.deepseek.chat-path}")
    private String chatPath;

    @Value("${rag.ai.siliconflow.url}")
    private String url;

    @Value("${rag.ai.siliconflow.embeddings-path}")
    private String embeddingsPath;

    @Value("${rag.ai.siliconflow.rerank-path}")
    private String rerankPath;

    /** 回答、问题改写、意图路由和摘要压缩共用。 */
    public String chatCompletionsUrl() {
        return endpoint(chatUrl, chatPath);
    }

    public String embeddingsUrl() {
        return endpoint(url, embeddingsPath);
    }

    public String rerankUrl() {
        return endpoint(url, rerankPath);
    }

    private String endpoint(String host, String path) {
        String base = host == null ? "" : host.replaceAll("/+$", "");
        return base + (path == null ? "" : path);
    }
}
