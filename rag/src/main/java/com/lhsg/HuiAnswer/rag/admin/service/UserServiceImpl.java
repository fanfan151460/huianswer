package com.lhsg.HuiAnswer.rag.admin.service;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.BCrypt;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lhsg.HuiAnswer.framework.exception.ClientException;
import com.lhsg.HuiAnswer.framework.exception.ServiceException;
import com.lhsg.HuiAnswer.rag.admin.dao.entity.UserDO;
import com.lhsg.HuiAnswer.rag.admin.dao.mapper.UserMapper;
import com.lhsg.HuiAnswer.rag.admin.dto.UserLoginReqDTO;
import com.lhsg.HuiAnswer.rag.admin.dto.UserRespDTO;
import com.lhsg.HuiAnswer.rag.admin.user.UserDTO;
import com.lhsg.HuiAnswer.rag.admin.user.UserRegisterReqDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static com.lhsg.HuiAnswer.rag.constant.constant.HUI_ANSWER_LOGIN_USER;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements IUserService {

    /** 登录态有效期：1 小时 */
    private static final long LOGIN_TTL_SECONDS = TimeUnit.DAYS.toSeconds(30);

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
        if (userLoginReqDTO == null || StrUtil.isBlank(userLoginReqDTO.getUsername())
                || StrUtil.isBlank(userLoginReqDTO.getPassword())) {
            throw new ClientException("用户名或密码错误");
        }
        LambdaQueryWrapper<UserDO> queryWrapper = Wrappers.lambdaQuery(UserDO.class)
                .eq(UserDO::getUserName, userLoginReqDTO.getUsername());
        UserDO userDO = userMapper.selectOne(queryWrapper);
        if (Objects.isNull(userDO)
                || !BCrypt.checkpw(userLoginReqDTO.getPassword(), userDO.getPassword())) {
            throw new ClientException("用户名或密码错误");
        }
        String userName = userDO.getUserName();
        String token = UUID.randomUUID(true).toString(true);
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

    @Override
    public void register(UserRegisterReqDTO loginReqDTO) {
        if (loginReqDTO == null || StrUtil.isBlank(loginReqDTO.getUsername()) || StrUtil.isBlank(loginReqDTO.getPassword())) {
            throw new ClientException("填写信息有误");
        }
        if (loginReqDTO.getPassword().length() < 6) {
            throw new ClientException("密码设置不得低于六位");
        }
        if (!loginReqDTO.getPassword().equals(loginReqDTO.getConfirmPassword())) {
            throw new ClientException("两次密码输入不同");
        }
        UserDO userDO = new UserDO().setUserName(loginReqDTO.getUsername())
                .setPassword(BCrypt.hashpw(loginReqDTO.getPassword(), BCrypt.gensalt()));
        int insert = userMapper.insert(userDO);
        if (insert != 1) {
            throw new ServiceException("服务注册失败");
        }
    }
}
