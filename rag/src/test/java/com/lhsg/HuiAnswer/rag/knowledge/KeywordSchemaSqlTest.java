package com.lhsg.HuiAnswer.rag.knowledge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** 直接执行仓库中的 SQL，在独立 schema 内验证后回滚，不改现有数据。 */
@EnabledIfEnvironmentVariable(named = "HUIANSWER_TEST_DB_URL",
        matches = "jdbc:postgresql://127\\.0\\.0\\.1:5432/huianswer")
class KeywordSchemaSqlTest {
    @Test
    void freshDatabaseScriptsCreateWorkingKeywordSearch() throws Exception {
        try (Connection connection = openSchema()) {
            try {
                executeScript(connection, sqlDirectory().resolve("01_knowledge.sql"));
                // 单独确认基础建表包含关键词字段，不能靠后续迁移掩盖遗漏。
                assertKeywordSchema(connection);
                try (Stream<Path> files = Files.list(sqlDirectory())) {
                    for (Path script : files.filter(each -> each.getFileName().toString()
                                    .matches("\\d{2}_.*\\.sql"))
                            .sorted().toList()) {
                        if (!script.getFileName().toString().equals("01_knowledge.sql")) {
                            executeScript(connection, script);
                        }
                    }
                }
                try (Statement statement = connection.createStatement()) {
                    statement.execute("""
                            INSERT INTO t_knowledge_base(id, name) VALUES (101, 'sql test kb');
                            INSERT INTO t_knowledge_document(id,kb_id,doc_name,file_path,status)
                            VALUES (102,101,'rules.txt','unused-test-path','success');
                            INSERT INTO t_knowledge_chunk
                                (id,kb_id,doc_id,chunk_index,content,embedding,search_text)
                            VALUES (103,101,102,0,'保留原文',
                                ('[1,' || repeat('0,',4094) || '0]')::vector(4096),
                                '会议室 预约 会议室');
                            """);
                }
                assertGeneratedSearchUpdates(connection);
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void existingDatabaseMigrationCanRunTwiceWithoutLosingContent() throws Exception {
        try (Connection connection = openSchema()) {
            try {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("""
                            CREATE TABLE t_knowledge_chunk (
                                id BIGINT PRIMARY KEY, content TEXT NOT NULL);
                            INSERT INTO t_knowledge_chunk(id,content) VALUES (103,'保留原文');
                            """);
                }
                Path migration = sqlDirectory().resolve("10_chunk_keyword_search.sql");
                executeScript(connection, migration);
                executeScript(connection, migration);
                assertKeywordSchema(connection);
                try (Statement statement = connection.createStatement();
                     ResultSet result = statement.executeQuery(
                             "SELECT content,search_text FROM t_knowledge_chunk WHERE id=103")) {
                    assertTrue(result.next());
                    assertEquals("保留原文", result.getString("content"));
                    assertNull(result.getString("search_text"));
                }
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate(
                            "UPDATE t_knowledge_chunk SET search_text='会议室 预约 会议室' WHERE id=103");
                }
                assertGeneratedSearchUpdates(connection);
            } finally {
                connection.rollback();
            }
        }
    }

    private Connection openSchema() throws Exception {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(System.getenv("HUIANSWER_TEST_DB_URL"));
        dataSource.setUser("postgres");
        dataSource.setPassword(System.getenv("HUIANSWER_TEST_DB_PASSWORD"));
        Connection connection = dataSource.getConnection();
        connection.setAutoCommit(false);
        String schema = "keyword_sql_" + UUID.randomUUID().toString().replace("-", "");
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            statement.execute("SET LOCAL search_path TO " + schema + ", public");
            return connection;
        } catch (Exception e) {
            connection.close();
            throw e;
        }
    }

    private Path sqlDirectory() {
        return Files.isDirectory(Path.of("sql")) ? Path.of("sql") : Path.of("..", "sql");
    }

    private void executeScript(Connection connection, Path script) throws Exception {
        // 仅移除脚本最外层事务指令，让本次验证统一回滚 schema、表及测试记录。
        String sql = Files.readString(script, StandardCharsets.UTF_8)
                .replaceAll("(?m)^[\\t ]*(BEGIN|COMMIT);[\\t ]*\\r?$", "");
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void assertKeywordSchema(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet columns = statement.executeQuery("""
                     SELECT column_name,data_type,is_generated
                     FROM information_schema.columns
                     WHERE table_schema=current_schema() AND table_name='t_knowledge_chunk'
                       AND column_name IN ('search_text','search_vector')
                     ORDER BY column_name
                     """)) {
            assertTrue(columns.next(), "建表/迁移脚本缺少 search_text");
            assertEquals("search_text", columns.getString("column_name"));
            assertEquals("text", columns.getString("data_type"));
            assertEquals("NEVER", columns.getString("is_generated"));
            assertTrue(columns.next(), "建表/迁移脚本缺少 search_vector");
            assertEquals("search_vector", columns.getString("column_name"));
            assertEquals("tsvector", columns.getString("data_type"));
            assertEquals("ALWAYS", columns.getString("is_generated"));
        }
        try (Statement statement = connection.createStatement();
             ResultSet index = statement.executeQuery("""
                     SELECT am.amname
                     FROM pg_index ix
                     JOIN pg_class i ON i.oid=ix.indexrelid
                     JOIN pg_am am ON am.oid=i.relam
                     WHERE ix.indrelid='t_knowledge_chunk'::regclass
                       AND i.relname='idx_chunk_search_vector'
                     """)) {
            assertTrue(index.next(), "脚本缺少关键词索引");
            assertEquals("gin", index.getString(1));
        }
    }

    private void assertGeneratedSearchUpdates(Connection connection) throws Exception {
        assertMatches(connection, "会议室|预约", 1);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE t_knowledge_chunk SET search_text='餐厅' WHERE id=103");
        }
        assertMatches(connection, "会议室|预约", 0);
        assertMatches(connection, "餐厅", 1);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE t_knowledge_chunk SET search_text=NULL WHERE id=103");
        }
        assertMatches(connection, "餐厅", 0);
    }

    private void assertMatches(Connection connection, String query, int count) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT count(*) FROM t_knowledge_chunk WHERE search_vector @@ to_tsquery('simple',?)")) {
            statement.setString(1, query);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals(count, result.getInt(1));
            }
        }
    }
}