package com.aifanyi.controller;

import com.aifanyi.common.R;
import com.aifanyi.controller.dto.SettingsDtos.ListModelsReq;
import com.aifanyi.controller.dto.SettingsDtos.SettingsVO;
import com.aifanyi.controller.dto.SettingsDtos.TtsEngineVO;
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
    private final com.aifanyi.agent.trace.LangSmithExporter langSmith;

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

    /** 拉取 TTS 端点的可用模型列表（优先展示 TTS 类模型）。 */
    @PostMapping("/tts/models")
    public R<List<String>> ttsModels(@RequestBody(required = false) ListModelsReq req) {
        String baseUrl = req == null ? null : req.baseUrl();
        String apiKey = req == null ? null : req.apiKey();
        return R.ok(settingsService.listTtsModels(SecurityUtils.currentUserId(), baseUrl, apiKey));
    }

    /** 拉取主 Agent 端点的可用模型列表（留空回退：已存主 Agent 配置 → 翻译模型配置）。 */
    @PostMapping("/agent-main/models")
    public R<List<String>> agentMainModels(@RequestBody(required = false) ListModelsReq req) {
        String baseUrl = req == null ? null : req.baseUrl();
        String apiKey = req == null ? null : req.apiKey();
        return R.ok(settingsService.listAgentModels(SecurityUtils.currentUserId(), baseUrl, apiKey, true));
    }

    /** 拉取子 Agent 端点的可用模型列表（回退链同上）。 */
    @PostMapping("/agent-sub/models")
    public R<List<String>> agentSubModels(@RequestBody(required = false) ListModelsReq req) {
        String baseUrl = req == null ? null : req.baseUrl();
        String apiKey = req == null ? null : req.apiKey();
        return R.ok(settingsService.listAgentModels(SecurityUtils.currentUserId(), baseUrl, apiKey, false));
    }

    /** LangSmith 连通性测试：表单填了用表单的 Key，留空用已保存的。返回人话结果。 */
    @PostMapping("/langsmith/test")
    public R<String> langsmithTest(@RequestBody(required = false) ListModelsReq req) {
        String key = req == null ? null : req.apiKey();
        String proj = req == null ? null : req.baseUrl();   // 复用字段：baseUrl 位携带项目名
        if (key == null || key.isBlank()) {
            String[] saved = settingsService.effectiveLangsmith(SecurityUtils.currentUserId());
            if (saved != null) {
                key = saved[0];
                if (proj == null || proj.isBlank()) {
                    proj = saved[1];
                }
            }
        }
        return R.ok(langSmith.testConnection(key, proj));
    }

    /** 内置 TTS 引擎清单（含每引擎在当前用户下的已配置状态与音色映射由 /tasks/{id}/tts/voices 提供）。 */
    @GetMapping("/tts/engines")
    public R<List<TtsEngineVO>> ttsEngines() {
        return R.ok(settingsService.listTtsEngines(SecurityUtils.currentUserId()));
    }

    /** 内置联网搜索引擎清单（Agent 模式设置页的引擎卡片）。 */
    @GetMapping("/search/engines")
    public R<List<com.aifanyi.controller.dto.SettingsDtos.SearchEngineVO>> searchEngines() {
        return R.ok(settingsService.listSearchEngines(SecurityUtils.currentUserId()));
    }
}
