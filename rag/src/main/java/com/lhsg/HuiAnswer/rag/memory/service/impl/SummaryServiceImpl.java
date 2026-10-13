package com.lhsg.HuiAnswer.rag.memory.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.lhsg.HuiAnswer.framework.exception.ClientException;
import com.lhsg.HuiAnswer.framework.exception.ServiceException;
import com.lhsg.HuiAnswer.framework.monitor.TimeAnnotation;
import com.lhsg.HuiAnswer.rag.config.ApiUrl;
import com.lhsg.HuiAnswer.rag.memory.service.IMessageService;
import com.lhsg.HuiAnswer.rag.memory.service.ISummaryService;
import com.lhsg.HuiAnswer.rag.memory.dao.entity.ChatConversationDO;
import com.lhsg.HuiAnswer.rag.memory.dao.entity.ChatSummaryDO;
import com.lhsg.HuiAnswer.rag.memory.dao.mapper.ChatConversationMapper;
import com.lhsg.HuiAnswer.rag.memory.dao.mapper.ChatSummaryMapper;
import com.lhsg.HuiAnswer.rag.memory.dto.search.SearchMemoryDTO;
import com.lhsg.HuiAnswer.rag.memory.dto.search.SearchSummaryMemoryDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.lhsg.HuiAnswer.rag.constant.constant.HUI_ANSWER_LOCK_SUMMARY;
import static com.lhsg.HuiAnswer.rag.memory.summary.prompt.summaryPrompt;

@Service
@Slf4j
@RequiredArgsConstructor
public class SummaryServiceImpl implements ISummaryService {
    private final ChatSummaryMapper chatSummaryMapper;
    private final ChatConversationMapper chatConversationMapper;
    private final OkHttpClient httpClient;
    private final Gson gson;
    private final IMessageService messageService;
    private final RedissonClient redissonClient;
    private final ApiUrl apiUrl;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    /**
     * 三轮完整问答，共六条消息。
     */
    @Value("${rag.memory.summary-batch-messages}")
    private int SUMMARY_BATCH_MESSAGES;
    @Value("${rag.memory.summary}")
    private Boolean ifSummary;
    @Value("${rag.memory.turns}")
    private int maxTurns;
    @Value("${rag.ai.siliconflow.api-key}")
    private String apiKey;
    @Value("${rag.ai.siliconflow.chat-model}")
    private String chatModel;
    @Value("${rag.chat.max-model-wait}")
    private long modelWait;
    @Value("${rag.memory.summary-max-tokens}")
    private int summaryMaxTokens;
    @Value("${rag.memory.summary-temperature}")
    private double summaryTemperature;

    public SearchSummaryMemoryDTO summaryMemory(Long conversationId, Long userId) {
        if (conversationId == null) {
            throw new ClientException("会话信息异常");
        }
        ChatConversationDO chatConversationDO = chatConversationMapper.selectOne(Wrappers.lambdaQuery(ChatConversationDO.class)
                .eq(ChatConversationDO::getUserId, userId)
                .eq(ChatConversationDO::getId, conversationId));
        if (chatConversationDO == null) {
            throw new ClientException("会话不存在或无权访问");
        }
        List<SearchMemoryDTO> memory = messageService.getMemory(conversationId, userId);
        if (!ifSummary) {
            return new SearchSummaryMemoryDTO().setMemory(memory);
        }
        ChatSummaryDO summary = chatSummaryMapper.selectOne(Wrappers.lambdaQuery(ChatSummaryDO.class)
                .eq(ChatSummaryDO::getConversationId, conversationId)
                .eq(ChatSummaryDO::getUserId, userId));
        int untilTurnNo = summary == null ? 0 : summary.getSummaryUntilTurnNo();
        String content = summary == null ? "" : summary.getContent();
        // memory 已由 IMessageService 筛成成功的问答对，这里只排除已经压缩过的轮次。
        // 虽然当前不看轮次也能取到最近八轮，但是中途改变了保留轮次的配置后会返回已经压缩的原文。
        List<SearchMemoryDTO> remaining = memory.stream()
                .filter(each -> each.getTurnNo() > untilTurnNo).toList();
        // 压缩可能滞后甚至一直失败，兜底只保留最近 maxTurns 轮原文，保证上下文有界。
        return new SearchSummaryMemoryDTO()
                .setSummary(content)
                .setMemory(messageService.getMaxMemory(remaining));
    }

    // TODO 后续如果需要智能概括标题需要在这里判断时候首轮即可
    @TimeAnnotation(method = "压缩摘要所需时间")
    public void addSummary(Long conversationId, Long userId) {
        RLock lock = redissonClient.getLock(HUI_ANSWER_LOCK_SUMMARY.formatted(conversationId));
        lock.lock();
        try {
            if (!ifSummary) {
                return;
            }
            // 排队任务拿到摘要锁时，会话可能已被删除，直接跳过。
            ChatConversationDO conversation = chatConversationMapper.selectOne(
                    Wrappers.<ChatConversationDO>lambdaQuery()
                            .eq(ChatConversationDO::getId, conversationId)
                            .eq(ChatConversationDO::getUserId, userId));
            if (conversation == null) {
                return;
            }
            List<SearchMemoryDTO> memory = messageService.getMemory(conversationId, userId);
            ChatSummaryDO summary = chatSummaryMapper.selectOne(Wrappers.lambdaQuery(ChatSummaryDO.class)
                    .eq(ChatSummaryDO::getConversationId, conversationId)
                    .eq(ChatSummaryDO::getUserId, userId));
            String content = summary == null ? "" : summary.getContent();
            int untilTurnNo = summary == null ? 0 : summary.getSummaryUntilTurnNo();

            // memory 已由 IMessageService 筛成成功的问答对，这里只排除已经压缩过的轮次。
            List<SearchMemoryDTO> remaining = memory.stream()
                    .filter(each -> each.getTurnNo() > untilTurnNo).toList();

            // 待压缩对话满 3 轮以上时压缩，不满直接返回旧摘要
            int needCount = remaining.size() - maxTurns * 2;
            if (needCount < SUMMARY_BATCH_MESSAGES) {
                return;
            }
            // 正常情况下 end 为 6 表示开始压缩，当上次压缩失败时，依然会再次压缩失败的三轮
            int end = needCount / SUMMARY_BATCH_MESSAGES * SUMMARY_BATCH_MESSAGES;
            List<SearchMemoryDTO> needSummary = remaining.subList(0, end);
            try {
                String currentSummary = toSummary(content, needSummary);
                if (StrUtil.isBlank(currentSummary)) {
                    throw new ServiceException("摘要内容为空");
                }
                int lastTurnNo = needSummary.get(needSummary.size() - 1).getTurnNo();
                ChatSummaryDO update = summary == null
                        ? new ChatSummaryDO().setUserId(userId).setConversationId(conversationId) : summary;
                update.setContent(currentSummary).setSummaryUntilTurnNo(lastTurnNo);
                // 单条 INSERT / UPDATE 同时保存正文和覆盖轮次。
                int affected = summary == null ? chatSummaryMapper.insert(update) : chatSummaryMapper.updateById(update);
                if (affected != 1) {
                    throw new ServiceException("保存摘要失败");
                }
            } catch (Exception e) {
                // 失败时日志提示，下次会话结束继续压缩
                log.warn("会话 {} 摘要压缩失败，保留未压缩历史，异常类型：{}",
                        conversationId, e.getClass().getSimpleName());
            }
        } finally {
            lock.unlock();
        }
    }

    public String toSummary(String previousSummary, List<SearchMemoryDTO> memory) {
        StringBuilder history = new StringBuilder();
        history.append("【已有摘要】\n")
                .append(StrUtil.blankToDefault(previousSummary, "暂无"))
                .append("\n【新增待压缩对话】\n");
        for (SearchMemoryDTO message : memory) {
            history.append(message.getRole()).append("：").append(message.getContent()).append('\n');
        }
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", summaryPrompt);
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", history.toString());
        JsonArray messages = new JsonArray();
        messages.add(system);
        messages.add(user);
        JsonObject body = new JsonObject();
        body.addProperty("model", chatModel);
        body.add("messages", messages);
        body.addProperty("stream", false);
        body.addProperty("temperature", summaryTemperature);
        body.addProperty("max_tokens", summaryMaxTokens);

        if (StrUtil.isBlank(apiKey)) {
            throw new ServiceException("摘要模型密钥未配置");
        }
        Request request = new Request.Builder()
                .url(apiUrl.chatCompletionsUrl())
                .header("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(body.toString(), JSON)).build();
        Call call = httpClient.newCall(request);
        call.timeout().timeout(Math.min(modelWait, 60), TimeUnit.SECONDS);
        try (Response response = call.execute()) {
            ResponseBody responseBody = response.body();
            if (!response.isSuccessful() || responseBody == null) {
                throw new ServiceException("摘要模型调用失败");
            }
            JsonObject json = gson.fromJson(responseBody.string(), JsonObject.class);
            JsonObject choice = json.getAsJsonArray("choices").get(0).getAsJsonObject();
            if (!"stop".equals(choice.get("finish_reason").getAsString())) {
                throw new ServiceException("摘要生成未正常结束");
            }
            return choice.getAsJsonObject("message").get("content").getAsString().trim();
        } catch (Exception e) {
            throw new ServiceException("摘要生成失败");
        }
    }

}
