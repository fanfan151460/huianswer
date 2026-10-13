package com.lhsg.HuiAnswer.rag.memory.controller;

import com.lhsg.HuiAnswer.framework.result.Result;
import com.lhsg.HuiAnswer.framework.result.Results;
import com.lhsg.HuiAnswer.rag.memory.service.IConversationService;
import com.lhsg.HuiAnswer.rag.memory.dto.resp.ConversationResp;
import com.lhsg.HuiAnswer.rag.memory.dto.req.UpdateConversationReq;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping
@RequiredArgsConstructor
public class ConversationController {

    private final IConversationService conversationService;

    @GetMapping("/api/hui-answer/conversation")
    public Result<List<ConversationResp>> listConversation() {
        List<ConversationResp> conversations = conversationService.listConversation();
        return Results.success(conversations);
    }

    @PutMapping("/api/hui-answer/conversation/{conversationId}")
    public Result<Void> updateConversation(@PathVariable("conversationId") Long conversationId,
                                           @Valid @RequestBody UpdateConversationReq req) {
        conversationService.updateConversation(conversationId, req.getTitle());
        return Results.success();
    }

    @DeleteMapping("/api/hui-answer/conversation/{conversationId}")
    public Result<Void> deleteConversation(@PathVariable("conversationId") Long conversationId) {
        conversationService.deleteConversation(conversationId);
        return Results.success();
    }
}
