package com.lhsg.HuiAnswer.rag.knowledge.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lhsg.HuiAnswer.rag.knowledge.dao.entity.KnowledgeChunkDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface KnowledgeChunkMapper extends BaseMapper<KnowledgeChunkDO> {
    /** 删除文档时使用，明确执行物理删除，包含已经逻辑删除的片段。 */
    @Delete("DELETE FROM t_knowledge_chunk WHERE doc_id = #{docId}")
    int deletePhysicallyByDocId(@Param("docId") Long docId);

    /** 删除知识库时，一次清理其全部片段，包含已逻辑删除的片段。 */
    @Delete("DELETE FROM t_knowledge_chunk WHERE kb_id = #{kbId}")
    int deletePhysicallyByKbId(@Param("kbId") Long kbId);
}
