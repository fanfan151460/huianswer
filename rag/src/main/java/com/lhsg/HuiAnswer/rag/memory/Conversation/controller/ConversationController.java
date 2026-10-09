package com.lhsg.HuiAnswer.rag.memory.Conversation.controller;

import com.lhsg.HuiAnswer.framework.result.Result;
import com.lhsg.HuiAnswer.framework.result.Results;
import com.lhsg.HuiAnswer.rag.memory.Conversation.service.IConversationService;
import com.lhsg.HuiAnswer.rag.memory.dto.req.AddConversationReq;
import com.lhsg.HuiAnswer.rag.memory.dto.resp.AddConversationResp;
import com.lhsg.HuiAnswer.rag.memory.dto.resp.ListConversationResp;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping
@RequiredArgsConstructor
public class ConversationController {

    private final IConversationService conversationService;

    @GetMapping("/api/hui-answer/conversation/{kbId}")
    public Result<List<ListConversationResp>> listConversation(@PathVariable("kbId") long kbId) {
        List<ListConversationResp> conversations = conversationService.listConversation(kbId);
        return Results.success(conversations);
    }

    @PostMapping("/api/hui-answer/add-conversation")
    public Result<AddConversationResp> addConversation(@RequestBody AddConversationReq addConversationReq) {
        AddConversationResp addConversationResp = conversationService.addConversation(addConversationReq);
        return Results.success(addConversationResp);
    }
}
