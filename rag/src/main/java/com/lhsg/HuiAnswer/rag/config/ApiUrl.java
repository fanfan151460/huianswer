package com.lhsg.HuiAnswer.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 模型服务地址：host 由 rag.ai.siliconflow.url 提供，端点路径由同节点的 *-path 提供。
 * 三个端点都走 OpenAI 兼容协议，换供应商时通常只需要改 yml 里的 url。
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.ai.siliconflow")
public class ApiUrl {
    /** 服务地址，只有 host，不含路径 */
    private String url;

    /** 对话补全端点路径 */
    private String chatPath;

    /** 向量端点路径 */
    private String embeddingsPath;

    /** 重排序端点路径 */
    private String rerankPath;

    /** 对话补全完整地址：回答、问题改写、摘要压缩共用。 */
    public String chatCompletionsUrl() {
        return endpoint(chatPath);
    }

    /** 向量完整地址 */
    public String embeddingsUrl() {
        return endpoint(embeddingsPath);
    }

    /** 重排序完整地址 */
    public String rerankUrl() {
        return endpoint(rerankPath);
    }

    /** 去掉 host 的尾斜杠再拼路径，避免配置多打一个 / 后拼出双斜杠。 */
    private String endpoint(String path) {
        String host = url == null ? "" : url.replaceAll("/+$", "");
        return host + (path == null ? "" : path);
    }
}
