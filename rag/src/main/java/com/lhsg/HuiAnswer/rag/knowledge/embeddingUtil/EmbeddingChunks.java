package com.lhsg.HuiAnswer.rag.knowledge.embeddingUtil;

import com.lhsg.HuiAnswer.framework.exception.ServiceException;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Setter
@Component
@ConfigurationProperties(prefix = "rag.ai.siliconflow")
public class EmbeddingChunks {
    private String url;
    private String apiKey;
    private String chatModel;
    private String embeddingModel;

    // TODO 向量化
    public List<float[]> doEmbedding(List<String> chunks) {
        throw new ServiceException("向量化尚未接入，当前只完成文本分块");
    }
}
