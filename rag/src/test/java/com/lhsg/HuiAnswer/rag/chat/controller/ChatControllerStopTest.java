package com.lhsg.HuiAnswer.rag.chat.controller;

import com.lhsg.HuiAnswer.framework.web.GlobalExceptionHandler;
import com.lhsg.HuiAnswer.rag.chat.service.IChatService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ChatControllerStopTest {
    @Test
    void stopRouteBindsGenerationIdAndRejectsMissingOrShortIds() throws Exception {
        IChatService service = mock(IChatService.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ChatController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        String id = UUID.randomUUID().toString();

        mvc.perform(post("/api/hui-answer/conversation/1/stop")
                        .contentType("application/json")
                        .content("{\"generationId\":\"" + id + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("0"));
        verify(service).stop(1L, id);
        mvc.perform(post("/api/hui-answer/conversation/1/stop")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code", not("0")));
        mvc.perform(post("/api/hui-answer/conversation/1/stop")
                        .contentType("application/json").content("{\"generationId\":\"bad\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code", not("0")));
        verifyNoMoreInteractions(service);
    }
}