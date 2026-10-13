package com.lhsg.HuiAnswer.rag.knowledge.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lhsg.HuiAnswer.framework.monitor.TimeAnnotation;
import com.lhsg.HuiAnswer.framework.result.Result;
import com.lhsg.HuiAnswer.framework.result.Results;
import com.lhsg.HuiAnswer.rag.knowledge.dto.resp.KnowledgeDocumentRespDTO;
import com.lhsg.HuiAnswer.rag.knowledge.dto.req.UpdateDocumentEnabledReqDTO;
import jakarta.validation.Valid;
import com.lhsg.HuiAnswer.rag.knowledge.service.IKnowledgeDocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;


/** 知识库文件控制层。上传文件使用 multipart，不是 JSON 请求体。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/knowledge-base/{kbId}/documents")
public class KnowledgeDocumentController {
    private final IKnowledgeDocumentService knowledgeDocumentService;

    @GetMapping
    public Result<IPage<KnowledgeDocumentRespDTO>> list(@PathVariable("kbId") Long kbId,
                                                       @RequestParam(name = "current", defaultValue = "1") long current,
                                                       @RequestParam(name = "size", defaultValue = "10") long size) {
        return Results.success(knowledgeDocumentService.listDocument(kbId, current, size));
    }

    @GetMapping("/{docId}")
    public Result<KnowledgeDocumentRespDTO> get(@PathVariable("kbId") Long kbId,
                                               @PathVariable("docId") Long docId) {
        return Results.success(knowledgeDocumentService.getDocument(kbId, docId));
    }

    @PutMapping("/{docId}/enabled")
    public Result<Void> updateEnabled(@PathVariable("kbId") Long kbId,
                                      @PathVariable("docId") Long docId,
                                      @Valid @RequestBody UpdateDocumentEnabledReqDTO req) {
        knowledgeDocumentService.updateEnabled(kbId, docId, req.getEnabled());
        return Results.success();
    }

    @DeleteMapping("/{docId}")
    public Result<Void> delete(@PathVariable("kbId") Long kbId, @PathVariable("docId") Long docId) {
        knowledgeDocumentService.deleteDocument(kbId, docId);
        return Results.success();
    }

    @TimeAnnotation(method = "上传并解析文档")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<KnowledgeDocumentRespDTO> upload(@PathVariable("kbId") Long kbId,
                                                  @RequestPart("file") MultipartFile file) {
        return Results.success(knowledgeDocumentService.uploadDocument(kbId, file));
    }

    @TimeAnnotation(method = "重试解析文档")
    @PostMapping("/{docId}/retry")
    public Result<KnowledgeDocumentRespDTO> retry(@PathVariable("kbId") Long kbId,
                                                  @PathVariable("docId") Long docId) {
        return Results.success(knowledgeDocumentService.retryDocument(kbId, docId));
    }

}
