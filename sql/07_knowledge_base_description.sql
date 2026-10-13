-- 仅增加范围描述，不猜测旧知识库的内容，旧数据保留为空字符串。
BEGIN;
ALTER TABLE t_knowledge_base
    ADD COLUMN IF NOT EXISTS description VARCHAR(1000) NOT NULL DEFAULT '';
COMMENT ON COLUMN t_knowledge_base.description IS '知识库业务范围，供模型意图路由使用';
COMMIT;
