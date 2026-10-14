package com.lhsg.HuiAnswer.rag.chat.build.message;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class ChatRequest {
    private double temperature;

    private Boolean thinking;

    private Messages messages;

    private String model;

    private int maxTokens;
}
