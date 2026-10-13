package com.lhsg.HuiAnswer.rag.chat.controller;

import com.lhsg.HuiAnswer.framework.duplicate.NoDuplicateSubmit;
import com.lhsg.HuiAnswer.rag.chat.dto.req.QuestionReq;
import com.lhsg.HuiAnswer.rag.chat.service.IChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequiredArgsConstructor
public class ChatController {
    private final IChatService chatService;

    @Value("${rag.chat.max-sse}")
    private long sseWait;

    @PostMapping(
            value = "/hui-ansWer/chat/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    @NoDuplicateSubmit
    public SseEmitter chatStream(@RequestBody QuestionReq req) {
        SseEmitter emitter = new SseEmitter(sseWait);
        chatService.chat(req, emitter);
        return emitter;
    }
}
