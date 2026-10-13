package com.lhsg.HuiAnswer.rag.knowledge.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lhsg.HuiAnswer.rag.knowledge.dao.entity.KnowledgeDocumentDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocumentDO> {
    /**
     * 批量逻辑删除，同时更新审计时间。
     */
    @Update("""
            UPDATE t_knowledge_document
            SET deleted = 1, update_time = #{updateTime}
            WHERE kb_id = #{kbId} AND deleted = 0
            """)
    void markDeletedByKbId(@Param("kbId") Long kbId, @Param("updateTime") LocalDateTime updateTime);
}
