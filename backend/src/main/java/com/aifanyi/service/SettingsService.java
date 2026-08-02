package com.aifanyi.service;

import com.aifanyi.common.BizException;
import com.aifanyi.agent.search.SearchConfig;
import com.aifanyi.agent.search.SearchEngines;
import com.aifanyi.config.AifanyiProperties;
import com.aifanyi.controller.dto.SettingsDtos.SearchEngineVO;
import com.aifanyi.controller.dto.SettingsDtos.SecretView;
import com.aifanyi.controller.dto.SettingsDtos.SettingsVO;
import com.aifanyi.controller.dto.SettingsDtos.TtsEngineVO;
import com.aifanyi.controller.dto.SettingsDtos.UpdateSettingsReq;
import com.aifanyi.entity.UserSetting;
import com.aifanyi.llm.GeminiConfig;
import com.aifanyi.llm.LlmConfig;
import com.aifanyi.tts.TtsConfig;
import com.aifanyi.tts.TtsEngines;
import com.aifanyi.mapper.UserSettingMapper;
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
                s == null ? null : s.getGeminiModel(),
                s == null ? null : s.getTtsProvider(),
                s == null ? null : s.getTtsBaseUrl(),
                secret(s == null ? null : s.getTtsApiKey()),
                s == null ? null : s.getTtsModel(),
                s == null ? null : s.getStylePrompt(),
                s == null ? null : s.getTermExtractPrompt(),
                // 内置模板原样展示（含 {sourceLang}/{targetLang} 占位符），用户可复制为自定义起点
                com.aifanyi.llm.GeminiClient.defaultTermPrompt(),
                // ---- Agent 模式 ----
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
        if (has(req.geminiBaseUrl())) s.setGeminiBaseUrl(req.geminiBaseUrl().trim());
        if (has(req.geminiApiKey())) s.setGeminiApiKey(req.geminiApiKey().trim());
        if (has(req.geminiModel())) s.setGeminiModel(req.geminiModel().trim());
        if (has(req.ttsProvider())) s.setTtsProvider(req.ttsProvider().trim());
        if (has(req.ttsBaseUrl())) s.setTtsBaseUrl(req.ttsBaseUrl().trim());
        if (has(req.ttsApiKey())) s.setTtsApiKey(req.ttsApiKey().trim());
        if (has(req.ttsModel())) s.setTtsModel(req.ttsModel().trim());
        // ---- Agent 模式：主/子 Agent 两组 + 搜索引擎 ----
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
        if (req.termExtractPrompt() != null) {
            // 同理：空串=清空（回退内置提示词）；非空=启用自定义
            String t = req.termExtractPrompt().trim();
            s.setTermExtractPrompt(t);
        }
        if (isNew) {
            mapper.insert(s);
        } else {
            mapper.updateById(s);
        }
    }

    /** 用户自定义的术语抽取提示词（空=未启用，走内置）。 */
    public String effectiveTermPrompt(Long userId) {
        UserSetting s = load(userId);
        String p = s == null ? null : s.getTermExtractPrompt();
        return has(p) ? p : null;
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

    // ---- Agent 模式（实验功能）----

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
            throw new BizException("请先在「设置 → API 密钥 → 语音合成 TTS」选择配音引擎");
        }
        switch (e.id()) {
            case "edge":
                return new TtsConfig("", "", e.defaultModel());
            case "siliconflow": {
                String key = s.getTtsApiKey();
                if (!has(key)) {
                    throw new BizException("硅基流动未配置 API Key，请在「设置 → API 密钥 → 语音合成 TTS」填写");
                }
                return new TtsConfig(e.defaultBaseUrl(), key.trim(), e.defaultModel());
            }
            default: {
                String base = s.getTtsBaseUrl();
                String key = s.getTtsApiKey();
                String model = s.getTtsModel();
                if (!has(base) || !has(key) || !has(model)) {
                    throw new BizException("TTS 未配置完整，请在「设置 → API 密钥 → 语音合成 TTS」填写 Base URL、API Key 和模型");
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
     * 模型后缀改写（vertex2openai 代理约定）：
     *  - 剥掉 -search——联网 grounding 只对抽术语有价值，批量翻译带上只会又慢又贵；
     *  - 追加 -nothinking——把 Gemini 思考预算压到 0（pro 系代理自动用最低 128）。
     *    实测 gemini-3-flash 同批字幕：默认思考 ~15s/批（多烧 ~1300 思考 token），关思考 ~3s/批。
     * disableThinking 固定 false（有意为之）：thinking:{type:disabled} 是 DeepSeek 系方言字段，
     * Gemini 代理不认识；关思考走上面的 -nothinking 模型名约定。
     * 并发压到 KB_MAX_CONCURRENCY——429 会被 OpenAiTranslator 静默回退为原文，宁可慢一点。
     */
    private LlmConfig effectiveKbLlm(Long userId) {
        GeminiConfig g = effectiveGemini(userId);
        String model = g.model();
        if (model.endsWith("-search")) {
            model = model.substring(0, model.length() - "-search".length());
        }
        if (!model.endsWith("-nothinking")) {
            model = model + "-nothinking";
        }
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
     * 拉取主/子 Agent 端点的模型列表。
     * 回退链与 {@link #effectiveAgentMain}/{@link #effectiveAgentSub} 保持一致：
     * 表单填了用表单 → 已存过该组 Agent 配置用已存的 → 都没有落到翻译模型配置。
     * 若不与运行时同链，按钮拉出来的列表就会和实际调用的端点对不上。
     */
    public List<String> listAgentModels(Long userId, String baseUrl, String apiKey, boolean main) {
        UserSetting s = load(userId);
        String savedBase = s == null ? null : (main ? s.getAgentMainBaseUrl() : s.getAgentSubBaseUrl());
        String savedKey = s == null ? null : (main ? s.getAgentMainApiKey() : s.getAgentSubApiKey());
        String base = has(baseUrl) ? baseUrl.trim() : savedBase;
        String key = has(apiKey) ? apiKey.trim() : savedKey;
        if (!has(base) || !has(key)) {
            // 该组 Agent 未配置 → 回落翻译模型端点（与运行时 agentLlm 的回落一致）
            base = s == null ? null : s.getLlmBaseUrl();
            key = s == null ? null : s.getLlmApiKey();
        }
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
