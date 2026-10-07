package com.lhsg.HuiAnswer.rag.chat.service;

import com.lhsg.HuiAnswer.rag.chat.dto.LLMChatResp;
import com.lhsg.HuiAnswer.rag.chat.dto.QuestionReq;

public interface IChatService {

    LLMChatResp chat(QuestionReq req);
}
