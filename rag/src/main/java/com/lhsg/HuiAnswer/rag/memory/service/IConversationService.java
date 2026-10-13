package com.lhsg.HuiAnswer.rag.memory.service;

import com.lhsg.HuiAnswer.rag.memory.dto.resp.ListConversationResp;

import java.util.List;

public interface IConversationService {
    /** 查询当前用户的未删除会话，按更新时间倒序。 */
    List<ListConversationResp> listConversation();

    /** 从首个问题创建会话，所属用户取自登录上下文。 */
    Long addConversation(String question);

    /**
     * 查询会话id是否为用户的
     */
    Boolean ifYourConversation(Long conversationId);
}
