package com.lhsg.HuiAnswer.framework.base;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

@Data
@Accessors(chain = true)
@NoArgsConstructor
@AllArgsConstructor
public class MsgWrapper<T> implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    /** 消息标识，用于日志追踪；目前还没有接入消费幂等。 */
    private String keys;

    /** 消息体 */
    private T msg;
}
