package com.lhsg.HuiAnswer.rag.chat.build.task;

import com.lhsg.HuiAnswer.framework.exception.ServiceException;
import okhttp3.Call;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatGenerationTaskTest {
    @Test
    void stopBeforeCallCreationCancelsLateCallAndPreventsCompletion() throws Exception {
        ChatGenerationTask task = new ChatGenerationTask();
        task.cancel();
        Call call = mock(Call.class);

        assertThrows(ServiceException.class, () -> task.bindCall(call));
        assertThrows(ServiceException.class, task::beginCompletion);
        verify(call).cancel();
        verify(call, never()).execute();
    }

    @Test
    void firstStopReasonIsPreservedWhenCallbacksAlsoCancel() {
        ChatGenerationTask task = new ChatGenerationTask();
        Call call = mock(Call.class);
        task.bindCall(call);

        task.cancel();
        task.cancel("流式连接已关闭");

        assertEquals("用户停止生成", task.getStopReason());
        assertTrue(task.getStopped().get());
        verify(call, times(1)).cancel();
    }

    @Test
    void normalCompletionRejectsLateCancellationAndReleasesCallReference() {
        ChatGenerationTask task = new ChatGenerationTask();
        Call call = mock(Call.class);
        task.bindCall(call);
        task.beginCompletion();

        task.cancel();
        assertFalse(task.getStopped().get());
        task.finish();

        assertTrue(task.isFinished());
        assertNull(task.getActiveCall().get());
        verify(call).cancel();
    }
}