package com.lhsg.HuiAnswer.rag.knowledge.controller;

import com.lhsg.HuiAnswer.rag.knowledge.service.IKnowledgeChunkService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文档分块控制层 TODO 文档启用和禁用
 */
@RestController
@RequiredArgsConstructor
public class KnowledgeChunkController {
    private final IKnowledgeChunkService service;
}
