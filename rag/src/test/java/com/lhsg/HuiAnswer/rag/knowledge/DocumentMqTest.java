package com.lhsg.HuiAnswer.rag.knowledge;

import com.lhsg.HuiAnswer.framework.base.MsgWrapper;
import com.lhsg.HuiAnswer.framework.exception.ServiceException;
import com.lhsg.HuiAnswer.rag.knowledge.mq.base.DocumentMsg;
import com.lhsg.HuiAnswer.rag.knowledge.mq.consumer.DocumentConsumer;
import com.lhsg.HuiAnswer.rag.knowledge.mq.producer.DocumentProducer;
import com.lhsg.HuiAnswer.rag.knowledge.service.IKnowledgeDocumentService;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.Message;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DocumentMqTest {
    private RocketMQTemplate template;
    private DocumentProducer producer;
    private final DocumentMsg msg = new DocumentMsg().setKbId(7L).setDocId(11L);

    @BeforeEach
    void setUp() {
        template = mock(RocketMQTemplate.class);
        producer = new DocumentProducer(template);
        ReflectionTestUtils.setField(producer, "topic", "documents");
        // 单测只验证发送行为，不实际等待，也不连接 Broker。
        ReflectionTestUtils.setField(producer, "retryInitialDelayMs", 0L);
    }

    @Test
    void producerShouldSendOnlyOnceWhenSuccessful() {
        SendResult result = new SendResult();
        result.setSendStatus(SendStatus.SEND_OK);
        when(template.syncSend(eq("documents"), any(Message.class))).thenReturn(result);

        producer.send(msg);

        verify(template).syncSend(eq("documents"), any(Message.class));
    }

    @Test
    void producerShouldReuseMessageKeyAndStopWhenRetrySucceeds() {
        SendResult rejected = new SendResult();
        rejected.setSendStatus(SendStatus.FLUSH_DISK_TIMEOUT);
        SendResult success = new SendResult();
        success.setSendStatus(SendStatus.SEND_OK);
        when(template.syncSend(eq("documents"), any(Message.class)))
                .thenThrow(new IllegalStateException("连接暂时中断"))
                .thenReturn(rejected)
                .thenReturn(null)
                .thenReturn(success);

        producer.send(msg);

        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(template, times(4)).syncSend(eq("documents"), captor.capture());
        Message<?> first = captor.getAllValues().get(0);
        MsgWrapper<?> wrapper = (MsgWrapper<?>) first.getPayload();
        assertSame(msg, wrapper.getMsg());
        assertFalse(wrapper.getKeys().isBlank());
        for (Message<?> sent : captor.getAllValues()) {
            assertSame(first, sent);
            assertEquals(wrapper.getKeys(), sent.getHeaders().get(MessageConst.PROPERTY_KEYS));
        }
    }

    @Test
    void producerShouldRejectUnsuccessfulSendAfterFiveRetries() {
        SendResult result = new SendResult();
        result.setSendStatus(SendStatus.FLUSH_DISK_TIMEOUT);
        when(template.syncSend(eq("documents"), any(Message.class))).thenReturn(result);

        assertThrows(ServiceException.class, () -> producer.send(msg));

        verify(template, times(6)).syncSend(eq("documents"), any(Message.class));
    }

    @Test
    void producerShouldPropagateCauseAfterRetriesAreExhausted() {
        IllegalStateException failure = new IllegalStateException("Broker 不可用");
        when(template.syncSend(eq("documents"), any(Message.class))).thenThrow(failure);

        ServiceException error = assertThrows(ServiceException.class, () -> producer.send(msg));

        assertSame(failure, error.getCause());
        verify(template, times(6)).syncSend(eq("documents"), any(Message.class));
    }

    @Test
    void producerShouldStopRetryingAndPreserveInterrupt() {
        when(template.syncSend(eq("documents"), any(Message.class))).thenAnswer(call -> {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("发送被中断");
        });
        try {
            assertThrows(ServiceException.class, () -> producer.send(msg));
            assertTrue(Thread.currentThread().isInterrupted());
            verify(template).syncSend(eq("documents"), any(Message.class));
        } finally {
            // 避免中断标志影响 JUnit 后续测试。
            Thread.interrupted();
        }
    }

    @Test
    void consumerShouldPropagateFailureSoMqCanRetry() {
        IKnowledgeDocumentService documents = mock(IKnowledgeDocumentService.class);
        DocumentConsumer consumer = new DocumentConsumer(documents);
        doThrow(new ServiceException("处理失败")).when(documents).consumeDocument(msg);
        assertThrows(ServiceException.class,
                () -> consumer.onMessage(new MsgWrapper<>("test-key", msg)));
    }
}
