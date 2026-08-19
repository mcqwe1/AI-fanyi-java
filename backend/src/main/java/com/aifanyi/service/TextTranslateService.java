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
    /** 动态批大小的目标字符量：短行文本≈现状 40 行/批，长块文本 2~3 块/批 */
    private static final int TARGET_BATCH_CHARS = 3000;
    private static final int HISTORY_LIMIT = 50;
    private static final int PREVIEW_CHARS = 50;
    /** 扩展渠道前缀：ext_text / ext_page / ext_image / ext_input，见 {@link TextTranslation#getChannel()} */
    private static final String EXT_CHANNEL_PREFIX = "ext_";

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
        return translate(userId, req, "text");
    }

    /** channel 标记历史来源渠道（text/file/ext_text/ext_page/ext_image），见 {@link TextTranslation#getChannel()}。 */
    public TextTranslateResp translate(Long userId, TextTranslateReq req, String channel) {
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

        // 动态批大小：字幕式短行顶到 40 行/批（与视频链路一致），整段长文压到 1~3 块/批。
        // 必须用 withBatchSize 而不是重新 new：后者会把用户选的协议（claude/deepl/谷歌/微软）
        // 和自定义超时静默改回 openai + 60s，端点当场打不通。
        LlmConfig base = settings.effectiveLlm(userId);
        long avgLen = Math.max(1, sendChars / toSend.size());
        int batch = (int) Math.max(1, Math.min(40, TARGET_BATCH_CHARS / avgLen));
        LlmConfig cfg = base.withBatchSize(batch);
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
        row.setChannel(channel == null ? "text" : channel);
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

    /** 支持的文本附件扩展名。 */
    private static final java.util.Set<String> TEXT_EXTS = java.util.Set.of("txt", "md", "markdown", "srt", "vtt");

    /**
     * 文件翻译：读取上传的文本文件（自动识别 UTF-8/GBK/BOM），翻译后返回结果 +
     * 可下载的同格式译文。srt/vtt 只翻字幕文本行、保留序号与时间轴；txt/md 整篇按行翻。
     */
    public com.aifanyi.controller.dto.TextTranslateDtos.FileTranslateResp translateFile(
            Long userId, byte[] bytes, String originalName, String targetLang, String stylePrompt) {
        String ext = ext(originalName);
        if (!TEXT_EXTS.contains(ext)) {
            throw new BizException("仅支持 txt / md / srt / vtt 文本文件；Word/PPT/PDF 请用「文档翻译」");
        }
        String content = com.aifanyi.media.CharsetSniffer.decode(bytes);
        if (!StringUtils.hasText(content)) {
            throw new BizException("文件内容为空或无法识别编码");
        }

        boolean subtitle = ext.equals("srt") || ext.equals("vtt");
        TextTranslateResp base = subtitle
                ? translate(userId, new TextTranslateReq(extractSubtitleText(content), targetLang, stylePrompt), "file")
                : translate(userId, new TextTranslateReq(content, targetLang, stylePrompt), "file");

        String outFormat = subtitle ? ext : (ext.equals("markdown") ? "md" : ext);
        String outName = baseName(originalName) + "." + targetTag(targetLang) + "." + outFormat;
        return new com.aifanyi.controller.dto.TextTranslateDtos.FileTranslateResp(
                base.id(), base.lines(), base.plainTarget(), base.model(), base.elapsedMs(),
                base.untranslatedLines(), outName, outFormat);
    }

    /** 从 srt/vtt 抽出纯字幕文本（每条一行；丢弃序号、时间轴、空行、WEBVTT 头），供翻译。 */
    private static String extractSubtitleText(String content) {
        String[] lines = content.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (String ln : lines) {
            String t = ln.strip();
            if (t.isEmpty() || t.equalsIgnoreCase("WEBVTT")) {
                continue;
            }
            if (t.matches("\\d+")) {
                continue;                             // 纯序号行
            }
            if (t.contains("-->")) {
                continue;                             // 时间轴行
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(t);
        }
        return sb.toString();
    }

    private static String ext(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    private static String baseName(String name) {
        if (name == null) {
            return "translation";
        }
        String n = name.replaceAll("[\\\\/]", "_");
        int dot = n.lastIndexOf('.');
        return dot <= 0 ? n : n.substring(0, dot);
    }

    /** 目标语言简短标签，用于译文文件名（去掉可能的空白/斜杠）。 */
    private static String targetTag(String targetLang) {
        String t = targetLang == null ? "译文" : targetLang.trim();
        return t.isEmpty() ? "译文" : t.replaceAll("[\\s\\\\/]", "");
    }

    /**
     * 文本翻译历史（当前用户，最新在前；只取轻量列，不拉 MEDIUMTEXT 大字段）。
     * 2026-08 需求改版：扩展渠道（channel 以 ext_ 开头）的记录归「划词翻译」页自己的历史，
     * 不再混进本列表——见 {@link #extHistory(Long)}。
     */
    public List<HistoryItem> history(Long userId) {
        return listHistory(userId, false);
    }

    /** 划词翻译（浏览器扩展）历史：划词 / 整页 / 图片 / 输入框，与文本翻译历史互不相见。 */
    public List<HistoryItem> extHistory(Long userId) {
        return listHistory(userId, true);
    }

    private List<HistoryItem> listHistory(Long userId, boolean ext) {
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TextTranslation> q =
                Wrappers.<TextTranslation>lambdaQuery()
                        .select(TextTranslation::getId, TextTranslation::getChannel, TextTranslation::getTargetLang,
                                TextTranslation::getPreview,
                                TextTranslation::getModel, TextTranslation::getElapsedMs,
                                TextTranslation::getUntranslatedLines, TextTranslation::getCreatedAt)
                        .eq(TextTranslation::getUserId, userId);
        // channel NOT NULL DEFAULT 'text'，前缀匹配即可把扩展渠道和文本渠道分成互补的两半
        if (ext) {
            q.likeRight(TextTranslation::getChannel, EXT_CHANNEL_PREFIX);
        } else {
            q.notLikeRight(TextTranslation::getChannel, EXT_CHANNEL_PREFIX);
        }
        List<TextTranslation> rows = mapper.selectList(q
                .orderByDesc(TextTranslation::getId)
                .last("limit " + HISTORY_LIMIT));
        List<HistoryItem> items = new ArrayList<>(rows.size());
        for (TextTranslation r : rows) {
            items.add(new HistoryItem(r.getId(), r.getChannel(), r.getTargetLang(), r.getPreview(), r.getModel(),
                    r.getElapsedMs() == null ? 0 : r.getElapsedMs(),
                    r.getUntranslatedLines() == null ? 0 : r.getUntranslatedLines(),
                    r.getCreatedAt()));
        }
        return items;
    }

    public HistoryDetail detail(Long userId, Long id) {
        return detail(userId, id, null);
    }

    /** ext=true 只允许读扩展记录、false 只允许读文本记录、null 不限（两边入口各管各的，防止串页操作）。 */
    public HistoryDetail detail(Long userId, Long id, Boolean ext) {
        TextTranslation r = getOwned(userId, id, ext);
        List<Line> lines = readLines(r.getPairsJson());
        return new HistoryDetail(r.getId(), r.getTargetLang(), r.getSourceText(), r.getStylePrompt(),
                lines, r.getPlainTarget(),
                r.getModel(), r.getElapsedMs() == null ? 0 : r.getElapsedMs(),
                r.getUntranslatedLines() == null ? 0 : r.getUntranslatedLines(), r.getCreatedAt());
    }

    public void remove(Long userId, Long id) {
        remove(userId, id, null);
    }

    public void remove(Long userId, Long id, Boolean ext) {
        getOwned(userId, id, ext);
        mapper.deleteById(id);
    }

    /** 清空当前用户的全部划词翻译历史，返回删除条数（逻辑删除）。 */
    public int clearExtHistory(Long userId) {
        return mapper.delete(Wrappers.<TextTranslation>lambdaQuery()
                .eq(TextTranslation::getUserId, userId)
                .likeRight(TextTranslation::getChannel, EXT_CHANNEL_PREFIX));
    }

    /** 归属校验：不存在、不属于当前用户、或渠道对不上入口，一律按不存在处理。 */
    private TextTranslation getOwned(Long userId, Long id, Boolean ext) {
        TextTranslation r = mapper.selectById(id);
        if (r == null || !r.getUserId().equals(userId)) {
            throw new BizException("记录不存在");
        }
        if (ext != null && isExt(r.getChannel()) != ext) {
            throw new BizException("记录不存在");
        }
        return r;
    }

    private static boolean isExt(String channel) {
        return channel != null && channel.startsWith(EXT_CHANNEL_PREFIX);
    }

    /**
     * 超长行按句子边界切块。实现见 {@link com.aifanyi.media.TextSplitter}（与 Agent 文本链共用）。
     */
    static List<String> splitLong(String line) {
        return com.aifanyi.media.TextSplitter.splitLong(line, MAX_SEG_CHARS);
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
