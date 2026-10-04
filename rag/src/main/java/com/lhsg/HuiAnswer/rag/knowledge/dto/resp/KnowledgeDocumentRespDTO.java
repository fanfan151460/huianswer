package com.lhsg.HuiAnswer.rag.knowledge.dto.resp;

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
public class KnowledgeDocumentRespDTO {
    private Long id;

    private Long kbId;

    private String docName;

    /** pending 等待切分/向量化；failed 解析失败；success 留给整条导入链路完成。 */
    private String status;

    private String parseStatus;

    private Integer textLength;

    /** 已切出的有效片段数量；当前仍等待向量化，不代表已经入库。 */
    private Integer chunkCount;

    /** 上传响应返回预览；列表不返回正文，不暴露磁盘路径。 */
    private String textPreview;

    private Boolean previewTruncated;

    private String errorMessage;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
