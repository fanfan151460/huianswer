package com.lhsg.HuiAnswer.rag.knowledge.mq.producer;

import com.lhsg.HuiAnswer.framework.base.MsgWrapper;
import com.lhsg.HuiAnswer.framework.exception.ServiceException;
import com.lhsg.HuiAnswer.rag.knowledge.mq.base.DocumentMsg;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentProducer {
    private final RocketMQTemplate rocketMQTemplate;

    @Value("${rocketmq.producer.topic}")
    private String topic;

    public Message<MsgWrapper<DocumentMsg>> buildMsg(DocumentMsg documentMsg) {
        String keys = UUID.randomUUID().toString();
        return MessageBuilder.withPayload(new MsgWrapper<>(keys, documentMsg))
                .setHeader(MessageConst.PROPERTY_KEYS, keys)
                .build();
    }

    public void send(DocumentMsg documentMsg) {
        Message<MsgWrapper<DocumentMsg>> message = buildMsg(documentMsg);
        try {
            // 只等待消息发送完成，不等待消费者解析文件或调用模型。
            SendResult result = rocketMQTemplate.syncSend(topic, message);
            if (result == null || result.getSendStatus() != SendStatus.SEND_OK) {
                throw new ServiceException("文档消息发送失败");
            }
            log.info("文档消息发送成功，docId={}，keys={}", documentMsg.getDocId(), message.getPayload().getKeys());
        } catch (Exception e) {
            log.error("文档消息发送失败，docId={}，keys={}", documentMsg.getDocId(), message.getPayload().getKeys(), e);
            throw new ServiceException("文档消息发送失败，请稍后重试");
        }
    }
}
