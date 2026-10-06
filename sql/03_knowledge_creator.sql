-- 先执行 01_knowledge.sql 和 02_admin.sql，再执行本文件。
-- 旧记录的创建者保留 NULL；新记录由后端登录上下文填写。
BEGIN;

ALTER TABLE t_knowledge_base
    ADD COLUMN IF NOT EXISTS created_by BIGINT REFERENCES t_admin(id);
COMMENT ON COLUMN t_knowledge_base.created_by IS '创建者的管理员 ID；历史未记录的数据为空';

ALTER TABLE t_knowledge_document
    ADD COLUMN IF NOT EXISTS created_by BIGINT REFERENCES t_admin(id);
COMMENT ON COLUMN t_knowledge_document.created_by IS '上传者的管理员 ID，不随 MQ 消费或重试变更';

COMMIT;
