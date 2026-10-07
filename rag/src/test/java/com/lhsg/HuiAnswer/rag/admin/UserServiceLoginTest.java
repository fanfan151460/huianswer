package com.lhsg.HuiAnswer.rag.admin;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.lhsg.HuiAnswer.framework.exception.ClientException;
import com.lhsg.HuiAnswer.rag.admin.dao.entity.UserDO;
import com.lhsg.HuiAnswer.rag.admin.dao.mapper.UserMapper;
import com.lhsg.HuiAnswer.rag.admin.dto.UserLoginReqDTO;
import com.lhsg.HuiAnswer.rag.admin.service.UserServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 登录校验通过后才能创建 Redis 会话。 */
@SuppressWarnings({"unchecked", "rawtypes"})
class UserServiceLoginTest {
    // 与 sql/02_admin.sql 中预置管理员的 BCrypt 哈希一致。
    private static final String ADMIN_PASSWORD_HASH =
            "$2a$10$EUmuNo7kJpndZZw3PlBiSO3vWZB47XCqJB5vlcofP29UhQNw.bLZi";

    private UserMapper users;
    private StringRedisTemplate redis;
    private UserServiceImpl service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), UserDO.class);
        users = mock(UserMapper.class);
        redis = mock(StringRedisTemplate.class);
        service = new UserServiceImpl(users, redis);
    }

    @Test
    void correctCredentialsShouldQueryByUsernameAndCreateSession() {
        when(users.selectOne(any(Wrapper.class))).thenReturn(admin());
        when(redis.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(0L);

        var response = service.login(request("admin", "admin123456"));

        assertNotNull(response.getToken());
        assertFalse(response.getToken().isBlank());
        ArgumentCaptor<LambdaQueryWrapper<UserDO>> query = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(users).selectOne(query.capture());
        assertTrue(query.getValue().getSqlSegment().contains("name ="));
        assertTrue(query.getValue().getParamNameValuePairs().containsValue("admin"));
        verify(redis).execute(any(RedisScript.class), anyList(), any(), any());
    }

    @Test
    void wrongPasswordShouldNotCreateSession() {
        when(users.selectOne(any(Wrapper.class))).thenReturn(admin());

        ClientException error = assertThrows(ClientException.class,
                () -> service.login(request("admin", "wrong-password")));

        assertEquals("用户名或密码错误", error.getMessage());
        verifyNoInteractions(redis);
    }

    @Test
    void unknownUsernameShouldNotCreateSession() {
        assertThrows(ClientException.class,
                () -> service.login(request("unknown-admin", "admin123456")));
        verify(users).selectOne(any(Wrapper.class));
        verifyNoInteractions(redis);
    }

    @Test
    void missingCredentialsShouldNotQueryDatabaseOrCreateSession() {
        assertThrows(ClientException.class, () -> service.login(request(null, "admin123456")));
        assertThrows(ClientException.class, () -> service.login(request("admin", null)));
        assertThrows(ClientException.class, () -> service.login(request(" ", "admin123456")));
        verifyNoInteractions(users, redis);
    }

    private static UserDO admin() {
        return new UserDO().setId(1L).setUserName("admin").setPassword(ADMIN_PASSWORD_HASH);
    }

    private static UserLoginReqDTO request(String username, String password) {
        return new UserLoginReqDTO().setUsername(username).setPassword(password);
    }
}