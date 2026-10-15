package com.lhsg.HuiAnswer.rag.retrieval.search;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.lhsg.HuiAnswer.rag.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.lhsg.HuiAnswer.rag.knowledge.embedding.EmbeddingChunks;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeChunkScopeTest {
    @Test
    void selectedKbIdsAreBoundForBothRetrievalQueries() throws Exception {
        for (String method : List.of("vectorSearch", "keySearch")) {
            BoundSql sql = boundSql(method, List.of(2L, 5L));
            assertTrue(sql.getSql().contains("c.kb_id IN"));
            assertEquals(2, sql.getParameterMappings().stream()
                    .filter(parameter -> parameter.getProperty().startsWith("__frch_kbId_")).count());
            assertTrue(sql.getSql().contains("d.status = 'success'"));
            assertTrue(sql.getSql().contains("d.enabled = 0"));
        }
        assertTrue(boundSql("vectorSearch", List.of(2L)).getSql().contains("c.embedding <=>"));
    }

    @Test
    void nullScopeIsGlobalButEmptyScopeCannotBecomeGlobal() throws Exception {
        for (String method : List.of("vectorSearch", "keySearch")) {
            String global = boundSql(method, null).getSql();
            assertFalse(global.contains("c.kb_id IN"));
            assertFalse(global.contains("AND 1 = 0"));
            assertTrue(boundSql(method, List.of()).getSql().contains("AND 1 = 0"));
        }
    }

    @Test
    void emptyScopeSkipsEmbeddingAndDatabaseCalls() {
        KnowledgeChunkMapper mapper = mock(KnowledgeChunkMapper.class);
        EmbeddingChunks embedding = mock(EmbeddingChunks.class);
        SearchChunks search = new SearchChunks(mapper, embedding);
        assertTrue(search.mixSearch(List.of(), "问题").isEmpty());
        assertTrue(search.vectorSearch(List.of(), "问题").isEmpty());
        assertTrue(search.searchKeyWord(List.of(), "问题").isEmpty());
        verifyNoInteractions(mapper, embedding);
    }

    private BoundSql boundSql(String methodName, List<Long> ids) throws Exception {
        Method method = methodName.equals("vectorSearch")
                ? KnowledgeChunkMapper.class.getMethod(methodName, List.class, float[].class, int.class)
                : KnowledgeChunkMapper.class.getMethod(methodName, List.class, String.class, int.class);
        String script = String.join("\n", method.getAnnotation(Select.class).value());
        SqlSource source = new XMLLanguageDriver().createSqlSource(new MybatisConfiguration(), script, Map.class);
        Map<String, Object> params = new HashMap<>();
        params.put("kbIds", ids);
        params.put("embedding", new float[]{0.1f, 0.2f});
        params.put("keyword", "预约|会议室");
        params.put("topK", 20);
        return source.getBoundSql(params);
    }
}
