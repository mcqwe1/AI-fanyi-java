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

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_USER = "USER";

    private final UserMapper userMapper;
    private final com.aifanyi.mapper.UserSettingMapper userSettingMapper;
    private final com.aifanyi.mapper.ModelServiceMapper modelServiceMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    @Transactional
    public LoginResponse register(RegisterRequest req) {
        bootstrapAdminIfMissing();
        Long exists = userMapper.selectCount(
                Wrappers.<User>lambdaQuery().eq(User::getUsername, req.username()));
        if (exists != null && exists > 0) {
            throw new BizException(409, "Username already exists");
        }
        Long userCount = userMapper.selectCount(Wrappers.<User>lambdaQuery());
        User user = new User();
        user.setUsername(req.username());
        user.setPassword(passwordEncoder.encode(req.password()));
        user.setNickname(req.nickname() == null || req.nickname().isBlank()
                ? req.username() : req.nickname());
        // The first account is the initial administrator, so a new portable install is manageable immediately.
        user.setRole(userCount == null || userCount == 0 ? ROLE_ADMIN : ROLE_USER);
        user.setEnabled(1);
        userMapper.insert(user);
        return responseFor(user);
    }

    @Transactional
    public LoginResponse login(LoginRequest req) {
        bootstrapAdminIfMissing();
        User user = userMapper.selectOne(
                Wrappers.<User>lambdaQuery().eq(User::getUsername, req.username()));
        if (user == null || !passwordEncoder.matches(req.password(), user.getPassword())) {
            throw new BizException(401, "Invalid username or password");
        }
        if (!isEnabled(user)) {
            throw new BizException(403, "This account has been disabled. Contact an administrator.");
        }
        return responseFor(user);
    }

    public void changePassword(Long userId, String oldPwd, String newPwd) {
        if (newPwd == null || newPwd.length() < 6) {
            throw new BizException(400, "New password must have at least 6 characters");
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(404, "User not found");
        }
        if (!passwordEncoder.matches(oldPwd, user.getPassword())) {
            throw new BizException(400, "Current password is incorrect");
        }
        user.setPassword(passwordEncoder.encode(newPwd));
        userMapper.updateById(user);
    }

    @Transactional
    public LoginResponse changeUsername(Long userId, String newUsername, String password) {
        if (newUsername == null || newUsername.isBlank()) {
            throw new BizException(400, "New username cannot be blank");
        }
        String name = newUsername.trim();
        if (name.length() < 2 || name.length() > 32) {
            throw new BizException(400, "Username must be 2 to 32 characters");
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(404, "User not found");
        }
        if (!passwordEncoder.matches(password == null ? "" : password, user.getPassword())) {
            throw new BizException(400, "Password is incorrect");
        }
        if (name.equals(user.getUsername())) {
            throw new BizException(400, "New username matches the current username");
        }
        Long exists = userMapper.selectCount(
                Wrappers.<User>lambdaQuery().eq(User::getUsername, name));
        if (exists != null && exists > 0) {
            throw new BizException(409, "Username is already in use");
        }
        user.setUsername(name);
        userMapper.updateById(user);
        return responseFor(user);
    }

    private LoginResponse responseFor(User user) {
        String role = normalizeRole(user.getRole());
        String token = jwtUtil.generate(user.getId(), user.getUsername());
        return new LoginResponse(user.getId(), user.getUsername(), user.getNickname(), role, token, user.getAvatar());
    }

    /** 上传/更换头像：前端已裁剪成小图的 data URL；空串=清除头像。 */
    public String updateAvatar(Long userId, String avatar) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(404, "User not found");
        }
        String a = avatar == null ? "" : avatar.trim();
        if (!a.isEmpty()) {
            if (!a.startsWith("data:image/")) {
                throw new BizException(400, "头像格式不对，请重新选择图片");
            }
            if (a.length() > 400_000) {
                throw new BizException(400, "头像图片过大，请换一张（裁剪后应在 300KB 以内）");
            }
        }
        user.setAvatar(a.isEmpty() ? null : a);
        userMapper.updateById(user);
        return user.getAvatar();
    }

    /**
     * 注销账号：密码确认后软删用户，并真删设置/模型服务（里面存着 API 密钥）。
     * username 先改成占位名——user 表的 UNIQUE(username) 覆盖软删行，
     * 不改名的话这个用户名从此再也注册不了。
     */
    @Transactional
    public void deleteAccount(Long userId, String password) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(404, "User not found");
        }
        if (!passwordEncoder.matches(password == null ? "" : password, user.getPassword())) {
            throw new BizException(400, "密码不正确");
        }
        if (ROLE_ADMIN.equalsIgnoreCase(user.getRole())) {
            Long admins = userMapper.selectCount(Wrappers.<User>lambdaQuery()
                    .eq(User::getRole, ROLE_ADMIN).eq(User::getEnabled, 1));
            if (admins != null && admins <= 1) {
                throw new BizException(400, "你是最后一个管理员，注销前请先在「管理员后台」把其他账号设为管理员");
            }
        }
        // 清掉密钥类数据（真删）；任务/术语库留存，管理员可在后台清理
        userSettingMapper.deleteById(userId);
        modelServiceMapper.delete(Wrappers.<com.aifanyi.entity.ModelService>lambdaQuery()
                .eq(com.aifanyi.entity.ModelService::getUserId, userId));
        String placeholder = "del_" + userId + "_" + (System.currentTimeMillis() % 100_000_000L);
        user.setUsername(placeholder.length() > 64 ? placeholder.substring(0, 64) : placeholder);
        user.setEnabled(0);
        userMapper.updateById(user);
        userMapper.deleteById(userId);      // @TableLogic 软删
    }

    private void bootstrapAdminIfMissing() {
        Long adminCount = userMapper.selectCount(
                Wrappers.<User>lambdaQuery().eq(User::getRole, ROLE_ADMIN));
        if (adminCount != null && adminCount > 0) {
            return;
        }
        User firstUser = userMapper.selectOne(Wrappers.<User>lambdaQuery()
                .orderByAsc(User::getId).last("LIMIT 1"));
        if (firstUser != null) {
            firstUser.setRole(ROLE_ADMIN);
            if (firstUser.getEnabled() == null) firstUser.setEnabled(1);
            userMapper.updateById(firstUser);
        }
    }

    private static boolean isEnabled(User user) {
        return user.getEnabled() == null || user.getEnabled() == 1;
    }

    private static String normalizeRole(String role) {
        return ROLE_ADMIN.equalsIgnoreCase(role) ? ROLE_ADMIN : ROLE_USER;
    }
}
