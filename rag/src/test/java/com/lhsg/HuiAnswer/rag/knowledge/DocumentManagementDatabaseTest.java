package com.lhsg.HuiAnswer.rag.knowledge;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.injector.DefaultSqlInjector;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.lhsg.HuiAnswer.framework.exception.ClientException;
import com.lhsg.HuiAnswer.rag.config.DatabaseMetaObjectHandler;
import com.lhsg.HuiAnswer.rag.config.MybatisPlusConfig;
import com.lhsg.HuiAnswer.rag.knowledge.dao.entity.KnowledgeChunkDO;
import com.lhsg.HuiAnswer.rag.knowledge.dao.entity.KnowledgeDocumentDO;
import com.lhsg.HuiAnswer.rag.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.lhsg.HuiAnswer.rag.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.lhsg.HuiAnswer.rag.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.lhsg.HuiAnswer.rag.knowledge.document.parser.DocumentTypeValidator;
import com.lhsg.HuiAnswer.rag.knowledge.document.parser.TikaDocumentParser;
import com.lhsg.HuiAnswer.rag.knowledge.document.storage.LocalDocumentStorage;
import com.lhsg.HuiAnswer.rag.knowledge.dto.resp.KnowledgeChunkRespDTO;
import com.lhsg.HuiAnswer.rag.knowledge.dto.resp.KnowledgeDocumentRespDTO;
import com.lhsg.HuiAnswer.rag.knowledge.mq.producer.DocumentProducer;
import com.lhsg.HuiAnswer.rag.knowledge.service.IKnowledgeChunkService;
import com.lhsg.HuiAnswer.rag.knowledge.service.impl.KnowledgeDocumentServiceImpl;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** 真实 PostgreSQL + MP 分页；临时表在事务回滚后消失，不改动现有业务记录。 */
@EnabledIfEnvironmentVariable(named = "HUIANSWER_TEST_DB_URL",
        matches = "jdbc:postgresql://127\\.0\\.0\\.1:5432/huianswer")
class DocumentManagementDatabaseTest {
    @Test
    void disabledDocumentsDisappearFromBothRetrievalPathsAndCanBeEnabledAgain() throws Exception {
        inDatabase((service, session) -> {
            KnowledgeChunkMapper chunks = session.getMapper(KnowledgeChunkMapper.class);
            float[] query = new float[4096];
            query[0] = 1;
            assertEquals(4, chunks.vectorSearch(List.of(1L), query, 20).size());
            assertEquals(4, chunks.keySearch(List.of(1L), "alpha", 20).size());

            service.updateEnabled(1L, 101L, 1);
            assertEquals(1, service.getDocument(1L, 101L).getEnabled());
            assertEquals(Set.of(1004L), chunks.vectorSearch(List.of(1L), query, 20).stream()
                    .map(each -> each.getChunkId()).collect(Collectors.toSet()));
            assertEquals(Set.of(1004L), chunks.keySearch(List.of(1L), "alpha", 20).stream()
                    .map(each -> each.getChunkId()).collect(Collectors.toSet()));
            assertEquals(2, chunks.vectorSearch(null, query, 20).size());
            assertEquals(2, chunks.keySearch(null, "alpha", 20).size());
            assertTrue(chunks.vectorSearch(List.of(), query, 20).isEmpty());
            // 禁用不删除片段，管理接口仍可查看。
            assertEquals(3, service.pageChunks(1L, 101L, 1, 10).getTotal());

            service.updateEnabled(1L, 101L, 0);
            assertEquals("success", service.getDocument(1L, 101L).getStatus());
            assertEquals(4, chunks.vectorSearch(List.of(1L), query, 20).size());
            assertEquals(4, chunks.keySearch(List.of(1L), "alpha", 20).size());
            assertThrows(ClientException.class, () -> service.updateEnabled(1L, 101L, 2));
        });
    }

    @Test
    void mpPagingReturnsTotalsAndDeletionCleansOnlyTheSelectedDocument() throws Exception {
        inDatabase((service, session) -> {
            IPage<KnowledgeDocumentRespDTO> first = service.listDocument(1L, 1, 1);
            assertEquals(2, first.getTotal());
            assertEquals(1, first.getRecords().size());
            assertEquals(102L, first.getRecords().get(0).getId());
            assertEquals(101L, service.listDocument(1L, 2, 1).getRecords().get(0).getId());

            IPage<KnowledgeChunkRespDTO> page = service.pageChunks(1L, 101L, 2, 2);
            assertEquals(3, page.getTotal());
            assertEquals(2, page.getCurrent());
            assertEquals(1, page.getRecords().size());
            assertEquals(2, page.getRecords().get(0).getChunkIndex());
            String json = new ObjectMapper().findAndRegisterModules().valueToTree(page.getRecords()).toString();
            assertTrue(json.contains("alpha rule three"));
            assertFalse(json.contains("embedding"));
            assertFalse(json.contains("searchText"));
            assertTrue(service.pageChunks(1L, 101L, 10, 2).getRecords().isEmpty());
            assertThrows(ClientException.class, () -> service.pageChunks(1L, 101L, 1, 101));

            service.deleteDocument(1L, 101L);
            assertThrows(ClientException.class, () -> service.getDocument(1L, 101L));
            KnowledgeChunkMapper chunks = session.getMapper(KnowledgeChunkMapper.class);
            assertEquals(0, chunks.selectCount(Wrappers.<KnowledgeChunkDO>lambdaQuery()
                    .eq(KnowledgeChunkDO::getDocId, 101L)));
            assertEquals(1, chunks.selectCount(Wrappers.<KnowledgeChunkDO>lambdaQuery()
                    .eq(KnowledgeChunkDO::getDocId, 102L)));
            assertEquals(1, service.listDocument(1L, 1, 10).getTotal());
            assertNotNull(session.getMapper(KnowledgeDocumentMapper.class).selectById(201L));
            assertThrows(ClientException.class, () -> service.deleteDocument(2L, 102L));
        });
    }

    private void inDatabase(BiConsumer<KnowledgeDocumentServiceImpl, SqlSessionTemplate> assertions)
            throws Exception {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(System.getenv("HUIANSWER_TEST_DB_URL"));
        dataSource.setUser("postgres");
        dataSource.setPassword(System.getenv("HUIANSWER_TEST_DB_PASSWORD"));

        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        factory.setConfiguration(configuration);
        factory.setPlugins(new MybatisPlusConfig().mybatisPlusInterceptor());
        GlobalConfig globalConfig = new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig())
                .setSqlInjector(new DefaultSqlInjector()).setMetaObjectHandler(new DatabaseMetaObjectHandler());
        factory.setGlobalConfig(globalConfig);
        SqlSessionFactory sessionFactory = factory.getObject();
        sessionFactory.getConfiguration().addMapper(KnowledgeBaseMapper.class);
        sessionFactory.getConfiguration().addMapper(KnowledgeDocumentMapper.class);
        sessionFactory.getConfiguration().addMapper(KnowledgeChunkMapper.class);
        SqlSessionTemplate session = new SqlSessionTemplate(sessionFactory);
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.executeWithoutResult(status -> {
            Connection connection = DataSourceUtils.getConnection(dataSource);
            try {
                seed(connection);
                KnowledgeDocumentServiceImpl service = new KnowledgeDocumentServiceImpl(
                        session.getMapper(KnowledgeBaseMapper.class),
                        session.getMapper(KnowledgeDocumentMapper.class),
                        session.getMapper(KnowledgeChunkMapper.class),
                        mock(LocalDocumentStorage.class), mock(TikaDocumentParser.class),
                        mock(DocumentTypeValidator.class), new Gson(),
                        mock(IKnowledgeChunkService.class), transaction, mock(DocumentProducer.class));
                assertions.accept(service, session);
            } catch (SQLException e) {
                throw new IllegalStateException("测试数据初始化失败", e);
            } finally {
                status.setRollbackOnly();
                DataSourceUtils.releaseConnection(connection, dataSource);
            }
        });
    }

    private void seed(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TEMP TABLE t_knowledge_base (
                        id BIGINT PRIMARY KEY, name TEXT, description TEXT, created_by BIGINT,
                        create_time TIMESTAMP DEFAULT now(), update_time TIMESTAMP DEFAULT now(),
                        deleted SMALLINT DEFAULT 0);
                    CREATE TEMP TABLE t_knowledge_document (
                        id BIGINT PRIMARY KEY, kb_id BIGINT, doc_name TEXT, file_path TEXT,
                        metadata JSONB DEFAULT '{}', status TEXT DEFAULT 'success',
                        enabled SMALLINT NOT NULL DEFAULT 0 CHECK (enabled IN (0,1)),
                        created_by BIGINT, create_time TIMESTAMP DEFAULT now(),
                        update_time TIMESTAMP DEFAULT now(), deleted SMALLINT DEFAULT 0);
                    CREATE TEMP TABLE t_knowledge_chunk (
                        id BIGINT PRIMARY KEY, kb_id BIGINT, doc_id BIGINT, chunk_index INTEGER,
                        content TEXT, embedding vector(4096), search_text TEXT,
                        search_vector TSVECTOR GENERATED ALWAYS AS
                            (to_tsvector('simple', coalesce(search_text, ''))) STORED,
                        create_time TIMESTAMP DEFAULT now(), update_time TIMESTAMP DEFAULT now(),
                        deleted SMALLINT DEFAULT 0);
                    INSERT INTO t_knowledge_base(id,name,description) VALUES
                        (1,'test kb one','alpha'),(2,'test kb two','alpha');
                    INSERT INTO t_knowledge_document(id,kb_id,doc_name,file_path) VALUES
                        (101,1,'rules.txt','unused-test-path'),
                        (102,1,'other.txt','unused-test-path'),
                        (201,2,'second-kb.txt','unused-test-path');
                    INSERT INTO t_knowledge_chunk(id,kb_id,doc_id,chunk_index,content,search_text,embedding)
                    VALUES
                        (1001,1,101,0,'alpha rule one','alpha',
                            ('[1,' || repeat('0,',4094) || '0]')::vector(4096)),
                        (1002,1,101,1,'alpha rule two','alpha',
                            ('[1,' || repeat('0,',4094) || '0]')::vector(4096)),
                        (1003,1,101,2,'alpha rule three','alpha',
                            ('[1,' || repeat('0,',4094) || '0]')::vector(4096)),
                        (1004,1,102,0,'alpha second doc','alpha',
                            ('[1,' || repeat('0,',4094) || '0]')::vector(4096)),
                        (2001,2,201,0,'alpha other kb','alpha',
                            ('[1,' || repeat('0,',4094) || '0]')::vector(4096));
                    """);
        }
    }
}
