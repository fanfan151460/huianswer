package com.lhsg.HuiAnswer.rag.admin;

import com.lhsg.HuiAnswer.rag.admin.dto.UserRespDTO;
import com.lhsg.HuiAnswer.rag.admin.service.IUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserControllerTest {
    private IUserService users;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        users = mock(IUserService.class);
        mvc = MockMvcBuilders.standaloneSetup(new UserController(users)).build();
    }

    @Test
    void publicRegistrationIsNotExposed() throws Exception {
        mvc.perform(post("/api/hui-answer/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"new-user\",\"password\":\"test-password\",\"confirmPassword\":\"test-password\"}"))
                .andExpect(status().isNotFound());

        verifyNoInteractions(users);
    }

    @Test
    void loginStillReturnsToken() throws Exception {
        when(users.login(any())).thenReturn(new UserRespDTO().setToken("test-token"));

        mvc.perform(post("/api/hui-answer/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"test-password\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.token").value("test-token"));

        verify(users).login(argThat(dto -> "admin".equals(dto.getUsername())
                && "test-password".equals(dto.getPassword())));
    }
}
