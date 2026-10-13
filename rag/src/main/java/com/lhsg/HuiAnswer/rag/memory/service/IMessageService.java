package com.lhsg.HuiAnswer.rag.memory.service;

import com.lhsg.HuiAnswer.rag.memory.dto.req.StoreDialogueDTO;
import com.lhsg.HuiAnswer.rag.memory.dto.resp.MessageResp;
import com.lhsg.HuiAnswer.rag.memory.dto.search.SearchMemoryDTO;
import io.micrometer.common.lang.Nullable;

import java.util.List;

public interface IMessageService {
    /**
     * 查询当前用户会话的展示历史，包含失败回答和未完成轮次
     *
     * @param conversationId 会话id
     * @return 按轮次排序的消息列表，每轮先问题后回答
     */
    List<MessageResp> getHistory(Long conversationId, @Nullable Long userId);

    /**
     * 同时保存问题和更新会话时间。
     */
    int saveQuestion(Long conversationId, Long userId, String question);

    /**
     * 持久化助手回答
     * @param dialogueDTO 助手回答实体
     */
    void storeDialogue(StoreDialogueDTO dialogueDTO);

    /**
     * 获取该轮对话所有历史（去除失败轮次）
     * @param conversationId 会话id
     * @return 对话历史（只含问答和角色）
     */
    List<SearchMemoryDTO> getMemory(Long conversationId, Long userId);

    /**
     * 返回最近${rag.memory.turns}轮次的会话历史
     * @param searchMemoryDTOs 会话历史
     */
    List<SearchMemoryDTO> getMaxMemory(List<SearchMemoryDTO> searchMemoryDTOs);
}