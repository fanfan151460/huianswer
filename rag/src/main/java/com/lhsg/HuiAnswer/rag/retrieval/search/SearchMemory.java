package com.lhsg.HuiAnswer.rag.retrieval.search;

import com.lhsg.HuiAnswer.rag.memory.Conversation.service.IMessageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// TODO 查询历史并压缩摘要（压缩视情况可选）
@Component
@RequiredArgsConstructor
public class SearchMemory {
    private final IMessageService messageService;
}
