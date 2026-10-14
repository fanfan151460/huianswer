package com.lhsg.HuiAnswer.rag.chat.service;

import com.lhsg.HuiAnswer.rag.chat.dto.req.QuestionReq;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface IChatService {

    /** userId 在请求线程取得，再显式传给生成任务。参考资料经 SSE 推送已取消，故无返回值。 */
    void chat(QuestionReq req, SseEmitter emitter);

    /** 提交当前用户本次生成的停止要求，可重复调用。 */
    void stop(Long conversationId, String generationId);
}
