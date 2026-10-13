package com.lhsg.HuiAnswer.rag.knowledge.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lhsg.HuiAnswer.framework.result.Result;
import com.lhsg.HuiAnswer.framework.result.Results;
import com.lhsg.HuiAnswer.rag.knowledge.dto.resp.KnowledgeChunkRespDTO;
import com.lhsg.HuiAnswer.rag.knowledge.service.IKnowledgeDocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/knowledge-base/{kbId}/documents/{docId}/chunks")
public class KnowledgeChunkController {
    private final IKnowledgeDocumentService documentService;

    @GetMapping
    public Result<IPage<KnowledgeChunkRespDTO>> page(@PathVariable("kbId") Long kbId,
                                                   @PathVariable("docId") Long docId,
                                                   @RequestParam(name = "current", defaultValue = "1") long current,
                                                   @RequestParam(name = "size", defaultValue = "10") long size) {
        return Results.success(documentService.pageChunks(kbId, docId, current, size));
    }
}
