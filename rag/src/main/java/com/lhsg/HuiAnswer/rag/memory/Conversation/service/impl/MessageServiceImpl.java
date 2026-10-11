package com.lhsg.HuiAnswer.rag.memory.Conversation.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.google.gson.Gson;
import com.lhsg.HuiAnswer.framework.exception.ClientException;
import com.lhsg.HuiAnswer.framework.exception.ServiceException;
import com.lhsg.HuiAnswer.rag.admin.user.UsersContext;
import com.lhsg.HuiAnswer.rag.chat.dto.NeedChunksResp;
import com.lhsg.HuiAnswer.rag.memory.Conversation.service.IMessageService;
import com.lhsg.HuiAnswer.rag.memory.dao.entity.ChatConversationDO;
import com.lhsg.HuiAnswer.rag.memory.dao.entity.ChatMessageDO;
import com.lhsg.HuiAnswer.rag.memory.dao.mapper.ChatConversationMapper;
import com.lhsg.HuiAnswer.rag.memory.dao.mapper.ChatMessageMapper;
import com.lhsg.HuiAnswer.rag.memory.dto.req.StoreDialogueDTO;
import com.lhsg.HuiAnswer.rag.memory.dto.resp.MessageResp;
import com.lhsg.HuiAnswer.rag.memory.dto.search.SearchMemoryDTO;
import io.micrometer.common.lang.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
public class MessageServiceImpl implements IMessageService {
    private final ChatMessageMapper chatMessageMapper;
    private final ChatConversationMapper chatConversationMapper;
    private final TransactionTemplate transactionTemplate;
    private final Gson gson;

    @Value("${rag.search.summary}")
    private Boolean ifSummary;
    @Value("${rag.search.turns}")
    private int maxTurns;

    @Override
    public List<MessageResp> getHistory(Long conversationId, @Nullable Long userId) {
        if (userId == null) {
            userId = UsersContext.requireUserId();
        }
        if (conversationId <= 0) {
            throw new ClientException("会话 ID 不正确");
        }
        // 会话 ID 来自请求，必须同时校验归属；@TableLogic 排除已删除会话。
        ChatConversationDO conversation = chatConversationMapper.selectOne(
                Wrappers.lambdaQuery(ChatConversationDO.class)
                        .eq(ChatConversationDO::getId, conversationId)
                        .eq(ChatConversationDO::getUserId, userId));
        if (conversation == null) {
            throw new ClientException("会话不存在或无权访问");
        }

        List<ChatMessageDO> messages = chatMessageMapper.selectList(
                Wrappers.lambdaQuery(ChatMessageDO.class)
                        .eq(ChatMessageDO::getConversationId, conversationId)
                        .orderByAsc(ChatMessageDO::getTurnNo)
                        // 角色只有 user/assistant，降序使同一轮的问题排在回答前面。
                        .orderByDesc(ChatMessageDO::getRole));
        List<MessageResp> result = new ArrayList<>(messages.size());
        for (ChatMessageDO message : messages) {
            MessageResp resp = new MessageResp();
            // 同名字段直接复制；sources 单独从 JSON 字符串转成列表，避免响应出现转义 JSON。
            BeanUtil.copyProperties(message, resp, "sources");
            if (StrUtil.isNotBlank(message.getSources())) {
                NeedChunksResp[] sources = gson.fromJson(message.getSources(), NeedChunksResp[].class);
                resp.setSources(sources == null ? List.of() : Arrays.asList(sources));
            }
            result.add(resp);
        }
        // 这是前端展示历史：保留失败回答和仅有问题的轮次，不能直接当作模型上下文。
        return result;
    }

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

    public List<SearchMemoryDTO> getMemory(Long conversationId, Long userId) {
        List<MessageResp> messages = getHistory(conversationId, userId);
        List<SearchMemoryDTO> success = new ArrayList<>();

        for (int i = 0; i < messages.size() - 1; i++) {
            MessageResp question = messages.get(i);
            MessageResp answer = messages.get(i + 1);

            if ("user".equals(question.getRole())
                    && "assistant".equals(answer.getRole())
                    && question.getTurnNo().equals(answer.getTurnNo())
                    && "success".equals(question.getStatus())
                    && "success".equals(answer.getStatus())) {

                success.add(BeanUtil.copyProperties(
                        question, SearchMemoryDTO.class));
                success.add(BeanUtil.copyProperties(
                        answer, SearchMemoryDTO.class));
                i++;
            }
        }
        return success;
    }

    /**
     * 返回最近${rag.search.turns}轮次的会话历史
     * @param searchMemoryDTOs 会话历史
     */
    public List<SearchMemoryDTO> getMaxMemory(List<SearchMemoryDTO> searchMemoryDTOs) {
        if (ifSummary) {
            int maxMessages = maxTurns * 2;
            int size = searchMemoryDTOs.size();
            if (size <= maxMessages) {
                return searchMemoryDTOs;
            }
            int from = size - maxMessages;
            return searchMemoryDTOs.subList(from, size);
        } else {
            return searchMemoryDTOs;
        }
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
