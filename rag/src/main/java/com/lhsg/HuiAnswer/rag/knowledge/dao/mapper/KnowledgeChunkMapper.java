package com.lhsg.HuiAnswer.rag.knowledge.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lhsg.HuiAnswer.rag.knowledge.dao.entity.KnowledgeChunkDO;
import com.lhsg.HuiAnswer.rag.retrieval.dto.References;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface KnowledgeChunkMapper extends BaseMapper<KnowledgeChunkDO> {
    /** 删除文档时使用，明确执行物理删除，包含已经逻辑删除的片段。 */
    @Delete("DELETE FROM t_knowledge_chunk WHERE doc_id = #{docId}")
    int deletePhysicallyByDocId(@Param("docId") Long docId);

    /** 删除知识库时，一次清理其全部片段，包含已逻辑删除的片段。 */
    @Delete("DELETE FROM t_knowledge_chunk WHERE kb_id = #{kbId}")
    int deletePhysicallyByKbId(@Param("kbId") Long kbId);

    @Select("""
    SELECT d.doc_name AS docName, c.content AS content, c.id AS chunkId, c.doc_id
    FROM t_knowledge_chunk c
    JOIN t_knowledge_document d ON d.id = c.doc_id
    WHERE c.kb_id = #{kbId}
    AND c.deleted = 0
    AND d.deleted = 0
    ORDER BY c.embedding <=> #{embedding, typeHandler=com.lhsg.HuiAnswer.rag.knowledge.dao.handler.PgVectorTypeHandler}
    LIMIT #{topK}
        """)
List<References> search(@Param("kbId") Long kbId,
                        @Param("embedding") float[] embedding,
                        @Param("topK") int topK);
}
