package com.lhsg.HuiAnswer.rag.knowledge.dto.resp;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/** 管理页面查看的片段，不返回向量数组和分词中间结果。 */
@Data
@Accessors(chain = true)
public class KnowledgeChunkRespDTO {
    private Long id;
    private Long kbId;
    private Long docId;
    private Integer chunkIndex;
    private String content;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
