package com.lhsg.HuiAnswer.rag.knowledge.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lhsg.HuiAnswer.rag.knowledge.dao.entity.KnowledgeBaseDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBaseDO> {
    /** 同一知识库的导入和删除串行执行；必须在数据库事务中调用。 */
    @Select("SELECT * FROM t_knowledge_base WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    KnowledgeBaseDO selectActiveForUpdate(@Param("id") Long id);
}
