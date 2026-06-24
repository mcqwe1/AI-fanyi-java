package com.aifanyi.service;

import com.aifanyi.config.AifanyiProperties;
import com.aifanyi.controller.dto.SettingsDtos.SecretView;
import com.aifanyi.controller.dto.SettingsDtos.SettingsVO;
import com.aifanyi.controller.dto.SettingsDtos.UpdateSettingsReq;
import com.aifanyi.entity.UserSetting;
import com.aifanyi.llm.LlmConfig;
import com.aifanyi.mapper.UserSettingMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 用户设置/密钥的读写与“有效配置”解析（用户设置优先，application.yml 的 env 兜底）。
 */
@Service
@RequiredArgsConstructor
public class SettingsService {

    private final UserSettingMapper mapper;
    private final AifanyiProperties props;

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
                secret(s == null ? null : s.getGeminiApiKey())
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
        if (has(req.geminiApiKey())) s.setGeminiApiKey(req.geminiApiKey().trim());
        if (isNew) {
            mapper.insert(s);
        } else {
            mapper.updateById(s);
        }
    }

    // ---- 有效配置解析（供 pipeline / providers 用）----

    public LlmConfig effectiveLlm(Long userId) {
        UserSetting s = load(userId);
        AifanyiProperties.Llm env = props.getLlm();
        return new LlmConfig(
                pick(s == null ? null : s.getLlmBaseUrl(), env.getBaseUrl()),
                pick(s == null ? null : s.getLlmApiKey(), env.getApiKey()),
                pick(s == null ? null : s.getLlmModel(), env.getModel()),
                env.isDisableThinking(), env.getBatchSize(), env.getConcurrency()
        );
    }

    public String effectiveGroqKey(Long userId) {
        UserSetting s = load(userId);
        return pick(s == null ? null : s.getGroqApiKey(), props.getAsr().getGroq().getApiKey());
    }

    public String effectiveDashscopeKey(Long userId) {
        UserSetting s = load(userId);
        return s == null ? null : s.getDashscopeApiKey();
    }

    public String effectiveZhipuKey(Long userId) {
        UserSetting s = load(userId);
        return s == null ? null : s.getZhipuApiKey();
    }

    public String effectiveGeminiKey(Long userId) {
        UserSetting s = load(userId);
        return s == null ? null : s.getGeminiApiKey();
    }

    // ---- 辅助 ----

    private static boolean has(String v) {
        return StringUtils.hasText(v);
    }

    private static String pick(String first, String fallback) {
        return has(first) ? first : fallback;
    }

    private static SecretView secret(String v) {
        if (!has(v)) return new SecretView(false, null);
        String t = v.trim();
        String masked = t.length() <= 4 ? "****" : "****" + t.substring(t.length() - 4);
        return new SecretView(true, masked);
    }
}
