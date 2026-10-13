-- 现有 huianswer 数据库执行本脚本；0 启用，1 禁用，不复用处理状态 status。
BEGIN;
ALTER TABLE t_knowledge_document
    ADD COLUMN IF NOT EXISTS enabled SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE t_knowledge_document ALTER COLUMN enabled SET DEFAULT 0;
UPDATE t_knowledge_document SET enabled = 0 WHERE enabled IS NULL;
ALTER TABLE t_knowledge_document ALTER COLUMN enabled SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 't_knowledge_document'::regclass
          AND conname = 'ck_knowledge_document_enabled'
    ) THEN
        ALTER TABLE t_knowledge_document
            ADD CONSTRAINT ck_knowledge_document_enabled CHECK (enabled IN (0, 1));
    END IF;
END $$;
COMMENT ON COLUMN t_knowledge_document.enabled IS '0 启用，1 禁用；只控制检索，不改变解析状态或删除片段';
COMMIT;
