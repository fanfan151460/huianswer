package com.lhsg.HuiAnswer.rag.memory.Conversation.service;

import com.lhsg.HuiAnswer.rag.memory.dto.resp.ListConversationResp;

import java.util.List;

public interface IConversationService {
    /**
     * 查询当前用户在指定知识库下的未删除会话，按更新时间倒序
     * @param kbId 知识库id
     * @return 响应实体集合
     */
    List<ListConversationResp> listConversation(long kbId);

    /**
     * 新加会话
     */
    Long addConversation(long kbId, String question);

    /**
     * 查询会话id是否为用户的
     */
    Boolean ifYourConversation(Long conversationId);
}
