package com.lhsg.HuiAnswer.rag.memory.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lhsg.HuiAnswer.framework.exception.ClientException;
import com.lhsg.HuiAnswer.framework.exception.ServiceException;
import com.lhsg.HuiAnswer.rag.admin.user.UsersContext;
import com.lhsg.HuiAnswer.rag.memory.service.IConversationService;
import com.lhsg.HuiAnswer.rag.memory.dao.entity.ChatConversationDO;
import com.lhsg.HuiAnswer.rag.memory.dao.mapper.ChatConversationMapper;
import com.lhsg.HuiAnswer.rag.memory.dao.entity.ChatMessageDO;
import com.lhsg.HuiAnswer.rag.memory.dao.entity.ChatSummaryDO;
import com.lhsg.HuiAnswer.rag.memory.dao.mapper.ChatMessageMapper;
import com.lhsg.HuiAnswer.rag.memory.dao.mapper.ChatSummaryMapper;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.transaction.support.TransactionTemplate;


import com.lhsg.HuiAnswer.rag.memory.dto.resp.ConversationResp;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

import static com.lhsg.HuiAnswer.rag.constant.constant.HUI_ANSWER_LOCK_CONVERSATION;
import static com.lhsg.HuiAnswer.rag.constant.constant.HUI_ANSWER_LOCK_SUMMARY;

@Service
@RequiredArgsConstructor
public class ConversationImpl implements IConversationService {
    private final ChatConversationMapper chatConversationMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final ChatSummaryMapper chatSummaryMapper;
    private final TransactionTemplate transactionTemplate;
    private final RedissonClient redissonClient;

    @Override
    public List<ConversationResp> listConversation() {
        long userId = UsersContext.requireUserId();
        LambdaQueryWrapper<ChatConversationDO> wrapper = Wrappers.lambdaQuery(ChatConversationDO.class)
                .eq(ChatConversationDO::getUserId, userId)
                .orderByDesc(ChatConversationDO::getUpdateTime)
                .orderByDesc(ChatConversationDO::getId);
        // @TableLogic 会自动排除已删除的会话。
        List<ChatConversationDO> conversations = chatConversationMapper.selectList(wrapper);
        return BeanUtil.copyToList(conversations, ConversationResp.class);
    }

    @Override
    public Long addConversation(String question) {
        Long userId = UsersContext.requireUserId();
        if (StrUtil.isBlank(question)) {
            throw new ClientException("请咨询你想要回复的问题");
        }
        ChatConversationDO chatConversationDO = new ChatConversationDO().setUserId(userId)
                .setTitle(StrUtil.sub(question.trim(), 0, 20));
        int insert = chatConversationMapper.insert(chatConversationDO);
        if (insert <= 0) {
            throw new ServiceException("创建会话失败");
        }
        return chatConversationDO.getId();
    }

    @Override
    public void updateConversation(Long conversationId, String title) {
        Long userId = UsersContext.requireUserId();
        validateConversationId(conversationId);
        if (StrUtil.isBlank(title) || title.strip().length() > 20) {
            throw new ClientException("会话标题不能为空且不能超过20个字符");
        }
        // 只更新标题；用户归属和逻辑删除条件同时参与更新。
        ChatConversationDO update = new ChatConversationDO().setTitle(title.strip());
        int affected = chatConversationMapper.update(update,
                Wrappers.<ChatConversationDO>lambdaUpdate()
                        .eq(ChatConversationDO::getId, conversationId)
                        .eq(ChatConversationDO::getUserId, userId));
        if (affected != 1) {
            throw new ClientException("会话不存在或无权访问");
        }
    }

    @Override
    public void deleteConversation(Long conversationId) {
        Long userId = UsersContext.requireUserId();
        validateConversationId(conversationId);
        // 与问答共用会话锁；删除接口不负责中止正在生成的回答。
        RLock conversationLock = redissonClient.getLock(
                HUI_ANSWER_LOCK_CONVERSATION.formatted(conversationId));
        if (!conversationLock.tryLock()) {
            throw new ClientException("会话正在生成回答，请结束后再删除");
        }
        try {
            // 摘要持有此锁直到写库，防止删除期间又保存一份新摘要。
            RLock summaryLock = redissonClient.getLock(
                    HUI_ANSWER_LOCK_SUMMARY.formatted(conversationId));
            if (!summaryLock.tryLock()) {
                throw new ClientException("会话正在压缩中！");
            }
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    // 先确认当前用户能删除父记录，再删除关联数据；任一操作失败整体回滚。
                    int affected = chatConversationMapper.delete(
                            Wrappers.<ChatConversationDO>lambdaQuery()
                                    .eq(ChatConversationDO::getId, conversationId)
                                    .eq(ChatConversationDO::getUserId, userId));
                    if (affected != 1) {
                        throw new ClientException("会话不存在或无权访问");
                    }
                    chatMessageMapper.delete(Wrappers.<ChatMessageDO>lambdaQuery()
                            .eq(ChatMessageDO::getConversationId, conversationId));
                    chatSummaryMapper.delete(Wrappers.<ChatSummaryDO>lambdaQuery()
                            .eq(ChatSummaryDO::getConversationId, conversationId));
                });
            } finally {
                summaryLock.unlock();
            }
        } finally {
            conversationLock.unlock();
        }
    }

    private void validateConversationId(Long conversationId) {
        if (conversationId == null || conversationId <= 0) {
            throw new ClientException("会话 ID 不正确");
        }
    }

    // TODO redis缓存改进
    public Boolean ifYourConversation(Long conversationId) {
        Long userId = UsersContext.requireUserId();
        ChatConversationDO chatConversationDO = chatConversationMapper.selectOne(Wrappers.<ChatConversationDO>lambdaQuery().eq(ChatConversationDO::getUserId, userId)
                .eq(ChatConversationDO::getId, conversationId));
        return chatConversationDO != null;
    }
}
