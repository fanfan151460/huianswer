package com.lhsg.HuiAnswer.rag.admin.user;

import com.lhsg.HuiAnswer.framework.exception.ClientException;

import java.util.Optional;

public class UsersContext {
    private static final ThreadLocal<UserDTO> USER_INFO_THREAD_LOCAL = new ThreadLocal<>();

    public static String getUserName() {
        return Optional.ofNullable(USER_INFO_THREAD_LOCAL.get())
                .map(UserDTO::getUserName)
                .orElse(null);
    }

    public static Long getUserId() {
        return Optional.ofNullable(USER_INFO_THREAD_LOCAL.get())
                .map(UserDTO::getId)
                .orElse(null);
    }

    /** 创建知识库、上传文档时使用，避免没有登录身份也写入数据。 */
    public static Long requireUserId() {
        Long userId = getUserId();
        if (userId == null || userId <= 0) {
            throw new ClientException("请先登录账号");
        }
        return userId;
    }

    public static void setUserDTO(UserDTO userInfo) {
        USER_INFO_THREAD_LOCAL.set(userInfo);
    }

    public static void removeUserDTO() {
        USER_INFO_THREAD_LOCAL.remove();
    }
}
