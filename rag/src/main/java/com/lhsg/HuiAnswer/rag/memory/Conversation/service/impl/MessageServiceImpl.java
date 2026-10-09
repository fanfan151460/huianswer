package com.lhsg.HuiAnswer.rag.memory.Conversation.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.google.gson.Gson;
import com.lhsg.HuiAnswer.framework.exception.ClientException;
import com.lhsg.HuiAnswer.rag.admin.user.UsersContext;
import com.lhsg.HuiAnswer.rag.chat.dto.NeedChunksResp;
import com.lhsg.HuiAnswer.rag.memory.Conversation.service.IMessageService;
import com.lhsg.HuiAnswer.rag.memory.dao.entity.ChatConversationDO;
import com.lhsg.HuiAnswer.rag.memory.dao.entity.ChatMessageDO;
import com.lhsg.HuiAnswer.rag.memory.dao.mapper.ChatConversationMapper;
import com.lhsg.HuiAnswer.rag.memory.dao.mapper.ChatMessageMapper;
import com.lhsg.HuiAnswer.rag.memory.dto.resp.MessageResp;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Service
@RequiredArgsConstructor
public class MessageServiceImpl implements IMessageService {
    private final ChatMessageMapper chatMessageMapper;
    private final ChatConversationMapper chatConversationMapper;
    private final Gson gson;

    @Override
    public List<MessageResp> getHistory(long conversationId) {
        long userId = UsersContext.requireUserId();
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
}
