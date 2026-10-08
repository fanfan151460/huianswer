package com.lhsg.HuiAnswer.rag.knowledge.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lhsg.HuiAnswer.rag.knowledge.dao.entity.KnowledgeChunkDO;
import com.lhsg.HuiAnswer.rag.retrieval.dto.KeyWordChunks;
import com.lhsg.HuiAnswer.rag.retrieval.dto.VectorChunks;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface KnowledgeChunkMapper extends BaseMapper<KnowledgeChunkDO> {
    /**
     * 删除文档时使用，明确执行物理删除，包含已经逻辑删除的片段。
     */
    @Delete("DELETE FROM t_knowledge_chunk WHERE doc_id = #{docId}")
    int deletePhysicallyByDocId(@Param("docId") Long docId);

    /**
     * 删除知识库时，一次清理其全部片段，包含已逻辑删除的片段。
     */
    @Delete("DELETE FROM t_knowledge_chunk WHERE kb_id = #{kbId}")
    int deletePhysicallyByKbId(@Param("kbId") Long kbId);

    @Select("""
            SELECT d.doc_name AS docName, c.content AS content, c.id AS chunkId, c.doc_id,
                   c.embedding <=> #{embedding, typeHandler=com.lhsg.HuiAnswer.rag.knowledge.handler.PgVectorTypeHandler} AS distance
            FROM t_knowledge_chunk c
            JOIN t_knowledge_document d ON d.id = c.doc_id
            WHERE c.kb_id = #{kbId}
            AND c.deleted = 0
            AND d.deleted = 0
            ORDER BY distance ASC
            LIMIT #{topK}
            """)
    List<VectorChunks> vectorSearch(@Param("kbId") Long kbId,
                                    @Param("embedding") float[] embedding,
                                    @Param("topK") int topK);

    /**
     * 关键词检索，keyword 必须是 to_tsquery 能解析的表达式（词之间用 | 或 &），
     * 由 ToKeyWord.toKeyQuestion 生成。
     */
    @Select("""
            WITH q AS (
                SELECT to_tsquery('simple', #{keyword}) AS query
            )
            SELECT d.doc_name AS docName, c.content AS content, c.id AS chunkId, c.doc_id,
                   ts_rank_cd(c.search_vector, q.query) AS keyword_score
            FROM t_knowledge_chunk c
            JOIN t_knowledge_document d ON d.id = c.doc_id
            CROSS JOIN q
            WHERE c.kb_id = #{kbId}
              AND c.deleted = 0
              AND d.deleted = 0
              AND c.search_vector @@ q.query
            ORDER BY keyword_score DESC
            LIMIT #{topK}
            """)
    List<KeyWordChunks> keySearch(@Param("kbId") Long kbId,
                                  @Param("keyword") String keyword,
                                  @Param("topK") int topK);
}
