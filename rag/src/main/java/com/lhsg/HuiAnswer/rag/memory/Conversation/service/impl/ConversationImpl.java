package com.lhsg.HuiAnswer.rag.memory.Conversation.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lhsg.HuiAnswer.framework.exception.ClientException;
import com.lhsg.HuiAnswer.framework.exception.ServiceException;
import com.lhsg.HuiAnswer.rag.admin.user.UsersContext;
import com.lhsg.HuiAnswer.rag.memory.Conversation.service.IConversationService;
import com.lhsg.HuiAnswer.rag.memory.dao.entity.ChatConversationDO;
import com.lhsg.HuiAnswer.rag.memory.dao.mapper.ChatConversationMapper;
import com.lhsg.HuiAnswer.rag.memory.dto.req.AddConversationReq;
import com.lhsg.HuiAnswer.rag.memory.dto.resp.AddConversationResp;
import com.lhsg.HuiAnswer.rag.memory.dto.resp.ListConversationResp;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ConversationImpl implements IConversationService {
    private final ChatConversationMapper chatConversationMapper;

    @Override
    public List<ListConversationResp> listConversation(long kbId) {
        long userId = UsersContext.requireUserId();
        if (kbId <= 0) {
            throw new ClientException("知识库 ID 不正确");
        }
        LambdaQueryWrapper<ChatConversationDO> wrapper = Wrappers.lambdaQuery(ChatConversationDO.class)
                .eq(ChatConversationDO::getUserId, userId)
                .eq(ChatConversationDO::getKbId, kbId)
                .orderByDesc(ChatConversationDO::getUpdateTime)
                .orderByDesc(ChatConversationDO::getId);
        // @TableLogic 会自动排除已删除的会话。
        List<ChatConversationDO> conversations = chatConversationMapper.selectList(wrapper);
        return BeanUtil.copyToList(conversations, ListConversationResp.class);
    }

    @Override
    public AddConversationResp addConversation(AddConversationReq addConversationReq) {
        long userId = UsersContext.requireUserId();
        long kbId = addConversationReq.getKbId();
        String question = addConversationReq.getQuestion();
        if (kbId <= 0) {
            throw new ClientException("知识库 ID 不正确");
        }
        ChatConversationDO chatConversationDO = new ChatConversationDO().setUserId(userId).setKbId(kbId)
                .setTitle(question);
        int insert = chatConversationMapper.insert(chatConversationDO);
        if (insert <= 0) {
            throw new ServiceException("创建会话失败");
        }
        return new AddConversationResp().setConversationId(chatConversationDO.getId());
    }
}
