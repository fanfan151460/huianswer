package com.lhsg.HuiAnswer.rag.knowledge.enums;

public enum ParseStatus {
    PARSE_PARSED("parsed"),
    PARSE_FAILED("failed"),
    PARSE_SUCCESS("success")
    ;
    private final String status;

    ParseStatus(String status) {
        this.status = status;
    }

    public String status() {
        return status;
    }
}
