package com.lhsg.HuiAnswer.rag.chat.service;

import com.lhsg.HuiAnswer.rag.chat.dto.QuestionReq;

public interface IChatService {

    String chat(QuestionReq req);
}
