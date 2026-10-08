package com.lhsg.HuiAnswer.rag.chat.controller;

import com.lhsg.HuiAnswer.framework.duplicate.NoDuplicateSubmit;
import com.lhsg.HuiAnswer.framework.monitor.TimeAnnotation;
import com.lhsg.HuiAnswer.framework.result.Result;
import com.lhsg.HuiAnswer.framework.result.Results;
import com.lhsg.HuiAnswer.rag.chat.dto.LLMChatResp;
import com.lhsg.HuiAnswer.rag.chat.dto.QuestionReq;
import com.lhsg.HuiAnswer.rag.chat.service.IChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

@RestController
@RequiredArgsConstructor
public class ChatController {
    private final IChatService chatService;
    private final ThreadPoolExecutor threadPoolExecutor;

    @TimeAnnotation(method = "问答全链路")
    @PostMapping("/hui-ansWer/chat")
    @NoDuplicateSubmit
    public Result<LLMChatResp> chat(@RequestBody QuestionReq req) {
        return Results.success(chatService.chat(req));
    }

    @PostMapping(
            value = "/hui-ansWer/chat/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public SseEmitter chatStream(@RequestBody QuestionReq req) {
        SseEmitter emitter = new SseEmitter(60_000L);
        try {
            // Controller 先返回 emitter，模型调用和发送事件由线程池继续执行。
            threadPoolExecutor.execute(() -> {
                try {
                    LLMChatResp resp = chatService.chatStream(req, emitter);
                    emitter.send(SseEmitter.event()
                            .name("done")
                            .data(resp, MediaType.APPLICATION_JSON));
                    emitter.complete();
                } catch (Exception e) {
                    sendError(emitter, "回答生成失败，请稍后再试");
                }
            });
        } catch (RejectedExecutionException e) {
            sendError(emitter, "服务繁忙，请稍后再试");
        }
        return emitter;
    }

    private void sendError(SseEmitter emitter, String message) {
        try {
            // 流已经开始后，用 error 事件通知客户端，不能再返回普通 Result。
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(Map.of("message", message), MediaType.APPLICATION_JSON));
            emitter.complete();
        } catch (Exception ignored) {
            // 客户端可能已经断开；容器会完成连接清理，不再尝试往响应里写数据。
        }
    }
}
