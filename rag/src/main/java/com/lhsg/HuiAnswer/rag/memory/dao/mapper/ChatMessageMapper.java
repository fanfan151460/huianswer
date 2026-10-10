package com.lhsg.HuiAnswer.rag.memory.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lhsg.HuiAnswer.rag.memory.dao.entity.ChatMessageDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessageDO> {
    /** 包含逻辑删除的消息，避免复用已存在的轮次；由会话 Redis 锁保护分配过程。 */
    @Select("SELECT COALESCE(MAX(turn_no), 0) FROM t_chat_message WHERE conversation_id = #{conversationId}")
    int selectMaxTurnNo(@Param("conversationId") Long conversationId);
}
