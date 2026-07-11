package com.aifanyi.service;

import com.aifanyi.common.BizException;
import com.aifanyi.controller.dto.TextTranslateDtos.HistoryDetail;
import com.aifanyi.controller.dto.TextTranslateDtos.HistoryItem;
import com.aifanyi.controller.dto.TextTranslateDtos.Line;
import com.aifanyi.controller.dto.TextTranslateDtos.TextTranslateReq;
import com.aifanyi.controller.dto.TextTranslateDtos.TextTranslateResp;
import com.aifanyi.entity.TextTranslation;
import com.aifanyi.llm.LlmConfig;
import com.aifanyi.llm.LlmTranslator;
import com.aifanyi.mapper.TextTranslationMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 文本翻译模式：任意文本 → 拆行/长行切块 → 复用 LlmTranslator 批量翻译 →
 * 还原纯译文 + 逐行对照，并保存历史（与视频翻译任务完全分开）。
 * 同步接口：前端页面内等待（axios 超时 10 分钟，足够 10 万字上限的最坏情形）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TextTranslateService {

    /** 输入全文字符上限（UTF-16 length，与前端 textarea maxlength 一致） */
    private static final int MAX_TEXT_CHARS = 100_000;
    /** 单块字符上限：超长行按句子边界切块，避免单批 prompt/输出 token 溢出 */
    private static final int MAX_SEG_CHARS = 1200;
    /** 切点搜索下限：句边界太靠前就退次级标点/硬切，避免切出碎块 */
    private static final int MIN_CUT = MAX_SEG_CHARS / 4;
    /** 动态批大小的目标字符量：短行文本≈现状 40 行/批，长块文本 2~3 块/批 */
    private static final int TARGET_BATCH_CHARS = 3000;
    private static final int HISTORY_LIMIT = 50;
    private static final int PREVIEW_CHARS = 50;

    private final SettingsService settings;
    private final LlmTranslator translator;
    private final TextTranslationMapper mapper;
    private final ObjectMapper json;

    /** 一块待翻译文本：属于原始第 origLine 行；空白块不送翻（sendIdx=-1）。 */
    private static final class Piece {
        final int origLine;
        final String text;
        int sendIdx = -1;

        Piece(int origLine, String text) {
            this.origLine = origLine;
            this.text = text;
        }
    }

    public TextTranslateResp translate(Long userId, TextTranslateReq req) {
        String targetLang = StringUtils.hasText(req.targetLang()) ? req.targetLang().trim() : "中文";
        String stylePrompt = TaskService.normalizeStylePrompt(req.stylePrompt());
        String text = req.text() == null ? "" : req.text().replace("\r\n", "\n").replace('\r', '\n');
        if (text.strip().isEmpty()) {
            throw new BizException("请输入要翻译的文本");
        }
        if (text.length() > MAX_TEXT_CHARS) {
            throw new BizException("文本过长（" + text.length() + " 字符，上限 " + MAX_TEXT_CHARS + "），请分段翻译");
        }

        // 拆行（保留空行占位）+ 超长行按句子边界切块；不变量：每行至少一块，块序即原文序
        String[] rawLines = text.split("\n", -1);
        List<Piece> pieces = new ArrayList<>();
        for (int i = 0; i < rawLines.length; i++) {
            for (String seg : splitLong(rawLines[i])) {
                pieces.add(new Piece(i, seg));
            }
        }

        // 空白块不送翻，其余按序编号，翻译结果按 sendIdx 回填（translate 契约：等长同序）
        List<String> toSend = new ArrayList<>();
        long sendChars = 0;
        for (Piece p : pieces) {
            if (!p.text.strip().isEmpty()) {
                p.sendIdx = toSend.size();
                toSend.add(p.text);
                sendChars += p.text.length();
            }
        }
        if (toSend.isEmpty()) {
            throw new BizException("未检测到可翻译内容");
        }

        // 动态批大小：字幕式短行顶到 40 行/批（与视频链路一致），整段长文压到 1~3 块/批
        LlmConfig base = settings.effectiveLlm(userId);
        long avgLen = Math.max(1, sendChars / toSend.size());
        int batch = (int) Math.max(1, Math.min(40, TARGET_BATCH_CHARS / avgLen));
        LlmConfig cfg = new LlmConfig(base.baseUrl(), base.apiKey(), base.model(),
                base.disableThinking(), batch, base.concurrency());
        log.info("文本翻译：{} 字符 / {} 块，batchSize={}，模型 {}", text.length(), toSend.size(), batch, cfg.model());

        long t0 = System.currentTimeMillis();
        List<String> out = translator.translate(toSend, targetLang, cfg, java.util.Map.of(), stylePrompt);
        long elapsed = System.currentTimeMillis() - t0;

        // 组装逐行对照 + 统计疑似未翻译（失败批会原文返回；数字/URL 行会误报，故措辞“疑似”）
        List<Line> lines = new ArrayList<>(pieces.size());
        int untranslated = 0;
        for (Piece p : pieces) {
            String target = p.sendIdx < 0 ? "" : out.get(p.sendIdx);
            lines.add(new Line(p.text, target));
            if (p.sendIdx >= 0 && target.strip().equals(p.text.strip())) {
                untranslated++;
            }
        }
        String plainTarget = joinPlainTarget(pieces, out, rawLines.length);

        TextTranslation row = new TextTranslation();
        row.setUserId(userId);
        row.setTargetLang(targetLang);
        row.setStylePrompt(stylePrompt);
        row.setPreview(buildPreview(text));
        row.setSourceText(text);
        row.setPlainTarget(plainTarget);
        row.setPairsJson(writeJson(lines));
        row.setModel(cfg.model());
        row.setElapsedMs(elapsed);
        row.setUntranslatedLines(untranslated);
        mapper.insert(row);

        return new TextTranslateResp(row.getId(), lines, plainTarget, cfg.model(), elapsed, untranslated);
    }

    /** 历史列表（当前用户，最新在前；只取轻量列，不拉 MEDIUMTEXT 大字段）。 */
    public List<HistoryItem> history(Long userId) {
        List<TextTranslation> rows = mapper.selectList(Wrappers.<TextTranslation>lambdaQuery()
                .select(TextTranslation::getId, TextTranslation::getTargetLang, TextTranslation::getPreview,
                        TextTranslation::getModel, TextTranslation::getElapsedMs,
                        TextTranslation::getUntranslatedLines, TextTranslation::getCreatedAt)
                .eq(TextTranslation::getUserId, userId)
                .orderByDesc(TextTranslation::getId)
                .last("limit " + HISTORY_LIMIT));
        List<HistoryItem> items = new ArrayList<>(rows.size());
        for (TextTranslation r : rows) {
            items.add(new HistoryItem(r.getId(), r.getTargetLang(), r.getPreview(), r.getModel(),
                    r.getElapsedMs() == null ? 0 : r.getElapsedMs(),
                    r.getUntranslatedLines() == null ? 0 : r.getUntranslatedLines(),
                    r.getCreatedAt()));
        }
        return items;
    }

    public HistoryDetail detail(Long userId, Long id) {
        TextTranslation r = getOwned(userId, id);
        List<Line> lines = readLines(r.getPairsJson());
        return new HistoryDetail(r.getId(), r.getTargetLang(), r.getSourceText(), r.getStylePrompt(),
                lines, r.getPlainTarget(),
                r.getModel(), r.getElapsedMs() == null ? 0 : r.getElapsedMs(),
                r.getUntranslatedLines() == null ? 0 : r.getUntranslatedLines(), r.getCreatedAt());
    }

    public void remove(Long userId, Long id) {
        getOwned(userId, id);
        mapper.deleteById(id);
    }

    /** 归属校验：不存在或不属于当前用户一律按不存在处理。 */
    private TextTranslation getOwned(Long userId, Long id) {
        TextTranslation r = mapper.selectById(id);
        if (r == null || !r.getUserId().equals(userId)) {
            throw new BizException("记录不存在");
        }
        return r;
    }

    /**
     * 超长行按句子边界切块。优先句末标点（'.' 仅当后跟空白/行尾，避开 3.14、U.S.），
     * 退而求次级停顿标点/空格，再退硬切；切点落在代理对高位时回退一位，不拆字符。
     */
    static List<String> splitLong(String line) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (line.length() - i > MAX_SEG_CHARS) {
            int end = i + MAX_SEG_CHARS;
            int cut = findCut(line, i, end, TextTranslateService::isSentenceEnd);
            if (cut < 0) {
                cut = findCut(line, i, end, TextTranslateService::isSoftBreak);
            }
            if (cut < 0) {
                cut = end - 1;
            }
            if (Character.isHighSurrogate(line.charAt(cut))) {
                cut--;
            }
            out.add(line.substring(i, cut + 1));
            i = cut + 1;
        }
        out.add(line.substring(i));
        return out;
    }

    /** 在 [from+MIN_CUT, end) 内从后往前找第一个满足条件的切点，找不到返回 -1。 */
    private static int findCut(String line, int from, int end, CutPredicate pred) {
        for (int j = end - 1; j >= from + MIN_CUT; j--) {
            if (pred.test(line, j)) {
                return j;
            }
        }
        return -1;
    }

    private interface CutPredicate {
        boolean test(String line, int idx);
    }

    private static boolean isSentenceEnd(String line, int idx) {
        char c = line.charAt(idx);
        if ("。！？!?…；;".indexOf(c) >= 0) {
            return true;
        }
        // '.' 仅当后一字符是空白或行尾才算句末（避开小数点、缩写）
        return c == '.' && (idx + 1 >= line.length() || Character.isWhitespace(line.charAt(idx + 1)));
    }

    private static boolean isSoftBreak(String line, int idx) {
        char c = line.charAt(idx);
        return "，,、）)】》”\" ".indexOf(c) >= 0 || Character.isWhitespace(c);
    }

    /**
     * 按原始行结构还原纯译文：同一行切出的多块拼回一行（前块末尾与后块开头都是
     * ASCII 字母/数字时补一个空格，中文等无缝），行间以 \n 还原（空行保留段落感）。
     */
    private static String joinPlainTarget(List<Piece> pieces, List<String> out, int totalLines) {
        StringBuilder sb = new StringBuilder();
        int curLine = 0;
        boolean lineHasContent = false;
        for (Piece p : pieces) {
            while (curLine < p.origLine) {
                sb.append('\n');
                curLine++;
                lineHasContent = false;
            }
            String t = p.sendIdx < 0 ? "" : out.get(p.sendIdx);
            if (t.isEmpty()) {
                continue;
            }
            if (lineHasContent && needSpace(sb.charAt(sb.length() - 1), t.charAt(0))) {
                sb.append(' ');
            }
            sb.append(t);
            lineHasContent = true;
        }
        while (curLine < totalLines - 1) {
            sb.append('\n');
            curLine++;
        }
        return sb.toString();
    }

    private static boolean needSpace(char prev, char next) {
        return isAsciiWord(prev) && isAsciiWord(next);
    }

    private static boolean isAsciiWord(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    private static String buildPreview(String text) {
        String flat = text.strip().replaceAll("\\s+", " ");
        return flat.length() <= PREVIEW_CHARS ? flat : flat.substring(0, PREVIEW_CHARS) + "…";
    }

    private String writeJson(List<Line> lines) {
        try {
            return json.writeValueAsString(lines);
        } catch (Exception e) {
            throw new BizException("序列化对照数据失败: " + e.getMessage());
        }
    }

    private List<Line> readLines(String pairsJson) {
        if (!StringUtils.hasText(pairsJson)) {
            return List.of();
        }
        try {
            return json.readValue(pairsJson, new TypeReference<List<Line>>() {
            });
        } catch (Exception e) {
            log.warn("对照数据反序列化失败，仅返回纯译文: {}", e.getMessage());
            return List.of();
        }
    }
}
