package com.lhsg.HuiAnswer.rag.memory.memoryStore;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.google.gson.Gson;
import com.lhsg.HuiAnswer.framework.exception.ClientException;
import com.lhsg.HuiAnswer.framework.exception.ServiceException;
import com.lhsg.HuiAnswer.rag.memory.dao.entity.ChatConversationDO;
import com.lhsg.HuiAnswer.rag.memory.dao.entity.ChatMessageDO;
import com.lhsg.HuiAnswer.rag.memory.dao.mapper.ChatConversationMapper;
import com.lhsg.HuiAnswer.rag.memory.dao.mapper.ChatMessageMapper;
import com.lhsg.HuiAnswer.rag.memory.dto.req.StoreDialogueDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 持久化用户问题和助手的结束态；由调用方持有会话 Redis 锁。
 */
@Component
@RequiredArgsConstructor
public class SaveDialogue {
    private final ChatMessageMapper chatMessageMapper;
    private final ChatConversationMapper chatConversationMapper;
    private final TransactionTemplate transactionTemplate;
    private final Gson gson;

    /**
     * 同时保存问题和更新会话时间。
     */
    public int saveQuestion(Long conversationId, Long userId, long kbId, String question) {
        AtomicInteger turnNo = new AtomicInteger();
        transactionTemplate.executeWithoutResult(status -> {
            turnNo.set(chatMessageMapper.selectMaxTurnNo(conversationId) + 1);
            ChatMessageDO message = new ChatMessageDO().setConversationId(conversationId)
                    .setTurnNo(turnNo.get()).setRole("user").setContent(question)
                    .setStatus("success").setSources("[]");
            if (chatMessageMapper.insert(message) != 1) {
                throw new ServiceException("保存用户问题失败");
            }
            // 按归属和知识库更新；无匹配会话时抛异常，问题插入也会回滚。
            touchConversation(conversationId, userId, kbId);
        });
        return turnNo.get();
    }

    /**
     * 助手消息插入和会话时间更新一起提交
     */
    public void storeDialogue(StoreDialogueDTO dialogueDTO) {
        transactionTemplate.executeWithoutResult(status -> {
            ChatMessageDO message = new ChatMessageDO()
                    .setConversationId(dialogueDTO.getConversationId())
                    .setTurnNo(dialogueDTO.getTurnNo()).setRole("assistant")
                    .setContent(dialogueDTO.getContent()).setStatus(dialogueDTO.getStatus())
                    .setSources(gson.toJson(dialogueDTO.getSources() == null
                            ? List.of() : dialogueDTO.getSources()))
                    .setModel(dialogueDTO.getModel()).setTotalTokens(dialogueDTO.getTotalTokens())
                    .setFinishReason(dialogueDTO.getFinishReason()).setErrorMessage(dialogueDTO.getErrorMessage());
            if (chatMessageMapper.insert(message) != 1) {
                throw new ServiceException("保存助手消息失败");
            }
            touchConversation(dialogueDTO.getConversationId(), dialogueDTO.getUserId(), null);
        });
    }

    /**
     * 更新会话时间
     */
    private void touchConversation(Long conversationId, Long userId, Long kbId) {
        ChatConversationDO update = new ChatConversationDO().setUpdateTime(LocalDateTime.now());
        int affected = chatConversationMapper.update(update, Wrappers.<ChatConversationDO>lambdaUpdate()
                .eq(ChatConversationDO::getId, conversationId)
                .eq(ChatConversationDO::getUserId, userId)
                .eq(kbId != null, ChatConversationDO::getKbId, kbId));
        if (affected != 1) {
            throw new ClientException("会话不存在、无权访问或知识库不匹配");
        }
    }
}