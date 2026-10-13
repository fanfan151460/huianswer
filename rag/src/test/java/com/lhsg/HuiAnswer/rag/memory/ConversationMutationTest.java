package com.lhsg.HuiAnswer.rag.memory;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.injector.DefaultSqlInjector;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.google.gson.Gson;
import com.lhsg.HuiAnswer.framework.exception.ClientException;
import com.lhsg.HuiAnswer.framework.web.GlobalExceptionHandler;
import com.lhsg.HuiAnswer.rag.admin.user.UserDTO;
import com.lhsg.HuiAnswer.rag.admin.user.UsersContext;
import com.lhsg.HuiAnswer.rag.config.ApiUrl;
import com.lhsg.HuiAnswer.rag.config.DatabaseMetaObjectHandler;
import com.lhsg.HuiAnswer.rag.memory.controller.ConversationController;
import com.lhsg.HuiAnswer.rag.memory.dao.entity.ChatMessageDO;
import com.lhsg.HuiAnswer.rag.memory.dao.entity.ChatSummaryDO;
import com.lhsg.HuiAnswer.rag.memory.dao.mapper.ChatConversationMapper;
import com.lhsg.HuiAnswer.rag.memory.dao.mapper.ChatMessageMapper;
import com.lhsg.HuiAnswer.rag.memory.dao.mapper.ChatSummaryMapper;
import com.lhsg.HuiAnswer.rag.memory.service.IMessageService;
import com.lhsg.HuiAnswer.rag.memory.service.impl.ConversationImpl;
import com.lhsg.HuiAnswer.rag.memory.service.impl.SummaryServiceImpl;
import okhttp3.OkHttpClient;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.postgresql.ds.PGSimpleDataSource;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;
import java.util.function.BiConsumer;

import static com.lhsg.HuiAnswer.rag.constant.constant.*;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ConversationMutationTest {
    private final RedissonClient redis = mock(RedissonClient.class);
    private final RLock conversationLock = mock(RLock.class);
    private final RLock summaryLock = mock(RLock.class);

    @BeforeEach
    void setUp() {
        UserDTO user = new UserDTO();
        user.setId(1L);
        UsersContext.setUserDTO(user);
        when(redis.getLock(startsWith(HUI_ANSWER_LOCK_CONVERSATION.formatted(""))))
                .thenReturn(conversationLock);
        when(redis.getLock(startsWith(HUI_ANSWER_LOCK_SUMMARY.formatted(""))))
                .thenReturn(summaryLock);
        when(conversationLock.tryLock()).thenReturn(true);
        when(summaryLock.tryLock()).thenReturn(true);
    }

    @AfterEach
    void clearContext() {
        UsersContext.removeUserDTO();
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "HUIANSWER_TEST_DB_URL",
            matches = "jdbc:postgresql://127\\.0\\.0\\.1:5432/huianswer")
    void endpointsRenameAndDeleteOnlyOwnedConversations() throws Exception {
        inDatabase(false, (service, session) -> {
            ChatConversationMapper conversations = session.getMapper(ChatConversationMapper.class);
            ChatMessageMapper messages = session.getMapper(ChatMessageMapper.class);
            ChatSummaryMapper summaries = session.getMapper(ChatSummaryMapper.class);
            MockMvc mvc = MockMvcBuilders.standaloneSetup(new ConversationController(service))
                    .setControllerAdvice(new GlobalExceptionHandler()).build();

            response(mvc, put("/api/hui-answer/conversation/101")
                    .contentType("application/json").content("{\"title\":\"  renamed  \"}"), true);
            assertEquals("renamed", conversations.selectById(101L).getTitle());
            response(mvc, put("/api/hui-answer/conversation/101")
                    .contentType("application/json").content("{\"title\":\" \"}"), false);
            response(mvc, put("/api/hui-answer/conversation/101")
                    .contentType("application/json").content(new Gson().toJson(
                            java.util.Map.of("title", "a".repeat(129)))), false);
            response(mvc, put("/api/hui-answer/conversation/102")
                    .contentType("application/json").content("{\"title\":\"forbidden\"}"), false);
            response(mvc, delete("/api/hui-answer/conversation/102"), false);
            assertEquals("other user", conversations.selectById(102L).getTitle());

            response(mvc, delete("/api/hui-answer/conversation/101"), true);
            assertNull(conversations.selectById(101L));
            assertEquals(0, messages.selectCount(Wrappers.<ChatMessageDO>lambdaQuery()
                    .eq(ChatMessageDO::getConversationId, 101L)));
            assertEquals(0, summaries.selectCount(Wrappers.<ChatSummaryDO>lambdaQuery()
                    .eq(ChatSummaryDO::getConversationId, 101L)));
            assertEquals(1, messages.selectCount(Wrappers.<ChatMessageDO>lambdaQuery()
                    .eq(ChatMessageDO::getConversationId, 102L)));
            assertEquals(1, summaries.selectCount(Wrappers.<ChatSummaryDO>lambdaQuery()
                    .eq(ChatSummaryDO::getConversationId, 102L)));
            assertTrue(service.listConversation().isEmpty());
            response(mvc, put("/api/hui-answer/conversation/101")
                    .contentType("application/json").content("{\"title\":\"restore?\"}"), false);
        });
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "HUIANSWER_TEST_DB_URL",
            matches = "jdbc:postgresql://127\\.0\\.0\\.1:5432/huianswer")
    void childDeletionFailureRollsBackConversationAndMessages() throws Exception {
        inDatabase(true, (service, session) -> {
            assertThrows(IllegalStateException.class, () -> service.deleteConversation(101L));
            assertNotNull(session.getMapper(ChatConversationMapper.class).selectById(101L));
            assertEquals(2, session.getMapper(ChatMessageMapper.class).selectCount(
                    Wrappers.<ChatMessageDO>lambdaQuery().eq(ChatMessageDO::getConversationId, 101L)));
            assertEquals(1, session.getMapper(ChatSummaryMapper.class).selectCount(
                    Wrappers.<ChatSummaryDO>lambdaQuery().eq(ChatSummaryDO::getConversationId, 101L)));
            verify(conversationLock).unlock();
            verify(summaryLock).unlock();
        });
    }

    @Test
    void busyAnswerOrSummaryRejectsDeletionBeforeDatabaseWrites() {
        ChatConversationMapper conversations = mock(ChatConversationMapper.class);
        ChatMessageMapper messages = mock(ChatMessageMapper.class);
        ChatSummaryMapper summaries = mock(ChatSummaryMapper.class);
        TransactionTemplate transaction = mock(TransactionTemplate.class);
        ConversationImpl service = new ConversationImpl(conversations, messages, summaries, transaction, redis);

        when(conversationLock.tryLock()).thenReturn(false);
        assertThrows(ClientException.class, () -> service.deleteConversation(101L));
        verify(conversationLock, never()).unlock();
        when(conversationLock.tryLock()).thenReturn(true);
        when(summaryLock.tryLock()).thenReturn(false);
        assertThrows(ClientException.class, () -> service.deleteConversation(101L));
        verify(conversationLock).unlock();
        verify(summaryLock, never()).unlock();
        verifyNoInteractions(conversations, messages, summaries, transaction);
    }

    @Test
    void queuedSummarySkipsDeletedConversation() {
        ChatConversationMapper conversations = mock(ChatConversationMapper.class);
        ChatSummaryMapper summaries = mock(ChatSummaryMapper.class);
        IMessageService messages = mock(IMessageService.class);
        OkHttpClient http = mock(OkHttpClient.class);
        SummaryServiceImpl service = new SummaryServiceImpl(
                summaries, conversations, http, new Gson(), messages, redis, mock(ApiUrl.class));
        ReflectionTestUtils.setField(service, "ifSummary", true);

        service.addSummary(101L, 1L);

        verify(conversations).selectOne(any(Wrapper.class));
        verifyNoInteractions(messages, summaries, http);
        verify(summaryLock).unlock();
    }

    private void response(MockMvc mvc, RequestBuilder request, boolean success) {
        try {
            mvc.perform(request).andExpect(status().isOk())
                    .andExpect(jsonPath("$.code", success ? is("0") : not("0")));
        } catch (Exception e) {
            throw new IllegalStateException("会话接口验证失败", e);
        }
    }

    private void inDatabase(boolean failSummaryDelete,
                            BiConsumer<ConversationImpl, SqlSessionTemplate> assertions) throws Exception {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(System.getenv("HUIANSWER_TEST_DB_URL"));
        dataSource.setUser("postgres");
        dataSource.setPassword(System.getenv("HUIANSWER_TEST_DB_PASSWORD"));
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        factory.setConfiguration(configuration);
        factory.setGlobalConfig(new GlobalConfig().setDbConfig(new GlobalConfig.DbConfig())
                .setSqlInjector(new DefaultSqlInjector()).setMetaObjectHandler(new DatabaseMetaObjectHandler()));
        SqlSessionFactory sessionFactory = factory.getObject();
        sessionFactory.getConfiguration().addMapper(ChatConversationMapper.class);
        sessionFactory.getConfiguration().addMapper(ChatMessageMapper.class);
        sessionFactory.getConfiguration().addMapper(ChatSummaryMapper.class);
        SqlSessionTemplate session = new SqlSessionTemplate(sessionFactory);
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
        TransactionTemplate fixtureTransaction = new TransactionTemplate(manager);
        TransactionTemplate serviceTransaction = new TransactionTemplate(manager);
        // 测试外层负责最终清理；服务事务用 savepoint 验证失败回滚后仍能读取原始记录。
        serviceTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        fixtureTransaction.executeWithoutResult(status -> {
            Connection connection = DataSourceUtils.getConnection(dataSource);
            try {
                seed(connection);
                ChatSummaryMapper summaries = session.getMapper(ChatSummaryMapper.class);
                if (failSummaryDelete) {
                    summaries = mock(ChatSummaryMapper.class);
                    when(summaries.delete(any(Wrapper.class)))
                            .thenThrow(new IllegalStateException("模拟摘要删除失败"));
                }
                ConversationImpl service = new ConversationImpl(
                        session.getMapper(ChatConversationMapper.class), session.getMapper(ChatMessageMapper.class),
                        summaries, serviceTransaction, redis);
                assertions.accept(service, session);
            } catch (Exception e) {
                throw new IllegalStateException("会话数据库验证失败", e);
            } finally {
                status.setRollbackOnly();
                DataSourceUtils.releaseConnection(connection, dataSource);
            }
        });
    }

    private void seed(Connection connection) throws Exception {
        String schema = "conversation_test_" + UUID.randomUUID().toString().replace("-", "");
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            statement.execute("SET LOCAL search_path TO " + schema + ", public");
            Path sqlDirectory = Files.isDirectory(Path.of("sql")) ? Path.of("sql") : Path.of("..", "sql");
            for (String script : new String[]{"04_chat_memory.sql", "05_chat_summary.sql"}) {
                String sql = Files.readString(sqlDirectory.resolve(script), StandardCharsets.UTF_8)
                        .replaceAll("(?m)^[\\t ]*(BEGIN|COMMIT);[\\t ]*\\r?$", "");
                statement.execute(sql);
            }
            statement.execute("""
                    INSERT INTO t_chat_conversation(id,user_id,title) VALUES
                        (101,1,'original'),(102,2,'other user');
                    INSERT INTO t_chat_message(id,conversation_id,turn_no,role,content) VALUES
                        (1001,101,1,'user','question'),(1002,101,1,'assistant','answer'),
                        (2001,102,1,'user','other question');
                    INSERT INTO t_chat_summary(id,conversation_id,user_id,content) VALUES
                        (3001,101,1,'summary'),(3002,102,2,'other summary');
                    """);
        }
    }
}