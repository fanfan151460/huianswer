package com.lhsg.HuiAnswer.rag.memory.service;

import com.lhsg.HuiAnswer.rag.memory.dto.resp.ConversationResp;

import java.util.List;

public interface IConversationService {
    /** 查询当前用户的未删除会话，按更新时间倒序。 */
    List<ConversationResp> listConversation();

    /** 从首个问题创建会话，所属用户取自登录上下文。 */
    Long addConversation(String question);

    /** 查询会话id是否为用户的 */
    Boolean ifYourConversation(Long conversationId);

    /** 修改当前用户会话的标题。 */
    void updateConversation(Long conversationId, String title);

    /** 逻辑删除当前用户的会话、消息和摘要。 */
    void deleteConversation(Long conversationId);

}
