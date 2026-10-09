package com.lhsg.HuiAnswer.rag.memory.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lhsg.HuiAnswer.rag.memory.dao.entity.ChatMessageDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessageDO> {
}
