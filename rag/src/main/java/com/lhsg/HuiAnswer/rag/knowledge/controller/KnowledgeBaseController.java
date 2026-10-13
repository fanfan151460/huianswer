package com.lhsg.HuiAnswer.rag.knowledge.controller;

import com.lhsg.HuiAnswer.framework.result.Result;
import com.lhsg.HuiAnswer.framework.result.Results;
import com.lhsg.HuiAnswer.rag.knowledge.dto.req.CreateBaseReqDTO;
import com.lhsg.HuiAnswer.rag.knowledge.dto.req.UpdateBaseReqDTO;
import com.lhsg.HuiAnswer.rag.knowledge.dto.resp.KnowledgeBaseRespDTO;
import com.lhsg.HuiAnswer.rag.knowledge.service.IKnowledgeBaseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 知识库相关信息操作。 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/knowledge-base")
public class KnowledgeBaseController {
    private final IKnowledgeBaseService knowledgeBaseService;

    /** 创建知识库；@RequestBody 读取 JSON，@Valid 执行 DTO 上的参数校验。 */
    @PostMapping
    public Result<KnowledgeBaseRespDTO> createBase(@Valid @RequestBody CreateBaseReqDTO createBaseReqDTO) {
        return Results.success(knowledgeBaseService.createBase(createBaseReqDTO));
    }

    /** 第一版直接列出知识库，按新建顺序倒序返回。 */
    @GetMapping
    public Result<List<KnowledgeBaseRespDTO>> listBase() {
        return Results.success(knowledgeBaseService.listBase());
    }

    @GetMapping("/{id}")
    public Result<KnowledgeBaseRespDTO> getBase(@PathVariable("id") Long id) {
        return Results.success(knowledgeBaseService.getBase(id));
    }

    /** 修改知识库名称和业务描述。 */
    @PutMapping("/{id}")
    public Result<KnowledgeBaseRespDTO> updateBase(@PathVariable("id") Long id,
                                                   @Valid @RequestBody UpdateBaseReqDTO updateBaseReqDTO) {
        return Results.success(knowledgeBaseService.updateBase(id, updateBaseReqDTO));
    }

    /** 删除知识库（高危操作，需要对用户必要的提示） */
    @DeleteMapping({"/{id}", "/delete/{id}"})
    public Result<Void> deleteBase(@PathVariable("id") Long id) {
        knowledgeBaseService.deleteBase(id);
        return Results.success();
    }
}
