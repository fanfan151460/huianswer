package com.lhsg.HuiAnswer.rag.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class NeedChunksResp {
    private int num;

    private long docId;

    private String docName;

    private long chunkId;
}
