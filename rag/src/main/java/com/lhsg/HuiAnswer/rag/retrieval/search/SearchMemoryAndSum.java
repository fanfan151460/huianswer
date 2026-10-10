package com.lhsg.HuiAnswer.rag.retrieval.search;

import cn.hutool.core.bean.BeanUtil;
import com.lhsg.HuiAnswer.rag.memory.Conversation.service.IMessageService;
import com.lhsg.HuiAnswer.rag.memory.dto.resp.MessageResp;
import com.lhsg.HuiAnswer.rag.retrieval.dto.search.SearchMemoryDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

// TODO 查询历史并压缩摘要（压缩视情况可选）
@Component
@RequiredArgsConstructor
public class SearchMemoryAndSum {
    private final IMessageService messageService;

    @Value("${rag.search.summary}")
    private Boolean ifSummary;
    @Value("${rag.search.turns}")
    private int maxTurns;

    public List<SearchMemoryDTO> getMemory(Long conversationId) {
        List<MessageResp> messages = messageService.getHistory(conversationId);
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

    public List<SearchMemoryDTO> getSummaryAndMemory(List<SearchMemoryDTO> searchMemoryDTOs) {
        if (ifSummary) {
            List<SearchMemoryDTO> otherList = new ArrayList<>(searchMemoryDTOs);
            // 一轮 = 用户问题 + 模型回答两条，所以按“轮”取最近的要换算成条数。
            int maxMessages = maxTurns * 2;
            int size = searchMemoryDTOs.size();
            if (size <= maxMessages) {
                return searchMemoryDTOs;
            }
            int from = size - maxMessages;
            List<SearchMemoryDTO> maxMemory = searchMemoryDTOs.subList(from, size);
            List<SearchMemoryDTO> needSummary = otherList.subList(0, from);
            return searchMemoryDTOs;
        } else {
            return searchMemoryDTOs;
        }
    }
}
