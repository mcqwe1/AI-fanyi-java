package com.aifanyi.controller;

import com.aifanyi.common.R;
import com.aifanyi.controller.dto.AuthDtos.LoginRequest;
import com.aifanyi.controller.dto.AuthDtos.LoginResponse;
import com.aifanyi.controller.dto.AuthDtos.RegisterRequest;
import com.aifanyi.controller.dto.SettingsDtos.ChangePasswordReq;
import com.aifanyi.security.SecurityUtils;
import com.aifanyi.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public R<LoginResponse> register(@Valid @RequestBody RegisterRequest req) {
        return R.ok(authService.register(req));
    }

    @PostMapping("/login")
    public R<LoginResponse> login(@Valid @RequestBody LoginRequest req) {
        return R.ok(authService.login(req));
    }

    @PostMapping("/change-password")
    public R<Void> changePassword(@RequestBody ChangePasswordReq req) {
        authService.changePassword(SecurityUtils.currentUserId(), req.oldPassword(), req.newPassword());
        return R.ok();
    }

    /** 修改用户名（需密码确认）；成功返回新 token，前端立即替换本地登录态。 */
    public record ChangeUsernameReq(String newUsername, String password) {
    }

    @PostMapping("/change-username")
    public R<LoginResponse> changeUsername(@RequestBody ChangeUsernameReq req) {
        return R.ok(authService.changeUsername(
                SecurityUtils.currentUserId(), req.newUsername(), req.password()));
    }

    /** 上传/更换头像（data URL 小图，空串=清除）。返回保存后的头像。 */
    public record AvatarReq(String avatar) {
    }

    @PostMapping("/avatar")
    public R<String> updateAvatar(@RequestBody AvatarReq req) {
        return R.ok(authService.updateAvatar(SecurityUtils.currentUserId(), req.avatar()));
    }

    /** 注销账号（需密码确认）：软删用户并清除其密钥类数据，前端随后登出。 */
    public record DeleteAccountReq(String password) {
    }

    @PostMapping("/delete-account")
    public R<Void> deleteAccount(@RequestBody DeleteAccountReq req) {
        authService.deleteAccount(SecurityUtils.currentUserId(), req.password());
        return R.ok();
    }
}
