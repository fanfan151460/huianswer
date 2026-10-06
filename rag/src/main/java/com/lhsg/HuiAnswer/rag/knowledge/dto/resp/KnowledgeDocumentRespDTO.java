package com.lhsg.HuiAnswer.rag.knowledge.dto.resp;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
public class KnowledgeDocumentRespDTO {
    private Long id;

    private Long kbId;

    /** 上传者的管理员 ID */
    private Long createdBy;

    private String docName;

    /** pending 排队中；running 处理中；failed 导入失败；success 全部片段已入库。 */
    private String status;

    private String parseStatus;

    private Integer textLength;

    /** 已成功入库的片段数量；导入失败时为 0。 */
    private Integer chunkCount;

    private String errorMessage;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
