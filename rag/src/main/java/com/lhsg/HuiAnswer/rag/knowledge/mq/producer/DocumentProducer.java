package com.lhsg.HuiAnswer.rag.knowledge.mq.producer;

import com.lhsg.HuiAnswer.framework.base.MsgWrapper;
import com.lhsg.HuiAnswer.framework.exception.ServiceException;
import com.lhsg.HuiAnswer.framework.result.errorcode.BaseErrorCode;
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
    // 首次发送不算重试，最多发送 1 + 5 次。
    private static final int MAX_RETRIES = 5;

    private final RocketMQTemplate rocketMQTemplate;

    @Value("${rocketmq.producer.topic}")
    private String topic;

    @Value("${rocketmq.producer.retry-initial-delay-ms:200}")
    private long retryInitialDelayMs = 200L;

    public Message<MsgWrapper<DocumentMsg>> buildMsg(DocumentMsg documentMsg) {
        String keys = UUID.randomUUID().toString();
        return MessageBuilder.withPayload(new MsgWrapper<>(keys, documentMsg))
                .setHeader(MessageConst.PROPERTY_KEYS, keys)
                .build();
    }

    public void send(DocumentMsg documentMsg) {
        // 重试复用同一个消息和 key；发送超时也可能已到 Broker，消费者靠 key 去重。
        Message<MsgWrapper<DocumentMsg>> message = buildMsg(documentMsg);
        long delayMs = retryInitialDelayMs;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                // 只等待消息发送完成，不等待消费者解析文件或调用模型。
                SendResult result = rocketMQTemplate.syncSend(topic, message);
                if (result == null || result.getSendStatus() != SendStatus.SEND_OK) {
                    throw new ServiceException("文档消息发送失败");
                }
                log.info("文档消息发送成功，docId={}，keys={}，发送次数={}",
                        documentMsg.getDocId(), message.getPayload().getKeys(), attempt + 1);
                return;
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) {
                    Thread.currentThread().interrupt();
                    throw new ServiceException("文档消息发送已中断");
                }
                if (attempt == MAX_RETRIES) {
                    log.error("文档消息发送失败，docId={}，keys={}，已重试{}次",
                            documentMsg.getDocId(), message.getPayload().getKeys(), MAX_RETRIES, e);
                    // 交给 enqueue 保存文档 failed 状态，保留原文件和手动重试入口。
                    throw new ServiceException("文档消息发送失败，已重试5次，请稍后重试",
                            e, BaseErrorCode.SERVICE_ERROR);
                }
                log.warn("文档消息发送失败，docId={}，keys={}，{}ms后进行第{}次重试，原因={}",
                        documentMsg.getDocId(), message.getPayload().getKeys(), delayMs, attempt + 1,
                        e.getMessage());
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new ServiceException("文档消息重试等待已中断", interrupted, BaseErrorCode.SERVICE_ERROR);
                }
                delayMs *= 2;
            }
        }
    }
}
