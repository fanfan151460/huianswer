package com.lhsg.HuiAnswer.rag.knowledge;

import com.lhsg.HuiAnswer.framework.exception.ServiceException;
import com.lhsg.HuiAnswer.rag.knowledge.mq.base.DocumentMsg;
import com.lhsg.HuiAnswer.rag.knowledge.mq.base.MsgWrapper;
import com.lhsg.HuiAnswer.rag.knowledge.mq.consumer.DocumentConsumer;
import com.lhsg.HuiAnswer.rag.knowledge.mq.producer.DocumentProducer;
import com.lhsg.HuiAnswer.rag.knowledge.service.IKnowledgeDocumentService;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocumentMqTest {
    @Test
    void producerShouldRejectUnsuccessfulSend() {
        RocketMQTemplate template = mock(RocketMQTemplate.class);
        DocumentProducer producer = new DocumentProducer(template);
        ReflectionTestUtils.setField(producer, "topic", "documents");
        SendResult result = new SendResult();
        result.setSendStatus(SendStatus.FLUSH_DISK_TIMEOUT);
        when(template.syncSend(eq("documents"), any(Message.class))).thenReturn(result);
        assertThrows(ServiceException.class,
                () -> producer.send(new DocumentMsg().setKbId(7L).setDocId(11L)));
    }

    @Test
    void consumerShouldPropagateFailureSoMqCanRetry() {
        IKnowledgeDocumentService documents = mock(IKnowledgeDocumentService.class);
        DocumentConsumer consumer = new DocumentConsumer(documents);
        DocumentMsg msg = new DocumentMsg().setKbId(7L).setDocId(11L);
        doThrow(new ServiceException("处理失败")).when(documents).consumeDocument(msg);
        assertThrows(ServiceException.class,
                () -> consumer.onMessage(new MsgWrapper<>("test-key", msg)));
    }
}
