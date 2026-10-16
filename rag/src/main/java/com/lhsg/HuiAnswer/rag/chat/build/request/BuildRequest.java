package com.lhsg.HuiAnswer.rag.chat.build.request;

import cn.hutool.core.util.StrUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.lhsg.HuiAnswer.rag.chat.build.message.ChatRequest;
import com.lhsg.HuiAnswer.rag.chat.build.message.Messages;
import com.lhsg.HuiAnswer.rag.memory.dto.search.SearchMemoryDTO;
import com.lhsg.HuiAnswer.rag.memory.dto.search.SearchSummaryMemoryDTO;
import com.lhsg.HuiAnswer.rag.retrieval.dto.Reference;

import static com.lhsg.HuiAnswer.rag.chat.dto.prompt.System.BASE_PROMPT;

public class BuildRequest {

    public static JsonObject buildRequest(ChatRequest chatRequest) {
        // 构建请求体
        Messages messages = chatRequest.getMessages();
        StringBuilder contextBuilder = new StringBuilder();
        for (Reference reference : messages.getReferences()) {
            contextBuilder.append("【%s】\n%s".formatted(reference.getDocName(), reference.getContent()))
                    .append("\n\n");
        }
        String context = contextBuilder.toString();
        JsonObject body = new JsonObject();
        JsonArray input = getJsonElements(messages.getQuestion(), context, messages.getHistory());
        body.addProperty("model", chatRequest.getModel());
        body.add("messages", input);
        body.addProperty("temperature", chatRequest.getTemperature());
        body.addProperty("max_tokens", chatRequest.getMaxTokens());
        JsonObject thinking = new JsonObject();
        thinking.addProperty("type", Boolean.TRUE.equals(chatRequest.getThinking()) ? "enabled" : "disabled");
        body.add("thinking", thinking);
        body.addProperty("stream", true);
        return body;
    }

    private static JsonArray getJsonElements(String question, String context, SearchSummaryMemoryDTO history) {
        JsonArray input = new JsonArray();
        JsonObject system = new JsonObject();
        JsonObject reference = new JsonObject();
        JsonObject user = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", BASE_PROMPT);
        reference.addProperty("role", "user");
        reference.addProperty("content", "【参考资料】" + context);
        user.addProperty("role", "user");
        user.addProperty("content", question);
        input.add(system);
        if (StrUtil.isNotBlank(history.getSummary())) {
            JsonObject summary = new JsonObject();
            summary.addProperty("role", "user");
            summary.addProperty("content", "【历史摘要，仅用于理解上下文，不作为本次事实或引用依据】" + history.getSummary());
            input.add(summary);
        }
        for (SearchMemoryDTO memoryDTO : history.getMemory()) {
            JsonObject message = new JsonObject();
            message.addProperty("role", memoryDTO.getRole());
            message.addProperty("content", memoryDTO.getContent());
            input.add(message);
        }
        if (StrUtil.isNotBlank(context)) {
            input.add(reference);
        }
        input.add(user);
        return input;
    }
}
