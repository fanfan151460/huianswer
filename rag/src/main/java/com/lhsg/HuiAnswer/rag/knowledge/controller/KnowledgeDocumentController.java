package com.lhsg.HuiAnswer.rag.knowledge.controller;

import com.lhsg.HuiAnswer.framework.monitor.TimeAnnotation;
import com.lhsg.HuiAnswer.framework.result.Result;
import com.lhsg.HuiAnswer.framework.result.Results;
import com.lhsg.HuiAnswer.rag.knowledge.dto.resp.KnowledgeDocumentRespDTO;
import com.lhsg.HuiAnswer.rag.knowledge.service.IKnowledgeDocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** 知识库文件控制层。上传文件使用 multipart，不是 JSON 请求体。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/knowledge-base/{kbId}/documents")
public class KnowledgeDocumentController {
    private final IKnowledgeDocumentService knowledgeDocumentService;

    @TimeAnnotation(method = "上传并解析文档")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<KnowledgeDocumentRespDTO> upload(@PathVariable("kbId") Long kbId,
                                                  @RequestPart("file") MultipartFile file) {
        return Results.success(knowledgeDocumentService.uploadDocument(kbId, file));
    }

    @GetMapping("/{docId}")
    public Result<KnowledgeDocumentRespDTO> get(@PathVariable("kbId") Long kbId,
                                                @PathVariable("docId") Long docId) {
        return Results.success(knowledgeDocumentService.getDocument(kbId, docId));
    }

    @PostMapping("/{docId}/retry")
    public Result<KnowledgeDocumentRespDTO> retry(@PathVariable("kbId") Long kbId,
                                                  @PathVariable("docId") Long docId) {
        return Results.success(knowledgeDocumentService.retryDocument(kbId, docId));
    }

    @GetMapping
    public Result<List<KnowledgeDocumentRespDTO>> list(@PathVariable("kbId") Long kbId) {
        return Results.success(knowledgeDocumentService.listDocument(kbId));
    }
}
