package com.lhsg.HuiAnswer.rag.chat.build.request;

import com.lhsg.HuiAnswer.rag.retrieval.dto.SubQuestionResultDTO;

import java.util.List;

import static com.lhsg.HuiAnswer.rag.retrieval.intent.enums.IntentEnum.CHAT;

public class IdentifyIndentForModel {

    public static Boolean isRag(List<SubQuestionResultDTO> results) {
        for (SubQuestionResultDTO result : results) {
            if (!result.getIntent().equals(CHAT)) {
                return true;
            }
        }
        return false;
    }
    // TODO isMcp?
}
