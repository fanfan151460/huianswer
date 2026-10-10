package com.lhsg.HuiAnswer.rag.memory.Conversation.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lhsg.HuiAnswer.framework.exception.ClientException;
import com.lhsg.HuiAnswer.framework.exception.ServiceException;
import com.lhsg.HuiAnswer.rag.admin.user.UsersContext;
import com.lhsg.HuiAnswer.rag.memory.Conversation.service.IConversationService;
import com.lhsg.HuiAnswer.rag.memory.dao.entity.ChatConversationDO;
import com.lhsg.HuiAnswer.rag.memory.dao.mapper.ChatConversationMapper;
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
    public Long addConversation(long kbId, String question) {
        Long userId = UsersContext.requireUserId();
        if (kbId <= 0) {
            throw new ClientException("知识库 ID 不正确");
        }
        if (StrUtil.isBlank(question)) {
            throw new ClientException("请咨询你想要回复的问题");
        }
        ChatConversationDO chatConversationDO = new ChatConversationDO().setUserId(userId).setKbId(kbId)
                .setTitle(StrUtil.sub(question.trim(), 0, 128));
        int insert = chatConversationMapper.insert(chatConversationDO);
        if (insert <= 0) {
            throw new ServiceException("创建会话失败");
        }
        return chatConversationDO.getId();
    }

    // TODO redis缓存改进
    public Boolean ifYourConversation(Long conversationId) {
        Long userId = UsersContext.requireUserId();
        ChatConversationDO chatConversationDO = chatConversationMapper.selectOne(Wrappers.<ChatConversationDO>lambdaQuery().eq(ChatConversationDO::getUserId, userId)
                .eq(ChatConversationDO::getId, conversationId));
        return chatConversationDO != null;
    }
}
