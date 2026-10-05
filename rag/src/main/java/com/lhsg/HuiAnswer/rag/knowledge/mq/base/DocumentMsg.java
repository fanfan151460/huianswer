package com.lhsg.HuiAnswer.rag.knowledge.mq.base;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/** 消息只传文档的归属和 ID，消费者从 filePath 读取原文件。 */
@Data
@Accessors(chain = true)
public class DocumentMsg implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private Long kbId;

    private Long docId;
}
