package com.lhsg.HuiAnswer.rag.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lhsg.HuiAnswer.rag.knowledge.dao.mapper.KnowledgeBaseMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 通过真实 Spring MVC 和 PostgreSQL 验证请求绑定、校验、查询与返回结构。 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "HUIANSWER_DB_PASSWORD", matches = ".+")
@Transactional
class KnowledgeBaseApiIntegrationTest {
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.lhsg.HuiAnswer.rag.knowledge.mq.producer.DocumentProducer producer;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private org.springframework.data.redis.core.StringRedisTemplate redis;
    private static final String BASE = "/api/knowledge-base";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private KnowledgeBaseMapper baseMapper;

    @Test
    void shouldCreateAndQueryKnowledgeBaseWithLatestFirst() throws Exception {
        JsonNode first = createBase("  员工制度  ");
        JsonNode second = createBase("产品手册");
        long firstId = first.path("id").asLong();
        long secondId = second.path("id").asLong();
        assertTrue(firstId > 0);
        assertEquals("员工制度", first.path("name").asText());
        assertFalse(first.has("deleted"));
        assertTrue(first.hasNonNull("createTime"));
        assertTrue(first.hasNonNull("updateTime"));

        MvcResult detailResult = mvc.perform(get(BASE + "/" + firstId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.name").value("员工制度")).andReturn();
        assertEquals(firstId, responseData(detailResult).path("id").asLong());
        assertEquals("员工制度", baseMapper.selectById(firstId).getName());

        List<Long> ids = listIds();
        assertTrue(ids.contains(firstId));
        assertTrue(ids.contains(secondId));
        assertTrue(ids.indexOf(secondId) < ids.indexOf(firstId));
    }

    @Test
    void shouldRejectBlankMissingAndOversizedNameWithoutInserting() throws Exception {
        Long before = baseMapper.selectCount(null);
        for (String name : List.of("", "   ", "\t\n", "a".repeat(129))) {
            mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("name", name))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("A000001"));
        }
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("A000001"));
        assertEquals(before, baseMapper.selectCount(null));
    }

    @Test
    void shouldHideDeletedKnowledgeBaseAndRejectInvalidId() throws Exception {
        long id = createBase("逻辑删除测试库").path("id").asLong();
        assertEquals(1, baseMapper.deleteById(id));
        mvc.perform(get(BASE + "/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("A000001"))
                .andExpect(jsonPath("$.message").value("知识库不存在"));
        assertFalse(listIds().contains(id));
        for (long invalid : new long[] {0, -1, Long.MAX_VALUE}) {
            mvc.perform(get(BASE + "/" + invalid)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("A000001"));
        }
    }

    @Test
    void shouldReturnClientErrorForMalformedJsonAndNonNumericId() throws Exception {
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{broken"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("A000001"));
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("A000001"));
        mvc.perform(get(BASE + "/not-a-number")).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("A000001"));
    }

    private JsonNode createBase(String name) throws Exception {
        MvcResult result = mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", name))))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.success").value(true)).andReturn();
        return responseData(result);
    }

    private List<Long> listIds() throws Exception {
        MvcResult result = mvc.perform(get(BASE)).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data").isArray()).andReturn();
        return StreamSupport.stream(responseData(result).spliterator(), false)
                .map(node -> node.path("id").asLong()).toList();
    }

    private JsonNode responseData(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray()).path("data");
    }
}
