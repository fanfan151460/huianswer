package com.lhsg.HuiAnswer.rag.assessment.resp;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class AssessChunk {
    /** 资料来源 */
    private String docName;
    private long docId;

    /** 检索片段文本 */
    private String content;

    /** 片段id */
    private long chunkId;

    /** 检索分数 */
    private Double score;

    /** 运行时间 */
    private long time;
}
