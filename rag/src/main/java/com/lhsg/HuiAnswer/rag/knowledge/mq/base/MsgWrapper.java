package com.lhsg.HuiAnswer.rag.knowledge.mq.base;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.io.Serializable;

@Data
@Accessors(chain = true)
@NoArgsConstructor
@AllArgsConstructor
public class MsgWrapper<T> implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 消息标识，用于日志追踪；目前还没有接入消费幂等。 */
    private String keys;
    private T msg;
}
