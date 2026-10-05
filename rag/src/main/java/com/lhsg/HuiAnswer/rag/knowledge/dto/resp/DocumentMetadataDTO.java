package com.lhsg.HuiAnswer.rag.knowledge.dto.resp;

import lombok.Data;

/** 文档 metadata 中用于展示处理进度的字段。 */
@Data
public class DocumentMetadataDTO {
    private String parseStatus;
    private Integer textLength;
    private Integer chunkCount;
    private String errorMessage;
}
