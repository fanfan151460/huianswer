package com.lhsg.HuiAnswer.rag.knowledge;

import com.lhsg.HuiAnswer.framework.exception.ClientException;
import com.lhsg.HuiAnswer.rag.admin.user.UserDTO;
import com.lhsg.HuiAnswer.rag.admin.user.UsersContext;
import com.lhsg.HuiAnswer.rag.knowledge.dao.entity.KnowledgeBaseDO;
import com.lhsg.HuiAnswer.rag.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.lhsg.HuiAnswer.rag.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.lhsg.HuiAnswer.rag.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.lhsg.HuiAnswer.rag.knowledge.dto.req.CreateBaseReqDTO;
import com.lhsg.HuiAnswer.rag.knowledge.dto.req.UpdateBaseReqDTO;
import com.lhsg.HuiAnswer.rag.knowledge.service.impl.KnowledgeBaseServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 创建者只取登录上下文，修改名称不改变创建者。 */
class KnowledgeCreatorTest {
    private KnowledgeBaseMapper bases;
    private KnowledgeBaseServiceImpl service;

    @BeforeEach
    void setUp() {
        UsersContext.removeUserDTO();
        bases = mock(KnowledgeBaseMapper.class);
        service = new KnowledgeBaseServiceImpl(bases,
                mock(KnowledgeDocumentMapper.class), mock(KnowledgeChunkMapper.class));
    }

    @AfterEach
    void clearUser() {
        UsersContext.removeUserDTO();
    }

    @Test
    void createShouldStoreAndReturnCurrentAdminId() {
        UserDTO user = new UserDTO();
        user.setId(1L);
        UsersContext.setUserDTO(user);
        when(bases.insert(any(KnowledgeBaseDO.class))).thenAnswer(call -> {
            KnowledgeBaseDO record = call.getArgument(0);
            record.setId(7L);
            return 1;
        });

        var response = service.createBase(new CreateBaseReqDTO().setName("资料库"));

        ArgumentCaptor<KnowledgeBaseDO> saved = ArgumentCaptor.forClass(KnowledgeBaseDO.class);
        verify(bases).insert(saved.capture());
        assertEquals(1L, saved.getValue().getCreatedBy());
        assertEquals(1L, response.getCreatedBy());
    }

    @Test
    void createWithoutLoginShouldNotInsert() {
        assertThrows(ClientException.class,
                () -> service.createBase(new CreateBaseReqDTO().setName("资料库")));
        verifyNoInteractions(bases);
    }

    @Test
    void updateShouldKeepOriginalCreator() {
        UserDTO user = new UserDTO();
        user.setId(2L);
        UsersContext.setUserDTO(user);
        when(bases.selectById(7L)).thenReturn(
                new KnowledgeBaseDO().setId(7L).setName("旧名称").setCreatedBy(1L));
        when(bases.updateById(any(KnowledgeBaseDO.class))).thenReturn(1);

        var response = service.updateBase(7L, new UpdateBaseReqDTO().setName("新名称"));

        assertEquals(1L, response.getCreatedBy());
    }
}
