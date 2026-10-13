-- 历史兼容迁移：已有旧字段时放宽可空；新表已经不包含该字段时跳过。
-- 08_conversation_without_kb.sql 会移除旧字段，不再按知识库组织会话。
BEGIN;
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 't_chat_conversation'
          AND column_name = 'kb_id'
    ) THEN
        ALTER TABLE t_chat_conversation ALTER COLUMN kb_id DROP NOT NULL;
    END IF;
END $$;
COMMIT;
