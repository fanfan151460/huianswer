package com.lhsg.HuiAnswer.rag.memory.service;

import com.lhsg.HuiAnswer.rag.memory.dto.search.SearchMemoryDTO;
import com.lhsg.HuiAnswer.rag.memory.dto.search.SearchSummaryMemoryDTO;

import java.util.List;

public interface ISummaryService {

    /**
     * 历史及摘要
     * @param conversationId 会话id
     * @param userId 用户id
     */
    SearchSummaryMemoryDTO summaryMemory(Long conversationId, Long userId);

    /**
     * 满预设配置轮次压缩历史对话
     * @param conversationId 会话id
     * @param userId 用户id
     */
    void addSummary(Long conversationId, Long userId);

    /**
     * 压缩历史对话以及早期摘要
     * @param previousSummary 早期摘要
     * @param memory 历史待压缩对话
     * @return 新的摘要
     */
    String toSummary(String previousSummary, List<SearchMemoryDTO> memory);
}
