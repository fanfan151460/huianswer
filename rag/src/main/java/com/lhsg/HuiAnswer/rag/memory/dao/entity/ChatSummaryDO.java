package com.lhsg.HuiAnswer.rag.memory.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/** 每个会话保存一份有效摘要，后续压缩成功后更新同一条记录。 */
@Data
@Accessors(chain = true)
@TableName("t_chat_summary")
public class ChatSummaryDO {
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属会话 ID。 */
    private Long conversationId;

    /** 所属用户 ID，由后端登录上下文填写。 */
    private Long userId;

    /** 摘要已经覆盖到的轮次；0 表示尚未压缩，只纳入完整成功的问答。 */
    private Integer summaryUntilTurnNo;

    /** 历史摘要正文；合并旧摘要与新增历史后更新。 */
    private String content;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /** 0 正常，1 逻辑删除。 */
    @TableLogic
    @TableField(fill = FieldFill.INSERT)
    private Integer deleted;
}
