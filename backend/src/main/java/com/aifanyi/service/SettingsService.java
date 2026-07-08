package com.aifanyi.service;

import com.aifanyi.common.BizException;
import com.aifanyi.config.AifanyiProperties;
import com.aifanyi.controller.dto.SettingsDtos.SecretView;
import com.aifanyi.controller.dto.SettingsDtos.SettingsVO;
import com.aifanyi.controller.dto.SettingsDtos.UpdateSettingsReq;
import com.aifanyi.entity.UserSetting;
import com.aifanyi.llm.GeminiConfig;
import com.aifanyi.llm.LlmConfig;
import com.aifanyi.mapper.UserSettingMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 用户设置/密钥的读写与“有效配置”解析。
 * 密钥/Base URL/模型一律来自每用户 user_setting（设置页），不再有环境变量兜底；
 * 未配置完整即抛业务异常，提示去设置页填写。
 */
@Service
@RequiredArgsConstructor
public class SettingsService {

    private final UserSettingMapper mapper;
    private final AifanyiProperties props;
    private final ObjectMapper json;

    private UserSetting load(Long userId) {
        return mapper.selectById(userId);
    }

    /** 展示用（密钥打码）。 */
    public SettingsVO view(Long userId) {
        UserSetting s = load(userId);
        return new SettingsVO(
                secret(s == null ? null : s.getGroqApiKey()),
                s == null ? null : s.getLlmBaseUrl(),
                secret(s == null ? null : s.getLlmApiKey()),
                s == null ? null : s.getLlmModel(),
                secret(s == null ? null : s.getDashscopeApiKey()),
                secret(s == null ? null : s.getZhipuApiKey()),
                s == null ? null : s.getGeminiBaseUrl(),
                secret(s == null ? null : s.getGeminiApiKey()),
                s == null ? null : s.getGeminiModel()
        );
    }

    /** 更新：仅非空字段写入。 */
    public void update(Long userId, UpdateSettingsReq req) {
        UserSetting s = load(userId);
        boolean isNew = (s == null);
        if (isNew) {
            s = new UserSetting();
            s.setUserId(userId);
        }
        if (has(req.groqApiKey())) s.setGroqApiKey(req.groqApiKey().trim());
        if (has(req.llmBaseUrl())) s.setLlmBaseUrl(req.llmBaseUrl().trim());
        if (has(req.llmApiKey())) s.setLlmApiKey(req.llmApiKey().trim());
        if (has(req.llmModel())) s.setLlmModel(req.llmModel().trim());
        if (has(req.dashscopeApiKey())) s.setDashscopeApiKey(req.dashscopeApiKey().trim());
        if (has(req.zhipuApiKey())) s.setZhipuApiKey(req.zhipuApiKey().trim());
        if (has(req.geminiBaseUrl())) s.setGeminiBaseUrl(req.geminiBaseUrl().trim());
        if (has(req.geminiApiKey())) s.setGeminiApiKey(req.geminiApiKey().trim());
        if (has(req.geminiModel())) s.setGeminiModel(req.geminiModel().trim());
        if (isNew) {
            mapper.insert(s);
        } else {
            mapper.updateById(s);
        }
    }

    // ---- 有效配置解析（供 pipeline / providers 用；不完整即抛错指引设置页）----

    public LlmConfig effectiveLlm(Long userId) {
        UserSetting s = load(userId);
        String base = s == null ? null : s.getLlmBaseUrl();
        String key = s == null ? null : s.getLlmApiKey();
        String model = s == null ? null : s.getLlmModel();
        if (!has(base) || !has(key) || !has(model)) {
            throw new BizException("翻译模型未配置完整，请在「设置 → API 密钥」填写 Base URL、API Key 和模型");
        }
        AifanyiProperties.Llm tune = props.getLlm();
        return new LlmConfig(base.trim(), key.trim(), model.trim(),
                tune.isDisableThinking(), tune.getBatchSize(), tune.getConcurrency());
    }

    public GeminiConfig effectiveGemini(Long userId) {
        UserSetting s = load(userId);
        String base = s == null ? null : s.getGeminiBaseUrl();
        String key = s == null ? null : s.getGeminiApiKey();
        String model = s == null ? null : s.getGeminiModel();
        if (!has(base) || !has(key) || !has(model)) {
            throw new BizException("Gemini 未配置完整，请在「设置 → API 密钥」填写 Base URL、API Key 和模型");
        }
        return new GeminiConfig(base.trim(), key.trim(), model.trim());
    }

    /** KB 翻译并发上限：与 GeminiClient.MAX_PARALLEL 同量级，避免压垮个人代理/触发 Vertex 配额。 */
    private static final int KB_MAX_CONCURRENCY = 6;

    /**
     * 按任务模式解析翻译配置：KB 模式与抽术语同用 Gemini（保证术语表遵循一致），
     * 普通模式用「设置 → API 密钥」里的 LLM 配置。模式路由收在这里，新增翻译入口不必各自记得分流。
     */
    public LlmConfig effectiveTranslationLlm(Long userId, boolean kbMode) {
        return kbMode ? effectiveKbLlm(userId) : effectiveLlm(userId);
    }

    /**
     * KB（术语表）模式的翻译配置：与抽术语用同一 Gemini 端点/模型，保证术语表遵循的一致性
     * （DeepSeek 等第三方模型偶尔不按 Gemini 建的术语表翻译）。
     * 模型剥掉 -search 后缀——联网 grounding 只对抽术语有价值，批量翻译带上只会又慢又贵。
     * disableThinking 固定 false（有意为之）：thinking:{type:disabled} 是 DeepSeek 系方言字段，
     * Gemini 代理不认识，发了没意义；vertex2openai 实测不带该字段即可正常翻译。
     * 并发压到 KB_MAX_CONCURRENCY——429 会被 OpenAiTranslator 静默回退为原文，宁可慢一点。
     */
    private LlmConfig effectiveKbLlm(Long userId) {
        GeminiConfig g = effectiveGemini(userId);
        String model = g.model().endsWith("-search")
                ? g.model().substring(0, g.model().length() - "-search".length())
                : g.model();
        AifanyiProperties.Llm tune = props.getLlm();
        return new LlmConfig(g.baseUrl(), g.apiKey(), model,
                false, tune.getBatchSize(), Math.min(tune.getConcurrency(), KB_MAX_CONCURRENCY));
    }

    public String effectiveGroqKey(Long userId) {
        UserSetting s = load(userId);
        return s == null ? null : s.getGroqApiKey();
    }

    public String effectiveDashscopeKey(Long userId) {
        UserSetting s = load(userId);
        return s == null ? null : s.getDashscopeApiKey();
    }

    public String effectiveZhipuKey(Long userId) {
        UserSetting s = load(userId);
        return s == null ? null : s.getZhipuApiKey();
    }

    // ---- 拉取模型列表（与流水线解析解耦：只需 base+key，不要求已选模型）----

    /**
     * 拉取 OpenAI 兼容端点的可用模型列表（GET {baseUrl}/models）。
     * baseUrl/apiKey 传空则回退到已保存的 LLM 配置。
     */
    public List<String> listLlmModels(Long userId, String baseUrl, String apiKey) {
        UserSetting s = load(userId);
        String base = has(baseUrl) ? baseUrl.trim() : (s == null ? null : s.getLlmBaseUrl());
        String key = has(apiKey) ? apiKey.trim() : (s == null ? null : s.getLlmApiKey());
        return fetchModels(base, key);
    }

    /** 同上，回退到已保存的 Gemini 配置（代理或官方 OpenAI 兼容端点均可）。 */
    public List<String> listGeminiModels(Long userId, String baseUrl, String apiKey) {
        UserSetting s = load(userId);
        String base = has(baseUrl) ? baseUrl.trim() : (s == null ? null : s.getGeminiBaseUrl());
        String key = has(apiKey) ? apiKey.trim() : (s == null ? null : s.getGeminiApiKey());
        return fetchModels(base, key);
    }

    private List<String> fetchModels(String base, String key) {
        if (!has(base)) {
            throw new BizException("请先填写 Base URL");
        }
        if (!has(key)) {
            throw new BizException("请先填写 API Key（或先保存密钥）");
        }
        String url = base.replaceAll("/+$", "") + "/models";

        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(Duration.ofSeconds(5));
        rf.setReadTimeout(Duration.ofSeconds(15));
        RestClient client = RestClient.builder().requestFactory(rf).build();

        String body;
        try {
            body = client.get()
                    .uri(url)
                    .header("Authorization", "Bearer " + key)
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            throw new BizException("拉取模型失败: " + e.getMessage());
        }

        try {
            JsonNode root = json.readTree(body);
            JsonNode data = root.path("data");
            List<String> ids = new ArrayList<>();
            if (data.isArray()) {
                for (JsonNode m : data) {
                    String id = m.path("id").asText("");
                    if (has(id)) {
                        ids.add(id);
                    }
                }
            }
            if (ids.isEmpty()) {
                throw new BizException("该端点未返回任何模型");
            }
            ids.sort(String.CASE_INSENSITIVE_ORDER);
            return ids;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("解析模型列表失败: " + e.getMessage());
        }
    }

    // ---- 辅助 ----

    private static boolean has(String v) {
        return StringUtils.hasText(v);
    }

    private static SecretView secret(String v) {
        if (!has(v)) return new SecretView(false, null);
        String t = v.trim();
        String masked = t.length() <= 4 ? "****" : "****" + t.substring(t.length() - 4);
        return new SecretView(true, masked);
    }
}
