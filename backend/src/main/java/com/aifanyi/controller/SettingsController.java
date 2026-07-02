package com.aifanyi.controller;

import com.aifanyi.common.R;
import com.aifanyi.controller.dto.SettingsDtos.ListModelsReq;
import com.aifanyi.controller.dto.SettingsDtos.SettingsVO;
import com.aifanyi.controller.dto.SettingsDtos.UpdateSettingsReq;
import com.aifanyi.security.SecurityUtils;
import com.aifanyi.service.SettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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

    /** 用填写的 baseUrl/apiKey（留空回退已保存配置）拉取可用模型列表。 */
    @PostMapping("/llm/models")
    public R<List<String>> llmModels(@RequestBody(required = false) ListModelsReq req) {
        String baseUrl = req == null ? null : req.baseUrl();
        String apiKey = req == null ? null : req.apiKey();
        return R.ok(settingsService.listLlmModels(SecurityUtils.currentUserId(), baseUrl, apiKey));
    }

    /** 拉取 Gemini 端点（本机代理或官方 OpenAI 兼容端点）的可用模型列表。 */
    @PostMapping("/gemini/models")
    public R<List<String>> geminiModels(@RequestBody(required = false) ListModelsReq req) {
        String baseUrl = req == null ? null : req.baseUrl();
        String apiKey = req == null ? null : req.apiKey();
        return R.ok(settingsService.listGeminiModels(SecurityUtils.currentUserId(), baseUrl, apiKey));
    }
}
