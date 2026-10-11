package com.lhsg.HuiAnswer.rag.memory.Conversation.controller;

import com.lhsg.HuiAnswer.framework.result.Result;
import com.lhsg.HuiAnswer.framework.result.Results;
import com.lhsg.HuiAnswer.rag.memory.Conversation.service.IMessageService;
import com.lhsg.HuiAnswer.rag.memory.dto.resp.MessageResp;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class MessageController {

    private final IMessageService messageService;

    @GetMapping("/api/hui-answer/{conversationId}")
    public Result<List<MessageResp>> history(@PathVariable("conversationId") long conversationId) {
        List<MessageResp> list =  messageService.getHistory(conversationId, null);
        return Results.success(list);
    }
}
