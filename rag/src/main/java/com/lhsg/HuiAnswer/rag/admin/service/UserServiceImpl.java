package com.lhsg.HuiAnswer.rag.admin.service;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lhsg.HuiAnswer.framework.exception.ClientException;
import com.lhsg.HuiAnswer.rag.admin.dao.entity.UserDO;
import com.lhsg.HuiAnswer.rag.admin.dao.mapper.UserMapper;
import com.lhsg.HuiAnswer.rag.admin.dto.UserLoginReqDTO;
import com.lhsg.HuiAnswer.rag.admin.dto.UserRespDTO;
import com.lhsg.HuiAnswer.rag.admin.user.UserDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.lhsg.HuiAnswer.rag.constant.constant.HUI_ANSWER_LOGIN_USER;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements IUserService {

    /** 登录态有效期：1 小时 */
    private static final long LOGIN_TTL_SECONDS = TimeUnit.HOURS.toSeconds(1);

    /** 脚本返回值：本次登录挤掉了该账号原有的会话 */
    private static final long LOGIN_KICKED_PREVIOUS = 1L;

    /**
     * 单设备登录：写入 token、设置有效期，并且让后登录的挤掉先登录的。
     */
    private static final DefaultRedisScript<Long> LOGIN_SINGLE_DEVICE_SCRIPT = new DefaultRedisScript<>("""
            -- KEYS[1] 用户维度的 hash key（userName）
            -- KEYS[2] token，作为 hash field
            -- ARGV[1] 序列化后的 UserDTO
            -- ARGV[2] 有效期（秒）
            -- DEL 返回被删掉的 key 数量（0/1），有没有旧会话的判断
            local kicked = redis.call('DEL', KEYS[1])
            redis.call('HSET', KEYS[1], KEYS[2], ARGV[1])
            redis.call('EXPIRE', KEYS[1], ARGV[2])
            return kicked
            """, Long.class);

    private final UserMapper userMapper;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public UserRespDTO login(UserLoginReqDTO userLoginReqDTO) {
        LambdaQueryWrapper<UserDO> queryWrapper = Wrappers.lambdaQuery(UserDO.class);
        UserDO userDO = userMapper.selectOne(queryWrapper);
        if (Objects.isNull(userDO)) {
            throw new ClientException("用户名或密码错误");
        }
        String userName = userDO.getUserName();
        String token = UUID.randomUUID().toString();
        UserDTO userDTO = BeanUtil.copyProperties(userDO, UserDTO.class);

        Long result = stringRedisTemplate.execute(
                LOGIN_SINGLE_DEVICE_SCRIPT,
                List.of(String.format(HUI_ANSWER_LOGIN_USER, userName), token),
                JSONUtil.toJsonStr(userDTO),
                String.valueOf(LOGIN_TTL_SECONDS));
        if (result == LOGIN_KICKED_PREVIOUS) {
            log.info("用户 [{}] 在新设备登录，原有会话已失效", userName);
        }

        return new UserRespDTO().setToken(token);
    }
}
