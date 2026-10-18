package com.lhsg.HuiAnswer.rag.knowledge.enums;

public enum SplitStrategy {
    OVERLAP("滑动重叠分块策略"),
    RECURSIVE("递归分块策略");

    private final String message;

    SplitStrategy(String message) {
        this.message = message;
    }

    public String message() {
        return this.message;
    }
}
