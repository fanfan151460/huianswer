package com.lhsg.HuiAnswer.rag.chat.service;

import com.lhsg.HuiAnswer.rag.chat.dto.LLMChatResp;
import com.lhsg.HuiAnswer.rag.chat.dto.QuestionReq;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface IChatService {

    LLMChatResp chat(QuestionReq req);

    LLMChatResp chatStream(QuestionReq req, SseEmitter emitter);
}
