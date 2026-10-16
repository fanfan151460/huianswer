package com.lhsg.HuiAnswer.rag.assessment.chunkSearch.resp;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

@Data
@Accessors(chain = true)
public class AssessChunkResp {
    /** 检索片段 */
    private List<AssessChunk> chunks;

    /** 检索时间 */
    private long time;
}
