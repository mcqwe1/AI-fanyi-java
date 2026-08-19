package com.aifanyi.controller;

import com.aifanyi.common.R;
import com.aifanyi.controller.dto.SettingsDtos.AsrEngineVO;
import com.aifanyi.controller.dto.SettingsDtos.ListModelsReq;
import com.aifanyi.controller.dto.SettingsDtos.LlmProviderVO;
import com.aifanyi.controller.dto.SettingsDtos.ModelServiceVO;
import com.aifanyi.controller.dto.SettingsDtos.SaveModelServiceReq;
import com.aifanyi.controller.dto.SettingsDtos.SettingsVO;
import com.aifanyi.controller.dto.SettingsDtos.ToggleServiceReq;
import com.aifanyi.controller.dto.SettingsDtos.TtsEngineVO;
import com.aifanyi.controller.dto.SettingsDtos.UpdateSettingsReq;
import com.aifanyi.llm.LlmTranslator;
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
    private final LlmTranslator llmTranslator;
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

    /** 用填写的 baseUrl/apiKey（留空回退当前生效服务）拉取可用模型列表；protocol=claude 换 Anthropic 鉴权头。 */
    @PostMapping("/llm/models")
    public R<List<String>> llmModels(@RequestBody(required = false) ListModelsReq req) {
        String baseUrl = req == null ? null : req.baseUrl();
        String apiKey = req == null ? null : req.apiKey();
        String protocol = req == null ? null : req.protocol();
        return R.ok(settingsService.listLlmModels(SecurityUtils.currentUserId(), baseUrl, apiKey, protocol));
    }

    // ---- 已配置的模型服务（多服务商） ----

    /** 服务商注册表（服务商下拉选项）。 */
    @GetMapping("/llm/providers")
    public R<List<LlmProviderVO>> llmProviders() {
        return R.ok(settingsService.listLlmProviders());
    }

    /** 已配置的模型服务列表（首次访问自动迁移旧单配置）。 */
    @GetMapping("/llm/services")
    public R<List<ModelServiceVO>> llmServices() {
        return R.ok(settingsService.listLlmServices(SecurityUtils.currentUserId()));
    }

    /** 新增/更新模型服务（Base URL 会按协议自动补 /v1）。 */
    @PostMapping("/llm/services")
    public R<ModelServiceVO> saveLlmService(@RequestBody SaveModelServiceReq req) {
        return R.ok(settingsService.saveLlmService(SecurityUtils.currentUserId(), req));
    }

    @DeleteMapping("/llm/services/{id}")
    public R<Void> deleteLlmService(@PathVariable Long id) {
        settingsService.deleteLlmService(SecurityUtils.currentUserId(), id);
        return R.ok();
    }

    /** 设为默认服务（全局翻译改用它；自动启用）。 */
    @PostMapping("/llm/services/{id}/default")
    public R<Void> setDefaultLlmService(@PathVariable Long id) {
        settingsService.setDefaultLlmService(SecurityUtils.currentUserId(), id);
        return R.ok();
    }

    /** 启用/停用服务。 */
    @PostMapping("/llm/services/{id}/toggle")
    public R<Void> toggleLlmService(@PathVariable Long id, @RequestBody ToggleServiceReq req) {
        settingsService.toggleLlmService(SecurityUtils.currentUserId(), id,
                req != null && Boolean.TRUE.equals(req.enabled()));
        return R.ok();
    }

    /** 测试连接：按表单配置真实翻译一行 Hello，返回译文与耗时（不落库）。 */
    @PostMapping("/llm/test")
    public R<String> testLlmService(@RequestBody SaveModelServiceReq req) {
        return R.ok(settingsService.testLlmService(SecurityUtils.currentUserId(), req, llmTranslator));
    }

    /** 语音识别引擎卡片（本地 Whisper / Groq）。 */
    @GetMapping("/asr/engines")
    public R<List<AsrEngineVO>> asrEngines() {
        return R.ok(settingsService.listAsrEngines(SecurityUtils.currentUserId()));
    }

    /**
     * 任务表单各识别档位的实测倍速（媒体时长 ÷ 转写耗时）。
     * 前端拿它 × 本地读到的媒体时长，在下拉里直接显示「预计 ≈2 分 17 秒」。
     */
    @GetMapping("/asr/speed")
    public R<List<com.aifanyi.asr.AsrSpeed.Option>> asrSpeed() {
        return R.ok(settingsService.asrSpeedOptions());
    }

    /** 拉取 TTS 端点的可用模型列表（优先展示 TTS 类模型）。 */
    @PostMapping("/tts/models")
    public R<List<String>> ttsModels(@RequestBody(required = false) ListModelsReq req) {
        String baseUrl = req == null ? null : req.baseUrl();
        String apiKey = req == null ? null : req.apiKey();
        return R.ok(settingsService.listTtsModels(SecurityUtils.currentUserId(), baseUrl, apiKey));
    }

    /** 拉取全能AI翻译「翻译模型」端点的可用模型列表（留空回退：已存配置 → 默认模型服务）。 */
    @PostMapping("/agent-translate/models")
    public R<List<String>> agentTranslateModels(@RequestBody(required = false) ListModelsReq req) {
        String baseUrl = req == null ? null : req.baseUrl();
        String apiKey = req == null ? null : req.apiKey();
        return R.ok(settingsService.listAgentModels(SecurityUtils.currentUserId(), baseUrl, apiKey, "translate"));
    }

    /** 拉取主 Agent 端点的可用模型列表（留空回退：已存主 Agent 配置 → 默认模型服务）。 */
    @PostMapping("/agent-main/models")
    public R<List<String>> agentMainModels(@RequestBody(required = false) ListModelsReq req) {
        String baseUrl = req == null ? null : req.baseUrl();
        String apiKey = req == null ? null : req.apiKey();
        return R.ok(settingsService.listAgentModels(SecurityUtils.currentUserId(), baseUrl, apiKey, "main"));
    }

    /** 拉取子 Agent 端点的可用模型列表（回退链同上）。 */
    @PostMapping("/agent-sub/models")
    public R<List<String>> agentSubModels(@RequestBody(required = false) ListModelsReq req) {
        String baseUrl = req == null ? null : req.baseUrl();
        String apiKey = req == null ? null : req.apiKey();
        return R.ok(settingsService.listAgentModels(SecurityUtils.currentUserId(), baseUrl, apiKey, "sub"));
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
