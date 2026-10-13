package com.lhsg.HuiAnswer.rag.memory.controller;

import com.lhsg.HuiAnswer.framework.result.Result;
import com.lhsg.HuiAnswer.framework.result.Results;
import com.lhsg.HuiAnswer.rag.memory.service.IConversationService;
import com.lhsg.HuiAnswer.rag.memory.dto.resp.ListConversationResp;
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
    public Result<List<ListConversationResp>> listConversation() {
        List<ListConversationResp> conversations = conversationService.listConversation();
        return Results.success(conversations);
    }

}
