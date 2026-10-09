package com.lhsg.HuiAnswer.rag.memory.Conversation.service;

import com.lhsg.HuiAnswer.rag.memory.dto.resp.MessageResp;

import java.util.List;

public interface IMessageService {
    /**
     * 查询当前用户会话的展示历史，包含失败回答和未完成轮次
     * @param conversationId 会话id
     * @return 按轮次排序的消息列表，每轮先问题后回答
     */
    List<MessageResp> getHistory(long conversationId);
}
