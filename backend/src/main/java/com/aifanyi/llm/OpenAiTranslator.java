package com.aifanyi.llm;

import com.aifanyi.common.BizException;
import com.aifanyi.config.AifanyiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 通用翻译实现：OpenAI 兼容端点（OpenAI / DeepSeek / 通义 / 智谱 / 中转站…）+
 * Claude（Anthropic messages 协议）+ 传统机翻（谷歌/微软/DeepL，经 MtTranslateClient）。
 * 协议由 LlmConfig.protocol 决定，调用方无感。
 * 关键优化：推理模型关闭思维链提速、多批次并发、强制 json_object、严格保持行数顺序。
 * 配置来自每次调用传入的 LlmConfig（用户设置优先）。
 */
@Slf4j
@Component
public class OpenAiTranslator implements LlmTranslator {

    private final ObjectMapper mapper;
    private final MtTranslateClient mt;
    /** 并发兜底：所有任务共用的在途请求上限，防多任务并跑时把端点打爆或触发限流。 */
    private final Semaphore globalGate;
    /** 用户没给该服务配并发时的默认值。 */
    private final int defaultConcurrency;

    public OpenAiTranslator(AifanyiProperties props, ObjectMapper mapper, MtTranslateClient mt) {
        this.mapper = mapper;
        this.mt = mt;
        this.defaultConcurrency = Math.max(1, props.getLlm().getConcurrency());
        this.globalGate = new Semaphore(Math.max(1, props.getLlm().getMaxTotalConcurrency()), true);
    }

    /**
     * 本次调用实际用几路并发：取该模型服务配置的值，落在 [1, 全局上限] 内；没配就用默认值。
     * <p>旧实现是在 Bean 构造时按环境变量把线程池大小焊死，且 {@code cfg.concurrency()}
     * 压根没被读过——于是设置页调不了、两个任务还要抢同一个池子。
     */
    private int effectiveConcurrency(LlmConfig cfg, int batches) {
        int want = cfg.concurrency() > 0 ? cfg.concurrency() : defaultConcurrency;
        return Math.max(1, Math.min(want, batches));
    }

    @Override
    public List<String> translate(List<String> sources, String targetLang, LlmConfig cfg,
                                  java.util.Map<String, String> glossary, String stylePrompt,
                                  TranslateHooks hooks) {
        TranslateHooks hk = hooks == null ? TranslateHooks.none() : hooks;
        if (cfg.isMt()) {
            // 传统机翻：无提示词能力，风格/术语忽略，按段直接翻译
            List<String> out = mt.translateAll(sources, targetLang, cfg).lines();
            bump(hk, sources.size());
            return out;
        }
        if (!StringUtils.hasText(cfg.apiKey())) {
            throw new BizException("未配置翻译模型 API Key，请在「设置 → API 配置 → 大语言模型」填写");
        }
        java.util.Map<String, String> gloss = glossary == null ? java.util.Map.of() : glossary;
        String style = StringUtils.hasText(stylePrompt) ? stylePrompt.trim() : null;
        int batchSize = Math.max(1, cfg.batchSize());

        List<List<String>> batches = new ArrayList<>();
        for (int i = 0; i < sources.size(); i += batchSize) {
            batches.add(sources.subList(i, Math.min(i + batchSize, sources.size())));
        }
        if (batches.isEmpty()) {
            return new ArrayList<>();
        }

        int concurrency = effectiveConcurrency(cfg, batches.size());
        // 每次调用自带线程池：并发度由该模型服务的配置决定，用完即关。
        // 全局在途上限由 globalGate 兜底，所以这里放开也不会压垮端点。
        ExecutorService pool = Executors.newFixedThreadPool(concurrency, r -> {
            Thread t = new Thread(r, "llm-translate");
            t.setDaemon(true);
            return t;
        });
        AtomicInteger doneLines = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        long t0 = System.currentTimeMillis();
        List<String> out = new ArrayList<>(sources.size());
        try {
            List<Future<List<String>>> futures = new ArrayList<>(batches.size());
            for (List<String> batch : batches) {
                futures.add(pool.submit(() -> {
                    // 排队期间调用方可能已经叫停（如用户删了任务）：轮到自己时先问一句，
                    // 不必把剩下的批次全烧完才停
                    if (!hk.shouldContinue()) {
                        skipped.incrementAndGet();
                        return new ArrayList<>(batch);
                    }
                    globalGate.acquire();
                    try {
                        return translateBatch(batch, targetLang, cfg, gloss, style);
                    } finally {
                        globalGate.release();
                        // 进度是旁路信号：无论这批成没成都要往前走，且绝不能因它抛异常毁掉翻译
                        bump(hk, doneLines.addAndGet(batch.size()));
                    }
                }));
            }
            for (int i = 0; i < futures.size(); i++) {
                try {
                    out.addAll(futures.get(i).get());
                } catch (Exception e) {
                    log.warn("第{}批翻译失败，保留原文: {}", i, e.getMessage());
                    out.addAll(batches.get(i));
                }
            }
        } finally {
            pool.shutdownNow();
        }
        if (skipped.get() > 0) {
            log.info("翻译中止：{} 行 / {} 批中有 {} 批被调用方叫停（保留原文）",
                    sources.size(), batches.size(), skipped.get());
        } else {
            log.info("翻译完成: {} 行 / {} 批 / {} 路并发, 模型 {}, 总耗时 {}s",
                    sources.size(), batches.size(), concurrency, cfg.model(),
                    String.format(java.util.Locale.ROOT, "%.1f", (System.currentTimeMillis() - t0) / 1000.0));
        }
        return out;
    }

    /** 回调调用方的进度计数；调用方的回调抛异常不该连累翻译本身。 */
    private void bump(TranslateHooks hooks, int doneLines) {
        if (hooks.onLineDone() == null) {
            return;
        }
        try {
            hooks.onLineDone().accept(doneLines);
        } catch (Exception e) {
            log.debug("翻译进度回调异常（忽略）: {}", e.toString());
        }
    }

    private List<String> translateBatch(List<String> batch, String targetLang, LlmConfig cfg,
                                        java.util.Map<String, String> glossary, String stylePrompt) {
        return translateBatch(batch, targetLang, cfg, glossary, stylePrompt, null, null).lines();
    }

    /** 单批产出：译文行（与输入等长、未译处为原文）+ 未译行的批内下标。 */
    public record BatchOutcome(List<String> lines, List<Integer> missing) {
    }

    /**
     * 翻译单个批次，可携带前文上下文（Agent 模式 ⑧ 的滚动窗口用）。
     * <p>公开单批接口是为了让调用方自己控制分批与并发策略——Agent 模式需要
     * 「组内串行带上下文、组间并发」，而本类的 translate() 是全批次并发，两者并存。
     * <p>返回 {@link BatchOutcome}：调用方能知道哪些行最终没译成（保留了原文），
     * 从而把「部分未译」如实标到任务上，而不是静默混在译文里让用户自己发现。
     *
     * @param priorSource 前文原文（只读上下文，不参与 index 对齐、不要求返回译文）
     * @param priorTarget 前文译文（可为 null；组内串行时才有）
     */
    public BatchOutcome translateBatchWithContext(List<String> batch, String targetLang, LlmConfig cfg,
                                                  java.util.Map<String, String> glossary, String stylePrompt,
                                                  List<String> priorSource, List<String> priorTarget) {
        if (cfg.isMt()) {
            MtTranslateClient.MtOutcome o = mt.translateAll(batch, targetLang, cfg);
            return new BatchOutcome(o.lines(), o.missing());
        }
        if (!StringUtils.hasText(cfg.apiKey())) {
            throw new BizException("未配置翻译模型 API Key，请在「设置 → API 配置 → 大语言模型」填写");
        }
        return translateBatch(batch, targetLang, cfg,
                glossary == null ? java.util.Map.of() : glossary,
                StringUtils.hasText(stylePrompt) ? stylePrompt.trim() : null,
                priorSource, priorTarget);
    }

    /** 补翻轮的分片大小：批越长，模型越容易再次自行断句串位，所以重发时切小。 */
    private static final int REPAIR_CHUNK = 12;

    private BatchOutcome translateBatch(List<String> batch, String targetLang, LlmConfig cfg,
                                        java.util.Map<String, String> glossary, String stylePrompt,
                                        List<String> priorSource, List<String> priorTarget) {
        int n = batch.size();
        String[] result = batch.toArray(new String[0]); // 默认=原文
        boolean[] filled = new boolean[n];

        // 最多三轮请求，每轮只发「还没拿到译文」的行（首轮=全批）。
        // 旧策略是「部分成功就接受、不为几行重发整批」——结果模型漏行时那些行直接保留原文
        // （实测 deepseek-v4-pro 一次漏 17/40，成品里塌出一大块没翻译的文本）。
        // 现在漏行只重发缺的小子集：请求便宜，输出小也天然规避截断。
        // 「漏行」不止是没返回，还包括<b>返回了但对不上原文</b>（错位），二者共用这条修复路径。
        for (int attempt = 1; attempt <= 3 && !allFilled(filled); attempt++) {
            List<Integer> todo = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if (!filled[i]) {
                    todo.add(i);
                }
            }
            // 首轮整批发（批大小由配置决定）；补翻轮切成小片，逐片重发
            int chunk = attempt == 1 ? todo.size() : Math.min(todo.size(), REPAIR_CHUNK);
            boolean anyFailed = false;
            for (int from = 0; from < todo.size(); from += chunk) {
                List<Integer> part = new ArrayList<>(todo.subList(from, Math.min(from + chunk, todo.size())));
                List<String> sub = new ArrayList<>(part.size());
                for (int g : part) {
                    sub.add(batch.get(g));
                }
                try {
                    String sys = buildSystemPrompt(targetLang, sub, glossary, stylePrompt, priorSource, priorTarget);
                    String content = chat(sys, buildUserPrompt(sub), cfg);
                    int drift = applyTranslations(content, sub, result, filled, part);
                    if (drift > 0) {
                        // 这条日志是错位问题今后唯一的现场：静默错位曾让 22.6% 的字幕串行而无人察觉
                        log.warn("第{}次尝试：{} 行中有 {} 行译文对不上原文（疑似错位），已判为未译待重发",
                                attempt, sub.size(), drift);
                    }
                } catch (Exception e) {
                    anyFailed = true;
                    log.warn("批量翻译失败(第{}次尝试, {} 行): {}", attempt, sub.size(), e.getMessage());
                }
            }
            // 网络/限流类失败立刻原样重试大概率还挂，等一拍再试；被取消就立刻收尾
            if (anyFailed && attempt < 3 && !sleepQuietly(1500L * attempt)) {
                break;
            }
        }
        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (!filled[i]) {
                missing.add(i);
            }
        }
        if (!missing.isEmpty()) {
            log.warn("补翻后仍有 {}/{} 行未译，这些行保留原文", missing.size(), n);
        }
        return new BatchOutcome(new ArrayList<>(List.of(result)), missing);
    }

    private static boolean sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 把模型返回的译文回填到 result（只填尚未填的行）。
     * <p>mapping 把「本次请求内的行号」映射回批内行号——补翻只发缺行子集，
     * 模型看到的 i 是子集内的 0..m-1，不经映射会填错行。
     * 兼容三种返回：①[{"i":行号,"s":"原文锚点","t":"译文"}] ②{"translations":[...]} 同上
     * ③长度与请求行数一致的纯字符串数组（按位置回填，兜底）。
     * <p><b>空白译文不算数</b>：回填空串等于把原文抹掉，比不翻还糟——留给下一轮补翻。
     *
     * <p><b>回填前必须先确认「这条译文真的属于这一行」</b>：行号是模型自己写的，它串位时
     * 行号照样连号，光看 i 看不出任何异常。所以要么用锚点 s 核对（{@link AlignmentGuard#anchorMatches}），
     * 要么在模型不给锚点时用启发式兜底（{@link AlignmentGuard#suspectDrift}）。
     * 判定可疑的行<b>不回填</b>，自然落进下一轮补翻重发——这正是既有的漏行修复路径。
     *
     * @param sources 本次请求发出去的原文行（与 mapping 等长、同序），校验锚点用
     * @return 因「对不上原文」被拒的行数（0 表示这批没发现错位）
     */
    int applyTranslations(String content, List<String> sources, String[] result,
                          boolean[] filled, List<Integer> mapping) {
        List<Entry> entries = parseEntries(content, mapping.size());
        if (entries.isEmpty()) {
            return 0;
        }
        int m = mapping.size();

        // 有锚点 → 逐条核对。但要先分清两件事：
        //   「模型串位了」——锚点是真原文片段，只是安到了别的行上（能匹配请求内的其他行）
        //   「模型没照做」——锚点根本不是原文（一行都对不上），此时全盘拒绝会把好译文也丢光
        boolean anyAnchor = entries.stream().anyMatch(e -> StringUtils.hasText(e.anchor));
        if (anyAnchor) {
            int hitSlot = 0;
            int hitAny = 0;
            for (Entry e : entries) {
                if (!StringUtils.hasText(e.anchor)) {
                    continue;
                }
                if (AlignmentGuard.anchorMatches(e.anchor, sources.get(e.index))) {
                    hitSlot++;
                }
                if (AlignmentGuard.anchorMatchesAny(e.anchor, sources)) {
                    hitAny++;
                }
            }
            if (hitSlot > 0 || hitAny > 0 || entries.size() < 4) {
                return fillAnchored(entries, sources, result, filled, mapping);
            }
            log.warn("模型返回的 s 字段不是原文片段（{} 条无一命中），本批退回启发式校验", entries.size());
        }
        return fillHeuristic(entries, sources, result, filled, mapping, m);
    }

    /** 锚点档：只回填「锚点确实落在该行原文上」的条目，其余留给补翻。 */
    private int fillAnchored(List<Entry> entries, List<String> sources, String[] result,
                             boolean[] filled, List<Integer> mapping) {
        int rejected = 0;
        for (Entry e : entries) {
            int g = mapping.get(e.index);
            if (filled[g]) {
                continue;
            }
            // 没给锚点的条目同样无从核对，一并留给补翻——重发几行很便宜，错位一片很贵
            if (!AlignmentGuard.anchorMatches(e.anchor, sources.get(e.index))) {
                rejected++;
                continue;
            }
            result[g] = e.text;
            filled[g] = true;
        }
        return rejected;
    }

    /** 启发式档（模型不给锚点时）：先按位置/行号摆好，再由 AlignmentGuard 挑出可疑行剔除。 */
    private int fillHeuristic(List<Entry> entries, List<String> sources, String[] result,
                              boolean[] filled, List<Integer> mapping, int m) {
        String[] proposed = new String[m];
        for (Entry e : entries) {
            proposed[e.index] = e.text;
        }
        java.util.Set<Integer> suspect = AlignmentGuard.suspectDrift(sources, proposed);
        int rejected = 0;
        for (int i = 0; i < m; i++) {
            if (proposed[i] == null) {
                continue;
            }
            int g = mapping.get(i);
            if (filled[g]) {
                continue;
            }
            if (suspect.contains(i)) {
                rejected++;
                continue;
            }
            result[g] = proposed[i];
            filled[g] = true;
        }
        return rejected;
    }

    /** 模型返回的一条译文：请求内行号 + 原文锚点（可能没有）+ 译文。 */
    private record Entry(int index, String anchor, String text) {
    }

    /**
     * 解析模型返回，抽出合法条目（行号越界、译文空白的直接丢弃）。
     * 纯字符串数组且行数恰好相等时按位置生成行号，此时没有锚点。
     */
    private List<Entry> parseEntries(String content, int m) {
        List<Entry> out = new ArrayList<>();
        if (content == null) {
            return out;
        }
        String s = content.trim();
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start >= 0 && end > start) {
            s = s.substring(start, end + 1);
        }
        JsonNode arr;
        try {
            arr = mapper.readTree(s).path("translations");
        } catch (Exception e) {
            return out;
        }
        if (!arr.isArray()) {
            return out;
        }
        boolean allTextual = true;
        for (JsonNode node : arr) {
            if (!node.isTextual()) {
                allTextual = false;
                break;
            }
        }
        if (allTextual && arr.size() == m) {
            for (int i = 0; i < m; i++) {
                String text = arr.get(i).asText();
                if (StringUtils.hasText(text)) {
                    out.add(new Entry(i, null, text));
                }
            }
            return out;
        }
        for (JsonNode node : arr) {
            int i = node.has("i") ? node.path("i").asInt(-1) : node.path("index").asInt(-1);
            if (i < 0 || i >= m) {
                continue;
            }
            JsonNode t = node.has("t") ? node.path("t") : node.path("translation");
            if (t.isMissingNode()) {
                t = node.path("text");
            }
            String text = t.asText("");
            if (!StringUtils.hasText(text)) {
                continue;
            }
            JsonNode a = node.has("s") ? node.path("s") : node.path("source");
            if (a.isMissingNode()) {
                a = node.path("src");
            }
            out.add(new Entry(i, a.asText(""), text));
        }
        return out;
    }

    private boolean allFilled(boolean[] filled) {
        for (boolean b : filled) if (!b) return false;
        return true;
    }

    private String buildSystemPrompt(String targetLang, List<String> batch,
                                     java.util.Map<String, String> glossary, String stylePrompt,
                                     List<String> priorSource, List<String> priorTarget) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是专业的视频字幕翻译。把 lines 数组里每个对象的 text 翻译成").append(targetLang);
        if (StringUtils.hasText(stylePrompt)) {
            // 用户指定风格时，默认的"口语化"让位，避免与用户风格冲突
            sb.append("。要求：忠实原意、避免翻译腔；保留专有名词。")
                    .append("【翻译风格要求，必须遵循】").append(stylePrompt.trim()).append("。");
        } else {
            sb.append("。要求：忠实自然、口语化、避免翻译腔；保留专有名词。");
        }
        // 注入当前批次中出现的术语对照（必须遵守，优先级高于风格）
        if (glossary != null && !glossary.isEmpty()) {
            String joined = String.join("\n", batch);
            StringBuilder terms = new StringBuilder();
            for (java.util.Map.Entry<String, String> e : glossary.entrySet()) {
                if (StringUtils.hasText(e.getKey()) && joined.contains(e.getKey())) {
                    terms.append("「").append(e.getKey()).append("」=>「").append(e.getValue()).append("」；");
                }
            }
            if (terms.length() > 0) {
                sb.append("【术语对照，必须严格按此翻译，不得改写】：").append(terms);
            }
        }
        sb.append("只返回 JSON 对象：{\"translations\": [{\"i\": 行号, \"s\": \"该行原文开头几个词\", \"t\": \"译文\"}]}，")
                .append("其中 i 必须与输入对象的 i 一一对应；")
                // s 是行号的校验锚点：模型串位时行号还是连号的，只有抄回来的原文片段能暴露它翻的到底是哪行
                .append("s 必须从该行 text 的开头原样照抄前几个词（不要翻译 s，它只用于核对行号是否对上）；")
                .append("必须为每一个输入行各返回恰好一条，不要合并、拆分或遗漏任何行，不要改变 i，不要输出任何额外文字。")
                // 直指本次故障：ASR 常把长句从中间劈开，模型看到半截话就想自行重新断句
                .append("特别注意：字幕经常从句子中间断开，若相邻两行合起来才是一句完整的话，")
                .append("仍必须分别返回两条、各自对应各自的 i 与 s，不得把两行并成一条，也不得把一行拆成两条。");
        // 滚动窗口（Agent 模式 ⑧）：前文只读，用于保持指代/称谓/语气连续，不参与 index 对齐
        appendContext(sb, priorSource, priorTarget);
        return sb.toString();
    }

    /** 把前文原文/译文作为只读上下文附在提示词末尾。 */
    private void appendContext(StringBuilder sb, List<String> priorSource, List<String> priorTarget) {
        boolean hasSrc = priorSource != null && !priorSource.isEmpty();
        boolean hasTgt = priorTarget != null && !priorTarget.isEmpty();
        if (!hasSrc && !hasTgt) {
            return;
        }
        sb.append("\n【前文上下文，仅供参考以保持指代、称谓、语气一致，不要翻译它、不要在结果中返回它】\n");
        if (hasSrc) {
            sb.append("前文原文：").append(String.join(" ", priorSource)).append('\n');
        }
        if (hasTgt) {
            sb.append("前文译文：").append(String.join(" ", priorTarget)).append('\n');
        }
    }

    private String buildUserPrompt(List<String> batch) {
        try {
            ArrayNode arr = mapper.createArrayNode();
            for (int i = 0; i < batch.size(); i++) {
                ObjectNode line = mapper.createObjectNode();
                line.put("i", i);
                line.put("text", batch.get(i));
                arr.add(line);
            }
            ObjectNode obj = mapper.createObjectNode();
            obj.set("lines", arr);
            return mapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new BizException("构造翻译请求失败: " + e.getMessage());
        }
    }

    private String chat(String system, String user, LlmConfig cfg) {
        if (cfg.isClaude()) {
            return chatClaude(system, user, cfg);
        }
        ObjectNode req = mapper.createObjectNode();
        req.put("model", cfg.model());
        req.put("temperature", 0.3);
        ObjectNode rf = req.putObject("response_format");
        rf.put("type", "json_object");
        if (cfg.disableThinking()) {
            req.putObject("thinking").put("type", "disabled");
        }
        ArrayNode messages = req.putArray("messages");
        messages.addObject().put("role", "system").put("content", system);
        messages.addObject().put("role", "user").put("content", user);

        String resp;
        long t0 = System.currentTimeMillis();
        try {
            resp = client(cfg).post()
                    // 尾斜杠必须剥掉：FastAPI 系代理把 //chat/completions 当另一条路径直接 404
                    .uri(cfg.baseUrl().replaceAll("/+$", "") + "/chat/completions")
                    .header("Authorization", "Bearer " + cfg.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(mapper.writeValueAsString(req))
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            throw new BizException("LLM 请求失败: " + e.getMessage());
        }
        try {
            JsonNode root = mapper.readTree(resp);
            // 每批耗时 + token 用量（total 远大于 prompt+completion 说明思考在烧 token）
            JsonNode usage = root.path("usage");
            long promptTk = usage.path("prompt_tokens").asLong(0);
            long completionTk = usage.path("completion_tokens").asLong(0);
            long totalTk = usage.path("total_tokens").asLong(0);
            long hiddenTk = Math.max(0, totalTk - promptTk - completionTk);
            log.info("LLM 批次: {}ms, 模型 {}, tokens prompt={} completion={} total={}{}",
                    System.currentTimeMillis() - t0, cfg.model(), promptTk, completionTk, totalTk,
                    hiddenTk > 0 ? "（思考≈" + hiddenTk + "）" : "");
            return root.path("choices").path(0).path("message").path("content").asText("");
        } catch (Exception e) {
            throw new BizException("解析 LLM 响应失败: " + e.getMessage());
        }
    }

    /** Anthropic messages 协议（Claude 官方与兼容端点）。system 走顶层字段，鉴权用 x-api-key。 */
    private String chatClaude(String system, String user, LlmConfig cfg) {
        ObjectNode req = mapper.createObjectNode();
        req.put("model", cfg.model());
        req.put("max_tokens", 8192);
        req.put("temperature", 0.3);
        req.put("system", system);
        ArrayNode messages = req.putArray("messages");
        messages.addObject().put("role", "user").put("content", user);

        String resp;
        long t0 = System.currentTimeMillis();
        try {
            resp = client(cfg).post()
                    .uri(cfg.baseUrl().replaceAll("/+$", "") + "/messages")
                    .header("x-api-key", cfg.apiKey())
                    .header("anthropic-version", "2023-06-01")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(mapper.writeValueAsString(req))
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            throw new BizException("Claude 请求失败: " + e.getMessage());
        }
        try {
            JsonNode root = mapper.readTree(resp);
            JsonNode usage = root.path("usage");
            log.info("Claude 批次: {}ms, 模型 {}, tokens in={} out={}",
                    System.currentTimeMillis() - t0, cfg.model(),
                    usage.path("input_tokens").asLong(0), usage.path("output_tokens").asLong(0));
            // content 数组里取第一个 text 块（thinking 模型可能先输出 thinking 块）
            for (JsonNode block : root.path("content")) {
                if ("text".equals(block.path("type").asText())) {
                    return block.path("text").asText("");
                }
            }
            return root.path("content").path(0).path("text").asText("");
        } catch (Exception e) {
            throw new BizException("解析 Claude 响应失败: " + e.getMessage());
        }
    }

    /** 每次调用按配置的超时秒数构建客户端（对象很轻，网络耗时才是大头）。 */
    private RestClient client(LlmConfig cfg) {
        org.springframework.http.client.SimpleClientHttpRequestFactory rf =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(java.time.Duration.ofSeconds(10));
        rf.setReadTimeout(java.time.Duration.ofSeconds(cfg.effectiveTimeoutSec()));
        return RestClient.builder().requestFactory(rf).build();
    }
}
