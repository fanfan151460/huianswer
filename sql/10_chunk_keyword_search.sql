-- 已有知识库执行；补齐关键词检索字段，不删除或覆盖现有片段。
-- search_text 由 Java/HanLP 生成，不能直接拿未分词的中文 content 代替。
BEGIN;
ALTER TABLE t_knowledge_chunk
    ADD COLUMN IF NOT EXISTS search_text TEXT;
ALTER TABLE t_knowledge_chunk
    ADD COLUMN IF NOT EXISTS search_vector TSVECTOR GENERATED ALWAYS AS (
        to_tsvector('simple', coalesce(search_text, ''))
    ) STORED;
COMMENT ON COLUMN t_knowledge_chunk.search_text IS 'HanLP 分词后的文本，空格分隔，保留词序和重复词';
COMMENT ON COLUMN t_knowledge_chunk.search_vector IS '基于 search_text 自动生成的全文检索向量，Java 不直接写入';
CREATE INDEX IF NOT EXISTS idx_chunk_search_vector
    ON t_knowledge_chunk USING GIN (search_vector);
COMMIT;