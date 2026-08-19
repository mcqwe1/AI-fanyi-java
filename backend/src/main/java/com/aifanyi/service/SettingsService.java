package com.aifanyi.service;

import com.aifanyi.common.BizException;
import com.aifanyi.agent.search.SearchConfig;
import com.aifanyi.agent.search.SearchEngines;
import com.aifanyi.asr.AiServiceLauncher;
import com.aifanyi.asr.AsrSpeed;
import com.aifanyi.config.AifanyiProperties;
import com.aifanyi.controller.dto.SettingsDtos.AsrEngineVO;
import com.aifanyi.controller.dto.SettingsDtos.LlmProviderVO;
import com.aifanyi.controller.dto.SettingsDtos.ModelServiceVO;
import com.aifanyi.controller.dto.SettingsDtos.SaveModelServiceReq;
import com.aifanyi.controller.dto.SettingsDtos.SearchEngineVO;
import com.aifanyi.controller.dto.SettingsDtos.SecretView;
import com.aifanyi.controller.dto.SettingsDtos.SettingsVO;
import com.aifanyi.controller.dto.SettingsDtos.TtsEngineVO;
import com.aifanyi.controller.dto.SettingsDtos.UpdateSettingsReq;
import com.aifanyi.entity.ModelService;
import com.aifanyi.entity.UserSetting;
import com.aifanyi.llm.LlmConfig;
import com.aifanyi.llm.LlmProviders;
import com.aifanyi.llm.LlmTranslator;
import com.aifanyi.tts.TtsConfig;
import com.aifanyi.tts.TtsEngines;
import com.aifanyi.mapper.ModelServiceMapper;
import com.aifanyi.mapper.UserSettingMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 用户设置/密钥的读写与“有效配置”解析。
 * 密钥/Base URL/模型一律来自每用户 user_setting（设置页），不再有环境变量兜底；
 * 未配置完整即抛业务异常，提示去设置页填写。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettingsService {

    private final UserSettingMapper mapper;
    private final ModelServiceMapper serviceMapper;
    private final AifanyiProperties props;
    private final ObjectMapper json;
    /** 只用来问「本地转写实际跑在 GPU 还是 CPU」，好把耗时预估算准 */
    private final AiServiceLauncher aiLauncher;

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
                s == null ? null : s.getTtsProvider(),
                s == null ? null : s.getTtsBaseUrl(),
                secret(s == null ? null : s.getTtsApiKey()),
                s == null ? null : s.getTtsModel(),
                s == null ? null : s.getStylePrompt(),
                // ---- Agent 模式 ----
                s == null ? null : s.getAgentTranslateBaseUrl(),
                secret(s == null ? null : s.getAgentTranslateApiKey()),
                s == null ? null : s.getAgentTranslateModel(),
                s == null ? null : s.getAgentMainBaseUrl(),
                secret(s == null ? null : s.getAgentMainApiKey()),
                s == null ? null : s.getAgentMainModel(),
                s == null ? null : s.getAgentSubBaseUrl(),
                secret(s == null ? null : s.getAgentSubApiKey()),
                s == null ? null : s.getAgentSubModel(),
                s == null ? null : s.getSearchProvider(),
                s == null ? null : s.getSearchBaseUrl(),
                secret(s == null ? null : s.getSearchApiKey()),
                secret(s == null ? null : s.getLangsmithApiKey()),
                s == null ? null : s.getLangsmithProject()
        );
    }

    /** 更新：密钥类仅非空字段写入；stylePrompt 传 null 不改、传值（含空串）即写入（空串=清空默认风格）。 */
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
        if (has(req.ttsProvider())) s.setTtsProvider(req.ttsProvider().trim());
        if (has(req.ttsBaseUrl())) s.setTtsBaseUrl(req.ttsBaseUrl().trim());
        if (has(req.ttsApiKey())) s.setTtsApiKey(req.ttsApiKey().trim());
        if (has(req.ttsModel())) s.setTtsModel(req.ttsModel().trim());
        // ---- Agent 模式：翻译模型 + 主/子 Agent 两组 + 搜索引擎 ----
        if (has(req.agentTranslateBaseUrl())) s.setAgentTranslateBaseUrl(req.agentTranslateBaseUrl().trim());
        if (has(req.agentTranslateApiKey())) s.setAgentTranslateApiKey(req.agentTranslateApiKey().trim());
        if (has(req.agentTranslateModel())) s.setAgentTranslateModel(req.agentTranslateModel().trim());
        if (has(req.agentMainBaseUrl())) s.setAgentMainBaseUrl(req.agentMainBaseUrl().trim());
        if (has(req.agentMainApiKey())) s.setAgentMainApiKey(req.agentMainApiKey().trim());
        if (has(req.agentMainModel())) s.setAgentMainModel(req.agentMainModel().trim());
        if (has(req.agentSubBaseUrl())) s.setAgentSubBaseUrl(req.agentSubBaseUrl().trim());
        if (has(req.agentSubApiKey())) s.setAgentSubApiKey(req.agentSubApiKey().trim());
        if (has(req.agentSubModel())) s.setAgentSubModel(req.agentSubModel().trim());
        // searchProvider 是选择项不是密钥：传空串=显式选「不联网」，必须能写进去
        if (req.searchProvider() != null) s.setSearchProvider(req.searchProvider().trim());
        if (has(req.searchBaseUrl())) s.setSearchBaseUrl(req.searchBaseUrl().trim());
        if (has(req.searchApiKey())) s.setSearchApiKey(req.searchApiKey().trim());
        if (has(req.langsmithApiKey())) s.setLangsmithApiKey(req.langsmithApiKey().trim());
        // 项目名非密钥：空串=清空回默认，与 stylePrompt 同语义
        if (req.langsmithProject() != null) s.setLangsmithProject(req.langsmithProject().trim());
        if (req.stylePrompt() != null) {
            // 非密钥字段：清空是真实需求。清空时存空串而非 null——updateById 忽略 null 字段会导致清不掉
            String norm = TaskService.normalizeStylePrompt(req.stylePrompt());
            s.setStylePrompt(norm == null ? "" : norm);
        }
        if (isNew) {
            mapper.insert(s);
        } else {
            mapper.updateById(s);
        }
    }

    // ---- 有效配置解析（供 pipeline / providers 用；不完整即抛错指引设置页）----

    /**
     * 全局翻译使用的大语言模型配置。
     * 优先取「已配置的模型服务」里默认且启用的那行（多服务商，协议感知）；
     * 没有任何服务行时回落到 user_setting 的旧 llm_* 单配置（老用户无感兼容）。
     */
    public LlmConfig effectiveLlm(Long userId) {
        ModelService svc = activeLlmService(userId);
        if (svc != null) {
            return toConfig(svc);
        }
        UserSetting s = load(userId);
        String base = s == null ? null : s.getLlmBaseUrl();
        String key = s == null ? null : s.getLlmApiKey();
        String model = s == null ? null : s.getLlmModel();
        if (!has(base) || !has(key) || !has(model)) {
            throw new BizException("翻译模型未配置，请在「设置 → API 配置 → 大语言模型」添加模型服务");
        }
        AifanyiProperties.Llm tune = props.getLlm();
        return new LlmConfig(base.trim(), key.trim(), model.trim(),
                tune.isDisableThinking(), tune.getBatchSize(), tune.getConcurrency());
    }

    /** 当前生效的服务行：默认且启用 > 任一启用（默认被停用时不让翻译直接瘫掉）。 */
    private ModelService activeLlmService(Long userId) {
        List<ModelService> rows = serviceMapper.selectList(Wrappers.<ModelService>lambdaQuery()
                .eq(ModelService::getUserId, userId)
                .eq(ModelService::getCategory, "llm")
                .eq(ModelService::getEnabled, 1));
        if (rows.isEmpty()) {
            return null;
        }
        return rows.stream()
                .max(Comparator.comparing((ModelService m) -> m.getIsDefault() != null && m.getIsDefault() == 1)
                        .thenComparing(m -> m.getUpdatedAt() == null ? java.time.LocalDateTime.MIN : m.getUpdatedAt()))
                .orElse(null);
    }

    private LlmConfig toConfig(ModelService svc) {
        AifanyiProperties.Llm tune = props.getLlm();
        return new LlmConfig(
                svc.getBaseUrl() == null ? "" : svc.getBaseUrl().trim(),
                svc.getApiKey() == null ? "" : svc.getApiKey().trim(),
                svc.getModel() == null ? "" : svc.getModel().trim(),
                has(svc.getProtocol()) ? svc.getProtocol() : LlmConfig.PROTO_OPENAI,
                svc.getTimeoutSec() == null ? 60 : svc.getTimeoutSec(),
                tune.isDisableThinking(), tune.getBatchSize(),
                // 该服务单独配了并发就用它，否则跟随全局默认
                svc.getConcurrency() == null || svc.getConcurrency() <= 0
                        ? tune.getConcurrency() : svc.getConcurrency());
    }

    // ---- Agent 模式（实验功能）----

    /**
     * 全能AI翻译的「翻译模型」：负责最终逐行翻译。
     * 未单独配置时回落到默认大语言模型服务——与主/子 Agent 同一回退哲学。
     */
    public LlmConfig effectiveAgentTranslate(Long userId) {
        UserSetting s = load(userId);
        String base = s == null ? null : s.getAgentTranslateBaseUrl();
        String key = s == null ? null : s.getAgentTranslateApiKey();
        String model = s == null ? null : s.getAgentTranslateModel();
        if (!has(base) || !has(key) || !has(model)) {
            return effectiveLlm(userId);
        }
        AifanyiProperties.Llm tune = props.getLlm();
        return new LlmConfig(base.trim(), key.trim(), model.trim(),
                tune.isDisableThinking(), tune.getBatchSize(), tune.getConcurrency());
    }

    /**
     * 主 Agent（场景推测/仲裁）模型配置。未单独配置时回落到翻译 LLM——
     * 让用户不必为了试用 Agent 模式先填两组配置。
     */
    public LlmConfig effectiveAgentMain(Long userId) {
        return agentLlm(userId, true);
    }

    /** 子 Agent（批量抽词）模型配置。未单独配置时回落到翻译 LLM。 */
    public LlmConfig effectiveAgentSub(Long userId) {
        return agentLlm(userId, false);
    }

    private LlmConfig agentLlm(Long userId, boolean main) {
        UserSetting s = load(userId);
        String base = s == null ? null : (main ? s.getAgentMainBaseUrl() : s.getAgentSubBaseUrl());
        String key = s == null ? null : (main ? s.getAgentMainApiKey() : s.getAgentSubApiKey());
        String model = s == null ? null : (main ? s.getAgentMainModel() : s.getAgentSubModel());
        if (!has(base) || !has(key) || !has(model)) {
            // 回落到已配置的翻译模型（不完整则由 effectiveLlm 抛出带指引的错误）
            return effectiveLlm(userId);
        }
        AifanyiProperties.Llm tune = props.getLlm();
        return new LlmConfig(base.trim(), key.trim(), model.trim(),
                tune.isDisableThinking(), tune.getBatchSize(), tune.getConcurrency());
    }

    /**
     * 搜索引擎有效配置。
     * <p><b>未配置返回 none 而非抛异常</b>——与 TTS 不同，「不联网」是合法的降级模式，
     * 不是用户的配置错误。子 Agent 会走「无答案→上下文证据→策略五选一」分支。
     */
    public SearchConfig effectiveSearch(Long userId) {
        UserSetting s = load(userId);
        SearchEngines.Engine e = SearchEngines.byId(s == null ? null : s.getSearchProvider());
        if (e == null || e.wire() == SearchEngines.Wire.NONE) {
            return SearchConfig.none();
        }
        String key = s.getSearchApiKey();
        if (e.needKey() && !has(key)) {
            log.info("搜索引擎 {} 未填 Key，本次不联网", e.id());
            return SearchConfig.none();
        }
        String base = has(s.getSearchBaseUrl()) ? s.getSearchBaseUrl().trim() : e.defaultBaseUrl();
        return new SearchConfig(e.id(), e.wire(), base, key == null ? "" : key.trim(),
                e.defaultTopK(), e.authorityDomains());
    }

    /** LangSmith 观测配置：[apiKey, project]；未配置返回 null（完全不上报）。 */
    public String[] effectiveLangsmith(Long userId) {
        UserSetting s = load(userId);
        String key = s == null ? null : s.getLangsmithApiKey();
        if (!has(key)) {
            return null;
        }
        String proj = s == null ? null : s.getLangsmithProject();
        return new String[]{key.trim(), has(proj) ? proj.trim() : "aifanyi"};
    }

    /** 用户配置的 Qdrant 地址；未填则用全局默认。 */    public String effectiveVectorUrl(Long userId) {
        UserSetting s = load(userId);
        String u = s == null ? null : s.getVectorUrl();
        return has(u) ? u.trim() : props.getAgent().getQdrantUrl();
    }

    /** 搜索引擎卡片列表（设置页展示，含当前用户是否已配好）。 */
    public List<SearchEngineVO> listSearchEngines(Long userId) {
        UserSetting s = load(userId);
        List<SearchEngineVO> out = new ArrayList<>();
        for (SearchEngines.Engine e : SearchEngines.all()) {
            boolean configured = !e.needKey() || (s != null && has(s.getSearchApiKey()));
            out.add(new SearchEngineVO(e.id(), e.name(), e.desc(), e.tag(), e.needKey(),
                    e.defaultBaseUrl(), configured));
        }
        return out;
    }

    /**
     * 按用户选择的 TTS 引擎解析有效配置：
     * edge = 本机 ai-service 转发微软语音，无需任何云端配置；
     * siliconflow = 预设 Base/模型，只需 API Key；
     * openai = 自定义 OpenAI 兼容端点，Base/Key/模型全部必填。
     */
    public TtsConfig effectiveTts(Long userId) {
        UserSetting s = load(userId);
        TtsEngines.Engine e = TtsEngines.byId(s == null ? null : s.getTtsProvider());
        if (e == null) {
            throw new BizException("请先在「设置 → API 配置 → 语音合成（TTS）」选择配音引擎");
        }
        switch (e.id()) {
            case "edge":
                return new TtsConfig("", "", e.defaultModel());
            case "siliconflow": {
                String key = s.getTtsApiKey();
                if (!has(key)) {
                    throw new BizException("硅基流动未配置 API Key，请在「设置 → API 配置 → 语音合成（TTS）」填写");
                }
                return new TtsConfig(e.defaultBaseUrl(), key.trim(), e.defaultModel());
            }
            default: {
                String base = s.getTtsBaseUrl();
                String key = s.getTtsApiKey();
                String model = s.getTtsModel();
                if (!has(base) || !has(key) || !has(model)) {
                    throw new BizException("TTS 未配置完整，请在「设置 → API 配置 → 语音合成（TTS）」填写 Base URL、API Key 和模型");
                }
                return new TtsConfig(base.trim(), key.trim(), model.trim());
            }
        }
    }

    /** 用户选择的 TTS 引擎 id（可能为 null=未选择）。 */
    public String ttsEngineId(Long userId) {
        UserSetting s = load(userId);
        return s == null ? null : s.getTtsProvider();
    }

    /** 引擎卡片列表：注册表 + 每引擎在当前用户下的已配置状态。 */
    public List<TtsEngineVO> listTtsEngines(Long userId) {
        UserSetting s = load(userId);
        List<TtsEngineVO> out = new ArrayList<>();
        for (TtsEngines.Engine e : TtsEngines.all()) {
            boolean configured = switch (e.id()) {
                case "edge" -> true;                        // 免配置，恒可用
                case "siliconflow" -> s != null && has(s.getTtsApiKey());
                default -> s != null && has(s.getTtsBaseUrl()) && has(s.getTtsApiKey()) && has(s.getTtsModel());
            };
            out.add(new TtsEngineVO(e.id(), e.name(), e.desc(), e.tag(), e.needKey(),
                    e.defaultBaseUrl(), e.defaultModel(), configured));
        }
        return out;
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

    // ---- 已配置的模型服务（「设置 → API 配置 → 大语言模型」多服务商管理）----

    /** 服务商注册表（下拉选项）。 */
    public List<LlmProviderVO> listLlmProviders() {
        List<LlmProviderVO> out = new ArrayList<>();
        for (LlmProviders.Provider p : LlmProviders.all()) {
            out.add(new LlmProviderVO(p.id(), p.name(), p.protocol(), p.defaultBaseUrl(),
                    p.defaultModel(), p.keyHint(), p.docsUrl(), p.note(),
                    p.needModel(), p.canListModels(), p.modelLabel(), p.custom()));
        }
        return out;
    }

    /**
     * 服务列表。首次访问时若用户还有旧的 llm_* 单配置而无任何服务行，
     * 自动迁移成一行默认服务——老用户打开新设置页就能看到并继续用原配置。
     */
    public List<ModelServiceVO> listLlmServices(Long userId) {
        List<ModelService> rows = llmRows(userId);
        if (rows.isEmpty()) {
            UserSetting s = load(userId);
            if (s != null && has(s.getLlmBaseUrl()) && has(s.getLlmApiKey()) && has(s.getLlmModel())) {
                ModelService m = new ModelService();
                m.setUserId(userId);
                m.setCategory("llm");
                m.setProvider(guessProvider(s.getLlmBaseUrl()));
                m.setProtocol(LlmConfig.PROTO_OPENAI);
                m.setBaseUrl(s.getLlmBaseUrl().trim());
                m.setApiKey(s.getLlmApiKey().trim());
                m.setModel(s.getLlmModel().trim());
                m.setTimeoutSec(60);
                m.setEnabled(1);
                m.setIsDefault(1);
                serviceMapper.insert(m);
                log.info("用户 {} 的旧翻译模型配置已迁移为模型服务 #{}", userId, m.getId());
                rows = llmRows(userId);
            }
        }
        return rows.stream().map(SettingsService::toVO).toList();
    }

    /** 旧配置迁移时按 Base URL 猜服务商，猜不出就归自定义。 */
    private static String guessProvider(String baseUrl) {
        String u = baseUrl == null ? "" : baseUrl.toLowerCase();
        if (u.contains("deepseek")) return "deepseek";
        if (u.contains("dashscope") || u.contains("aliyun")) return "qwen";
        if (u.contains("googleapis")) return "gemini";
        if (u.contains("api.openai.com")) return "gpt";
        if (u.contains("bigmodel")) return "glm";
        if (u.contains("anthropic")) return "claude";
        if (u.contains("api.x.ai")) return "grok";
        return "custom-openai";
    }

    private List<ModelService> llmRows(Long userId) {
        return serviceMapper.selectList(Wrappers.<ModelService>lambdaQuery()
                .eq(ModelService::getUserId, userId)
                .eq(ModelService::getCategory, "llm")
                .orderByDesc(ModelService::getIsDefault)
                .orderByDesc(ModelService::getUpdatedAt));
    }

    private static ModelServiceVO toVO(ModelService m) {
        return new ModelServiceVO(m.getId(), m.getProvider(), LlmProviders.displayName(m.getProvider()),
                m.getProtocol(), m.getBaseUrl(), secret(m.getApiKey()), m.getModel(),
                m.getTimeoutSec() == null ? 60 : m.getTimeoutSec(),
                m.getConcurrency() == null ? 0 : m.getConcurrency(),
                m.getEnabled() != null && m.getEnabled() == 1,
                m.getIsDefault() != null && m.getIsDefault() == 1,
                m.getUpdatedAt());
    }

    /**
     * 新增/更新服务。返回保存后的行。
     * Base URL 规则：openai/claude 协议缺版本段自动补 /v1（normalizedBaseUrl 回传给前端提示用户）。
     * 更新时 API Key 留空 = 保留原 Key。
     */
    public ModelServiceVO saveLlmService(Long userId, SaveModelServiceReq req) {
        LlmProviders.Provider p = LlmProviders.byId(req.provider());
        if (p == null) {
            throw new BizException("未知服务商: " + req.provider());
        }
        String base = has(req.baseUrl()) ? req.baseUrl().trim() : p.defaultBaseUrl();
        if (!has(base)) {
            throw new BizException("请填写 Base URL");
        }
        base = normalizeBaseUrl(base, p.protocol());
        if (p.needModel() && "模型".equals(p.modelLabel()) && !has(req.model())) {
            throw new BizException("请选择或填写模型名");
        }

        ModelService m;
        boolean isNew = req.id() == null;
        if (isNew) {
            if (!has(req.apiKey())) {
                throw new BizException("请填写 API Key");
            }
            m = new ModelService();
            m.setUserId(userId);
            m.setCategory("llm");
        } else {
            m = ownedService(userId, req.id());
        }
        m.setProvider(p.id());
        m.setProtocol(p.protocol());
        m.setBaseUrl(base);
        if (has(req.apiKey())) {
            m.setApiKey(req.apiKey().trim());
        }
        m.setModel(req.model() == null ? null : req.model().trim());
        int timeout = req.timeoutSec() == null ? 60 : req.timeoutSec();
        m.setTimeoutSec(Math.max(5, Math.min(600, timeout)));
        // 并发：0 = 跟随全局默认；上限 32（再高对同一端点基本只换来限流）。
        // 「跟随默认」必须存 0 而不是 null——MyBatis-Plus 的 updateById 默认跳过 null 字段，
        // 存 null 等于什么也没改，用户把 16 调回默认时会发现根本调不回去。
        m.setConcurrency(req.concurrency() == null || req.concurrency() <= 0
                ? 0 : Math.min(32, req.concurrency()));
        if (m.getEnabled() == null) {
            m.setEnabled(1);
        }

        boolean firstService = llmRows(userId).isEmpty();
        if (isNew) {
            m.setIsDefault(0);
            serviceMapper.insert(m);
        } else {
            serviceMapper.updateById(m);
        }
        // 第一条服务自动设为默认；或调用方显式要求设默认
        if (firstService || Boolean.TRUE.equals(req.makeDefault())) {
            setDefaultLlmService(userId, m.getId());
            m.setIsDefault(1);
        }
        return toVO(serviceMapper.selectById(m.getId()));
    }

    /** openai/claude 协议：Base URL 无版本段时自动补 /v1（如 api.deepseek.com → api.deepseek.com/v1）。 */
    public static String normalizeBaseUrl(String url, String protocol) {
        String u = url == null ? "" : url.trim().replaceAll("/+$", "");
        if (!LlmConfig.PROTO_OPENAI.equals(protocol) && !LlmConfig.PROTO_CLAUDE.equals(protocol)) {
            return u;
        }
        // 路径里已有 /v1、/v4、/v1beta 等版本段就不动（智谱 /v4、Gemini /v1beta/openai）
        if (u.matches("(?i).*/v\\d+[a-z]*(/.*)?$") || u.matches("(?i).*/v\\d+[a-z]*/.*")) {
            return u;
        }
        return u + "/v1";
    }

    private ModelService ownedService(Long userId, Long id) {
        ModelService m = serviceMapper.selectById(id);
        if (m == null || !m.getUserId().equals(userId)) {
            throw new BizException(404, "服务不存在");
        }
        return m;
    }

    public void deleteLlmService(Long userId, Long id) {
        ModelService m = ownedService(userId, id);
        serviceMapper.deleteById(m.getId());
        // 删掉的是默认服务 → 把最近更新的启用服务顶上，翻译不断档
        if (m.getIsDefault() != null && m.getIsDefault() == 1) {
            llmRows(userId).stream()
                    .filter(r -> r.getEnabled() != null && r.getEnabled() == 1)
                    .findFirst()
                    .ifPresent(r -> setDefaultLlmService(userId, r.getId()));
        }
    }

    /** 设为默认（自动启用），并清掉其他行的默认标记。 */
    public void setDefaultLlmService(Long userId, Long id) {
        ModelService target = ownedService(userId, id);
        for (ModelService r : llmRows(userId)) {
            boolean shouldBeDefault = r.getId().equals(target.getId());
            int def = shouldBeDefault ? 1 : 0;
            Integer cur = r.getIsDefault() == null ? 0 : r.getIsDefault();
            boolean enable = shouldBeDefault && (r.getEnabled() == null || r.getEnabled() != 1);
            if (cur != def || enable) {
                r.setIsDefault(def);
                if (shouldBeDefault) {
                    r.setEnabled(1);
                }
                serviceMapper.updateById(r);
            }
        }
    }

    public void toggleLlmService(Long userId, Long id, boolean enabled) {
        ModelService m = ownedService(userId, id);
        m.setEnabled(enabled ? 1 : 0);
        serviceMapper.updateById(m);
    }

    /**
     * 测试连接：按表单/已存服务的配置真实翻译一行 "Hello"。
     * 不落库——填完还没保存也能测。返回人话结果（含译文与耗时）。
     */
    public String testLlmService(Long userId, SaveModelServiceReq req, LlmTranslator translator) {
        LlmProviders.Provider p = LlmProviders.byId(req.provider());
        if (p == null) {
            throw new BizException("未知服务商: " + req.provider());
        }
        String base = has(req.baseUrl()) ? req.baseUrl().trim() : p.defaultBaseUrl();
        if (!has(base)) {
            throw new BizException("请填写 Base URL");
        }
        base = normalizeBaseUrl(base, p.protocol());
        String key = req.apiKey();
        String model = req.model();
        if ((!has(key) || (p.needModel() && !has(model))) && req.id() != null) {
            // Key/模型留空 → 用已存服务的
            ModelService saved = ownedService(userId, req.id());
            if (!has(key)) {
                key = saved.getApiKey();
            }
            if (!has(model)) {
                model = saved.getModel();
            }
        }
        if (!has(key)) {
            throw new BizException("请填写 API Key（或先保存服务）");
        }
        AifanyiProperties.Llm tune = props.getLlm();
        int timeout = req.timeoutSec() == null ? 60 : Math.max(5, Math.min(600, req.timeoutSec()));
        LlmConfig cfg = new LlmConfig(base, key.trim(), model == null ? "" : model.trim(),
                p.protocol(), timeout, tune.isDisableThinking(), 1, 1);
        long t0 = System.currentTimeMillis();
        List<String> out = translator.translate(List.of("Hello, nice to meet you."), "中文", cfg);
        long ms = System.currentTimeMillis() - t0;
        String translated = out.isEmpty() ? "" : out.get(0);
        if (!has(translated) || "Hello, nice to meet you.".equals(translated)) {
            throw new BizException("连接失败：端点没有返回译文，请检查 Base URL / Key / 模型是否正确（后台日志有详细错误）");
        }
        return "连接正常（" + (ms / 1000.0) + "s）：Hello, nice to meet you. → " + translated;
    }

    /** 语音识别引擎卡片（设置页「语音识别」面板）。 */
    public List<AsrEngineVO> listAsrEngines(Long userId) {
        UserSetting s = load(userId);
        List<AsrEngineVO> out = new ArrayList<>();
        // 卡片上的倍速取该引擎最常用的一档：本地=large-v3（默认推荐档），云端=Groq large-v3
        out.add(new AsrEngineVO("local", "本地 Whisper", "内置离线识别，零配置免费，模型大小在任务里选（首次使用自动加载）",
                "free", false, true, speedOf("local-large-v3")));
        out.add(new AsrEngineVO("groq", "Groq 云端识别", "whisper-large-v3 云端加速，有免费额度；国内网络需要魔法",
                "cloud", true, s != null && has(s.getGroqApiKey()), speedOf("groq")));
        return out;
    }

    /**
     * 任务表单里各识别档位的实测倍速，供前端算「预计耗时」。
     * <p>本地档会按 ai-service 实际跑在 GPU 还是 CPU 上自动调整（见 {@link AsrSpeed}）。
     */
    public List<AsrSpeed.Option> asrSpeedOptions() {
        return AsrSpeed.options(aiLauncher.resolvedDevice());
    }

    private double speedOf(String value) {
        return asrSpeedOptions().stream()
                .filter(o -> o.value().equals(value))
                .findFirst().map(AsrSpeed.Option::speedFactor).orElse(0.0);
    }


    /**
     * 拉取端点的可用模型列表（GET {baseUrl}/models，claude 协议换 x-api-key 头）。
     * baseUrl/apiKey 传空则回退到当前生效的模型服务。
     */
    public List<String> listLlmModels(Long userId, String baseUrl, String apiKey, String protocol) {
        String base = has(baseUrl) ? baseUrl.trim() : null;
        String key = has(apiKey) ? apiKey.trim() : null;
        String proto = has(protocol) ? protocol : LlmConfig.PROTO_OPENAI;
        if (base == null || key == null) {
            ModelService svc = activeLlmService(userId);
            if (svc != null) {
                if (base == null) base = svc.getBaseUrl();
                if (key == null) key = svc.getApiKey();
                if (!has(protocol)) proto = svc.getProtocol();
            } else {
                UserSetting s = load(userId);
                if (base == null) base = s == null ? null : s.getLlmBaseUrl();
                if (key == null) key = s == null ? null : s.getLlmApiKey();
            }
        }
        if (has(base)) {
            base = normalizeBaseUrl(base, proto);
        }
        return fetchModels(base, key, proto);
    }

    /**
     * 拉取 TTS 端点的模型列表，回退到已保存的 TTS 配置。
     * 聚合端点（如硅基流动）的 /models 混着聊天模型，按名称过滤出 TTS 类；全被滤掉则原样返回兜底。
     */
    public List<String> listTtsModels(Long userId, String baseUrl, String apiKey) {
        UserSetting s = load(userId);
        String base = has(baseUrl) ? baseUrl.trim() : (s == null ? null : s.getTtsBaseUrl());
        String key = has(apiKey) ? apiKey.trim() : (s == null ? null : s.getTtsApiKey());
        List<String> all = fetchModels(base, key);
        List<String> tts = all.stream()
                .filter(m -> m.toLowerCase().matches(".*(tts|cosyvoice|fish-speech|speech|voice|audio).*"))
                .toList();
        return tts.isEmpty() ? all : tts;
    }

    /**
     * 拉取全能AI翻译各组端点（translate/main/sub）的模型列表。
     * 回退链与 {@link #effectiveAgentTranslate}/{@link #effectiveAgentMain}/{@link #effectiveAgentSub} 保持一致：
     * 表单填了用表单 → 已存过该组配置用已存的 → 都没有落到默认大语言模型服务。
     * 若不与运行时同链，按钮拉出来的列表就会和实际调用的端点对不上。
     */
    public List<String> listAgentModels(Long userId, String baseUrl, String apiKey, String mode) {
        UserSetting s = load(userId);
        String savedBase = null;
        String savedKey = null;
        if (s != null) {
            switch (mode) {
                case "translate" -> {
                    savedBase = s.getAgentTranslateBaseUrl();
                    savedKey = s.getAgentTranslateApiKey();
                }
                case "main" -> {
                    savedBase = s.getAgentMainBaseUrl();
                    savedKey = s.getAgentMainApiKey();
                }
                default -> {
                    savedBase = s.getAgentSubBaseUrl();
                    savedKey = s.getAgentSubApiKey();
                }
            }
        }
        String base = has(baseUrl) ? baseUrl.trim() : savedBase;
        String key = has(apiKey) ? apiKey.trim() : savedKey;
        String proto = LlmConfig.PROTO_OPENAI;
        if (!has(base) || !has(key)) {
            // 该组未配置 → 回落当前生效的模型服务（与运行时回落一致）
            ModelService svc = activeLlmService(userId);
            if (svc != null) {
                base = svc.getBaseUrl();
                key = svc.getApiKey();
                proto = svc.getProtocol();
            } else {
                base = s == null ? null : s.getLlmBaseUrl();
                key = s == null ? null : s.getLlmApiKey();
            }
        }
        return fetchModels(base, key, proto);
    }

    /** OpenAI 兼容端点的模型列表（旧签名，Gemini/TTS 复用）。 */
    private List<String> fetchModels(String base, String key) {
        return fetchModels(base, key, LlmConfig.PROTO_OPENAI);
    }

    private List<String> fetchModels(String base, String key, String protocol) {
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
            var spec = client.get().uri(url);
            if (LlmConfig.PROTO_CLAUDE.equals(protocol)) {
                spec = spec.header("x-api-key", key).header("anthropic-version", "2023-06-01");
            } else {
                spec = spec.header("Authorization", "Bearer " + key);
            }
            body = spec.retrieve().body(String.class);
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
