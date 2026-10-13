-- 会话只按用户归属；每个子问题的知识库范围由意图路由决定。
-- 仅移除废弃字段，不删除会话、消息或摘要，不使用 CASCADE。
BEGIN;
ALTER TABLE t_chat_conversation DROP COLUMN IF EXISTS kb_id;
COMMENT ON TABLE t_chat_conversation IS '用户会话：按用户归属，不绑定知识库';
COMMIT;
