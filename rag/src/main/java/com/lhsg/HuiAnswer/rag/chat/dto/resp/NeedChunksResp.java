package com.lhsg.HuiAnswer.rag.chat.dto.resp;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 一条引用出处；只记来源本身，编号一类的展示格式由消费方自行生成。 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class NeedChunksResp {
    private long docId;

    private String docName;

    private long chunkId;
}
