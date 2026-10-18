package com.lhsg.HuiAnswer.rag.knowledge.mq.base;

import com.lhsg.HuiAnswer.rag.knowledge.enums.SplitStrategy;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/** 消息传文档归属、ID 和分块策略，消费者从 filePath 读取原文件。 */
@Data
@Accessors(chain = true)
public class DocumentMsg implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private Long kbId;

    private Long docId;

    /** 分块策略 */
    private SplitStrategy strategy = SplitStrategy.OVERLAP;
}
