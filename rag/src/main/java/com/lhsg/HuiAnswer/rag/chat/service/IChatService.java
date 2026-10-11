package com.lhsg.HuiAnswer.rag.chat.service;

import com.lhsg.HuiAnswer.rag.chat.dto.NeedChunksResp;
import com.lhsg.HuiAnswer.rag.chat.dto.QuestionReq;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

public interface IChatService {

    void chat(QuestionReq req, SseEmitter emitter);

    /** userId 在请求线程取得，再显式传给生成任务；startNanos 由 chat() 记录，用于统计首字延迟。 */
    List<NeedChunksResp> chatStream(QuestionReq req, SseEmitter emitter, Long userId, long startNanos);
}
