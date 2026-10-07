package com.lhsg.HuiAnswer.rag.chat.controller;

import com.lhsg.HuiAnswer.framework.duplicate.NoDuplicateSubmit;
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

    @PostMapping("/hui-ansWer/chat")
    @NoDuplicateSubmit
    public String chat(@RequestBody QuestionReq req) {
        return chatService.chat(req);
    }
}
