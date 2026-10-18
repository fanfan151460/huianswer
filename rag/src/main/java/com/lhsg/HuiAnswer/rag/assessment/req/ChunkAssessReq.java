package com.lhsg.HuiAnswer.rag.assessment.req;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

@Data
@Accessors(chain = true)
public class ChunkAssessReq {
    private String question;

    private List<Long> dbIds;

    private String Choose;
}
