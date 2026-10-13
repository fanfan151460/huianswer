package com.lhsg.HuiAnswer.rag.retrieval.intent.enums;

/**
 * 意图识别分类(CHAT,KNOWLEDGE,CLARIFY,NO_MATCH)
 */
public enum IntentEnum {
    CHAT("无需查询资料的寒暄、感谢或普通交流。"),

    KNOWLEDGE("需要查询资料，并能确定相关知识库。"),

    CLARIFY("主体或检索范围不明确，需要用户补充信息。"),

    NO_MATCH("需求明确，但候选目录中没有合适的知识库或模型意图路由失败");

    private final String message;

    IntentEnum(String message) {
        this.message = message;
    }
    public String message() {
        return message;
    }
}
