package com.lhsg.HuiAnswer.rag.chat.build.task;

import com.lhsg.HuiAnswer.framework.exception.ServiceException;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Setter;
import lombok.experimental.Accessors;
import okhttp3.Call;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Data
@Accessors(chain = true)
public final class ChatGenerationTask {
    /** 本次生成 ID；同一会话每次问答都会重新分配。 */
    private String generationId;
    private Long conversationId;
    private Long userId;
    private SseEmitter emitter;

    /** 已收到停止要求；不负责主动检测 TCP 断线。 */
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final AtomicReference<Call> activeCall = new AtomicReference<>();

    @Setter(AccessLevel.NONE)
    private volatile String stopReason;
    @Setter(AccessLevel.NONE)
    private volatile boolean finished;

    public void cancel() {
        cancel("用户停止生成");
    }

    /** 回调、停止检查线程和工作线程可能同时调用，首次停止原因保留。 */
    public synchronized void cancel(String reason) {
        if (finished || stopped.get()) {
            return;
        }
        stopReason = reason;
        stopped.set(true);
        Call call = activeCall.get();
        if (call != null) {
            call.cancel();
        }
    }

    public void bindCall(Call call) {
        activeCall.set(call);
        // 停止可能发生在 Call 创建之前；登记后必须再检查一次。
        if (stopped.get() || finished) {
            call.cancel();
        }
        checkStopped();
    }

    public void checkStopped() {
        if (stopped.get()) {
            throw new ServiceException(stopReason);
        }
    }

    /** 正常回答准备入库后，不再接受晚到的停止信号来改写本轮结果。 */
    public synchronized void beginCompletion() {
        checkStopped();
        finished = true;
    }

    public synchronized void finish() {
        finished = true;
        Call call = activeCall.getAndSet(null);
        if (call != null) {
            call.cancel();
        }
    }
}