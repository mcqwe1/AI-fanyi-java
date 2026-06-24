package com.aifanyi.service;

import com.aifanyi.common.BizException;
import com.aifanyi.controller.dto.AuthDtos.LoginRequest;
import com.aifanyi.controller.dto.AuthDtos.LoginResponse;
import com.aifanyi.controller.dto.AuthDtos.RegisterRequest;
import com.aifanyi.entity.User;
import com.aifanyi.mapper.UserMapper;
import com.aifanyi.security.JwtUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    @Transactional
    public LoginResponse register(RegisterRequest req) {
        Long exists = userMapper.selectCount(
                Wrappers.<User>lambdaQuery().eq(User::getUsername, req.username()));
        if (exists != null && exists > 0) {
            throw new BizException(409, "用户名已存在");
        }
        User user = new User();
        user.setUsername(req.username());
        user.setPassword(passwordEncoder.encode(req.password()));
        user.setNickname(req.nickname() == null || req.nickname().isBlank()
                ? req.username() : req.nickname());
        userMapper.insert(user);
        String token = jwtUtil.generate(user.getId(), user.getUsername());
        return new LoginResponse(user.getId(), user.getUsername(), user.getNickname(), token);
    }

    public LoginResponse login(LoginRequest req) {
        User user = userMapper.selectOne(
                Wrappers.<User>lambdaQuery().eq(User::getUsername, req.username()));
        if (user == null || !passwordEncoder.matches(req.password(), user.getPassword())) {
            throw new BizException(401, "用户名或密码错误");
        }
        String token = jwtUtil.generate(user.getId(), user.getUsername());
        return new LoginResponse(user.getId(), user.getUsername(), user.getNickname(), token);
    }

    public void changePassword(Long userId, String oldPwd, String newPwd) {
        if (newPwd == null || newPwd.length() < 6) {
            throw new BizException(400, "新密码至少 6 位");
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(404, "用户不存在");
        }
        if (!passwordEncoder.matches(oldPwd, user.getPassword())) {
            throw new BizException(400, "原密码错误");
        }
        user.setPassword(passwordEncoder.encode(newPwd));
        userMapper.updateById(user);
    }
}
