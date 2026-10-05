package com.lhsg.HuiAnswer.rag.knowledge.mq.consumer;

import com.lhsg.HuiAnswer.framework.base.MsgWrapper;
import com.lhsg.HuiAnswer.framework.duplicate.Idempotent;
import com.lhsg.HuiAnswer.rag.knowledge.mq.base.DocumentMsg;
import com.lhsg.HuiAnswer.rag.knowledge.service.IKnowledgeDocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.ingestion.mq-enabled", havingValue = "true", matchIfMissing = true)
@RocketMQMessageListener(topic = "${rocketmq.producer.topic}", consumerGroup = "${rocketmq.consumer.group}",
        consumeThreadNumber = 2, consumeThreadMax = 2, maxReconsumeTimes = 3, consumeTimeout = 120)
public class DocumentConsumer implements RocketMQListener<MsgWrapper<DocumentMsg>> {

    private final IKnowledgeDocumentService documentService;

    @Override
    @Idempotent
    public void onMessage(MsgWrapper<DocumentMsg> message) {
        log.info("收到文档消息，docId={}，keys={}", message.getMsg().getDocId(), message.getKeys());
        // 转换由 RocketMQ Spring 完成；业务异常继续抛出，MQ 才会安排重试。
        documentService.consumeDocument(message.getMsg());
    }
}
