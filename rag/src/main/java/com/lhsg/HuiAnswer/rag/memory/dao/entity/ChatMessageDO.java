package com.lhsg.HuiAnswer.rag.memory.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lhsg.HuiAnswer.rag.knowledge.handler.JsonbTypeHandler;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/** 用户问题与模型回答分别保存；模型回答结束后一次性插入。 */
@Data
@Accessors(chain = true)
@TableName(value = "t_chat_message", autoResultMap = true)
public class ChatMessageDO {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long conversationId;

    /** 从 1 开始，同一轮的问题和回答共用轮次。 */
    private Integer turnNo;

    /** user / assistant。 */
    private String role;

    /** 完整正文或失败时已经生成的部分，允许空字符串。 */
    private String content;

    /** 结束态：success / failed。 */
    private String status;

    /** 引用列表的 JSON 字符串，使用 Gson 转换，对应数据库 JSONB 数组。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String sources;

    private String model;

    /** 本次调用总 token 数；未取得时为 null。 */
    private Integer totalTokens;

    private String finishReason;

    /** 简短失败原因。 */
    private String errorMessage;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    @TableField(fill = FieldFill.INSERT)
    private Integer deleted;
}
