package com.lhsg.HuiAnswer.rag.knowledge.enums;

public enum FileStatus {
    FILE_FAILED("failed"),
    FILE_SUCCESS("success"),
    FILE_PENDING("pending");

    private final String status;
    FileStatus(String status) {
        this.status = status;
    }
    public String status() {
        return status;
    }
}