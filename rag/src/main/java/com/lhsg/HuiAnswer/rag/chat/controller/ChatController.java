package com.lhsg.HuiAnswer.rag.chat.controller;

import com.lhsg.HuiAnswer.framework.duplicate.NoDuplicateSubmit;
import com.lhsg.HuiAnswer.framework.monitor.TimeAnnotation;
import com.lhsg.HuiAnswer.framework.result.Result;
import com.lhsg.HuiAnswer.framework.result.Results;
import com.lhsg.HuiAnswer.rag.chat.dto.LLMChatResp;
import com.lhsg.HuiAnswer.rag.chat.dto.QuestionReq;
import com.lhsg.HuiAnswer.rag.chat.service.IChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ChatController {
    private final IChatService chatService;

    @TimeAnnotation(method = "问答全链路")
    @PostMapping("/hui-ansWer/chat")
    @NoDuplicateSubmit
    public Result<LLMChatResp> chat(@RequestBody QuestionReq req) {
        return Results.success(chatService.chat(req));
    }
}
