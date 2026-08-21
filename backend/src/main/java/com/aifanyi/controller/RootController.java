
package com.aifanyi.controller;

import com.aifanyi.common.R;
import com.aifanyi.controller.dto.AuthDtos.LoginRequest;
import com.aifanyi.controller.dto.AuthDtos.LoginResponse;
import com.aifanyi.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class RootController {

    private final AuthService authService;

    @PostMapping("/")
    public R<LoginResponse> login(@Valid @RequestBody LoginRequest req) {
        return R.ok(authService.login(req));
    }
}


