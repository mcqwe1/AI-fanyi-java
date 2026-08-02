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
}
