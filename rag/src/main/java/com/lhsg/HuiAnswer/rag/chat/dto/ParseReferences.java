package com.lhsg.HuiAnswer.rag.chat.dto;

import com.google.gson.JsonObject;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class ParseReferences {
    private JsonObject body;

    private List<NeedChunksResp> needChunks;
}
