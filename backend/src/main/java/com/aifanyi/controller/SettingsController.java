package com.aifanyi.controller;

import com.aifanyi.common.R;
import com.aifanyi.controller.dto.SettingsDtos.SettingsVO;
import com.aifanyi.controller.dto.SettingsDtos.UpdateSettingsReq;
import com.aifanyi.security.SecurityUtils;
import com.aifanyi.service.SettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final SettingsService settingsService;

    @GetMapping
    public R<SettingsVO> get() {
        return R.ok(settingsService.view(SecurityUtils.currentUserId()));
    }

    @PutMapping
    public R<Void> update(@RequestBody UpdateSettingsReq req) {
        settingsService.update(SecurityUtils.currentUserId(), req);
        return R.ok();
    }
}
