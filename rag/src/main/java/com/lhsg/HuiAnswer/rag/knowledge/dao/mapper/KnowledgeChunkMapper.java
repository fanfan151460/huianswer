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
     * 物理删除文档的全部片段，包含已经逻辑删除的片段。
     */
    @Delete("DELETE FROM t_knowledge_chunk WHERE doc_id = #{docId}")
    void deletePhysicallyByDocId(@Param("docId") Long docId);

    /**
     * 物理删除知识库的全部片段，包含已经逻辑删除的片段。
     */
    @Delete("DELETE FROM t_knowledge_chunk WHERE kb_id = #{kbId}")
    void deletePhysicallyByKbId(@Param("kbId") Long kbId);

    /** kbIds 为 null 表示全库；空集合不检索；有 ID 时只检索选中的库。 */
    @Select("""
            <script>
            SELECT d.doc_name AS docName, c.content AS content, c.id AS chunkId, c.doc_id,
                   c.embedding &lt;=> #{embedding, typeHandler=com.lhsg.HuiAnswer.rag.knowledge.handler.PgVectorTypeHandler} AS distance
            FROM t_knowledge_chunk c
            JOIN t_knowledge_document d ON d.id = c.doc_id
            JOIN t_knowledge_base b ON b.id = c.kb_id
            WHERE b.deleted = 0
              AND c.deleted = 0
              AND d.deleted = 0
              AND d.status = 'success'
              AND d.enabled = 0
            <if test="kbIds != null">
                <choose>
                    <when test="kbIds.size() > 0">
                        AND c.kb_id IN
                        <foreach collection="kbIds" item="kbId" open="(" separator="," close=")">
                            #{kbId,jdbcType=BIGINT}
                        </foreach>
                    </when>
                    <otherwise>AND 1 = 0</otherwise>
                </choose>
            </if>
            ORDER BY distance ASC
            LIMIT #{topK}
            </script>
            """)
    List<VectorChunks> vectorSearch(@Param("kbIds") List<Long> kbIds,
                                    @Param("embedding") float[] embedding,
                                    @Param("topK") int topK);

    /** keyword 是 ToKeyWord 生成的 to_tsquery 表达式；范围约定与向量检索相同。 */
    @Select("""
            <script>
            WITH q AS (
                SELECT to_tsquery('simple', #{keyword}) AS query
            )
            SELECT d.doc_name AS docName, c.content AS content, c.id AS chunkId, c.doc_id,
                   ts_rank_cd(c.search_vector, q.query) AS keyword_score
            FROM t_knowledge_chunk c
            JOIN t_knowledge_document d ON d.id = c.doc_id
            JOIN t_knowledge_base b ON b.id = c.kb_id
            CROSS JOIN q
            WHERE b.deleted = 0
              AND c.deleted = 0
              AND d.deleted = 0
              AND d.status = 'success'
              AND d.enabled = 0
              AND c.search_vector @@ q.query
            <if test="kbIds != null">
                <choose>
                    <when test="kbIds.size() > 0">
                        AND c.kb_id IN
                        <foreach collection="kbIds" item="kbId" open="(" separator="," close=")">
                            #{kbId,jdbcType=BIGINT}
                        </foreach>
                    </when>
                    <otherwise>AND 1 = 0</otherwise>
                </choose>
            </if>
            ORDER BY keyword_score DESC
            LIMIT #{topK}
            </script>
            """)
    List<KeyWordChunks> keySearch(@Param("kbIds") List<Long> kbIds,
                                  @Param("keyword") String keyword,
                                  @Param("topK") int topK);
}
