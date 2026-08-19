package com.aifanyi.doc;

import org.apache.fontbox.ttf.TrueTypeCollection;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PDF：pdfbox 抽取文本行（带页码与坐标）→ 按版面聚成视觉段 → 翻译 →
 * <b>原 PDF 原位回填</b>：段内各行画底色矩形盖住原文，再在段的包围盒内排版译文
 * （嵌入系统中文字体、自动换行、放不下逐级缩小字号）。
 *
 * <p>2026-08 需求改版：输入 PDF 输出 PDF（此前输出 DOCX）。已知边界：
 * 非纯色底的区域盖白会露出色块；旋转页不回填（保留原文）；
 * 系统无 CJK 字体或回填异常时整体降级回 DOCX，保证任务不挂。
 * 扫描版（纯图片）PDF 抽不出文本，仍明确报错。
 */
final class PdfDoc extends ParsedDoc {

    /** 一行文本 + 版面信息。page 从 1 起；top 为自页面顶部向下的 y（cropBox 空间）。 */
    private record Line(String text, int page, float x, float top, float width, float height) {
    }

    /** 一个视觉段：同页、同栏、行距连续的若干行，是翻译与回填的基本单元。 */
    private static final class Para {
        final List<Line> lines = new ArrayList<>();
        String text;
    }

    /** 换栏/缩进跳变判定：相邻行 x 起点差超过该值（pt）即分段。 */
    private static final float COLUMN_JUMP_PT = 50f;
    /** 段间距判定：行间空隙超过行高的该倍数即分段（正常行距的空隙约 0.2~0.5 倍行高）。 */
    private static final float PARA_GAP_RATIO = 0.9f;
    /** 回填字号下限：再小就不可读了，允许以此字号溢出段框底部。 */
    private static final float MIN_FONT_PT = 5f;

    private final byte[] original;
    private final List<Para> paras = new ArrayList<>();
    private final List<String> segments = new ArrayList<>();
    /** rebuild 的实际产物格式：正常 pdf；字体缺失/回填失败降级 docx。 */
    private String outExt = "pdf";

    PdfDoc(byte[] bytes) throws Exception {
        this.original = bytes;
        List<Line> lines;
        try (PDDocument pd = Loader.loadPDF(bytes)) {
            if (pd.isEncrypted()) {
                throw new IllegalArgumentException("PDF 已加密，请先解除密码保护");
            }
            lines = extractLines(pd);
        }
        groupParas(lines);
        for (Para p : paras) {
            segments.add(p.text);
        }
        if (segments.isEmpty()) {
            throw new IllegalArgumentException(
                    "该 PDF 抽取不到文本（可能是扫描版/纯图片 PDF），暂不支持 OCR 翻译");
        }
    }

    // ---------------- 解析：行 + 坐标 ----------------

    /** 自定义 Stripper：按 stripper 的行边界收集文本与包围盒，文本本身丢弃。 */
    private static List<Line> extractLines(PDDocument pd) throws IOException {
        List<Line> lines = new ArrayList<>();
        PDFTextStripper stripper = new PDFTextStripper() {
            final StringBuilder lineText = new StringBuilder();
            final List<TextPosition> buf = new ArrayList<>();

            @Override
            protected void writeString(String text, List<TextPosition> tps) {
                lineText.append(text);
                buf.addAll(tps);
            }

            @Override
            protected void writeLineSeparator() {
                flushLine();
            }

            @Override
            protected void endPage(PDPage page) throws IOException {
                flushLine();
                super.endPage(page);
            }

            private void flushLine() {
                if (buf.isEmpty()) {
                    lineText.setLength(0);
                    return;
                }
                float x0 = Float.MAX_VALUE;
                float x1 = 0;
                float top = Float.MAX_VALUE;
                float bottom = 0;
                for (TextPosition tp : buf) {
                    x0 = Math.min(x0, tp.getXDirAdj());
                    x1 = Math.max(x1, tp.getXDirAdj() + tp.getWidthDirAdj());
                    // getYDirAdj 是基线（自顶向下）；上沿减高度，下沿加 1/4 高度近似 descender
                    top = Math.min(top, tp.getYDirAdj() - tp.getHeightDir());
                    bottom = Math.max(bottom, tp.getYDirAdj() + tp.getHeightDir() * 0.25f);
                }
                String t = lineText.toString().strip();
                if (!t.isEmpty() && x1 > x0 && bottom > top) {
                    lines.add(new Line(t, getCurrentPageNo(), x0, top, x1 - x0, bottom - top));
                }
                lineText.setLength(0);
                buf.clear();
            }
        };
        stripper.setSortByPosition(true);
        stripper.getText(pd);                                   // 只为触发遍历，返回文本不用
        return lines;
    }

    /**
     * 行 → 视觉段：跨页、x 起点跳变（换栏/表格列）、行间空隙突增（段间距）任一出现即分段。
     * 有真实坐标后不再需要旧版“句末标点断段”的猜测。
     */
    private void groupParas(List<Line> lines) {
        Para cur = null;
        Line prev = null;
        for (Line ln : lines) {
            boolean newPara = cur == null || prev == null
                    || ln.page() != prev.page()
                    || Math.abs(ln.x() - prev.x()) > COLUMN_JUMP_PT
                    || ln.top() - (prev.top() + prev.height()) > prev.height() * PARA_GAP_RATIO;
            if (newPara) {
                cur = new Para();
                paras.add(cur);
            }
            cur.lines.add(ln);
            prev = ln;
        }
        for (Para p : paras) {
            p.text = joinLines(p.lines);
        }
        paras.removeIf(p -> p.text.isBlank());
    }

    /** 段内行拼接：连字符断词去 '-' 无缝拼，ASCII 词间补空格，CJK 无缝。 */
    private static String joinLines(List<Line> lines) {
        StringBuilder sb = new StringBuilder();
        for (Line ln : lines) {
            String t = ln.text();
            if (sb.length() > 0) {
                char prevCh = sb.charAt(sb.length() - 1);
                if (prevCh == '-' && Character.isLowerCase(t.charAt(0))) {
                    sb.setLength(sb.length() - 1);
                } else if (prevCh < 0x2E80 && t.charAt(0) < 0x2E80) {
                    sb.append(' ');
                }
            }
            sb.append(t);
        }
        return sb.toString().strip();
    }

    // ---------------- 回填 ----------------

    @Override
    public List<String> segments() {
        return segments;
    }

    @Override
    public byte[] rebuild(List<String> translated) throws Exception {
        try {
            byte[] out = rebuildPdf(translated);
            outExt = "pdf";
            return out;
        } catch (Exception e) {
            // 原位回填失败（无中文字体/损坏页等）不挂任务：退回旧行为输出 DOCX
            outExt = "docx";
            return MiniDocxWriter.write(translated);
        }
    }

    private byte[] rebuildPdf(List<String> translated) throws Exception {
        try (PDDocument pd = Loader.loadPDF(original)) {
            PDType0Font font = loadCjkFont(pd);
            // 段按页分桶：每页只开一个内容流
            Map<Integer, List<int[]>> byPage = new LinkedHashMap<>();   // pageIdx -> [paraIdx]
            for (int i = 0; i < paras.size(); i++) {
                int pageIdx = paras.get(i).lines.get(0).page() - 1;
                byPage.computeIfAbsent(pageIdx, k -> new ArrayList<>()).add(new int[]{i});
            }
            for (Map.Entry<Integer, List<int[]>> e : byPage.entrySet()) {
                int pageIdx = e.getKey();
                if (pageIdx < 0 || pageIdx >= pd.getNumberOfPages()) {
                    continue;
                }
                PDPage page = pd.getPage(pageIdx);
                if (page.getRotation() != 0) {
                    continue;                                   // 旋转页坐标系不同，保留原文
                }
                PDRectangle crop = page.getCropBox();
                try (PDPageContentStream cs = new PDPageContentStream(
                        pd, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                    for (int[] idx : e.getValue()) {
                        Para p = paras.get(idx[0]);
                        String t = idx[0] < translated.size() ? translated.get(idx[0]) : p.text;
                        coverLines(cs, p, crop);
                        drawPara(cs, font, t, p, crop);
                    }
                }
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            pd.save(bos);
            return bos.toByteArray();
        }
    }

    /** 段内每一行画白色矩形盖住原文（各边外扩 1pt，防露边）。 */
    private static void coverLines(PDPageContentStream cs, Para p, PDRectangle crop) throws IOException {
        cs.setNonStrokingColor(Color.WHITE);
        for (Line ln : p.lines) {
            float x = crop.getLowerLeftX() + ln.x() - 1;
            float y = crop.getUpperRightY() - (ln.top() + ln.height()) - 1;
            cs.addRect(x, y, ln.width() + 2, ln.height() + 2);
        }
        cs.fill();
    }

    /** 在段包围盒内排版译文：初始字号≈原行高，放不下按 0.9 逐级缩，最小 5pt（允许底部微溢）。 */
    private static void drawPara(PDPageContentStream cs, PDType0Font font, String text,
                                 Para p, PDRectangle crop) throws IOException {
        float x0 = Float.MAX_VALUE;
        float x1 = 0;
        float top = Float.MAX_VALUE;
        float bottom = 0;
        float hSum = 0;
        for (Line ln : p.lines) {
            x0 = Math.min(x0, ln.x());
            x1 = Math.max(x1, ln.x() + ln.width());
            top = Math.min(top, ln.top());
            bottom = Math.max(bottom, ln.top() + ln.height());
            hSum += ln.height();
        }
        float boxW = Math.max(10, x1 - x0);
        float boxH = Math.max(4, bottom - top);
        float avgLineH = hSum / p.lines.size();

        String clean = sanitize(font, text);
        if (clean.isBlank()) {
            return;
        }
        float size = Math.min(Math.max(MIN_FONT_PT, avgLineH * 0.82f), 24f);
        List<String> wrapped = List.of();
        for (; size >= MIN_FONT_PT; size *= 0.9f) {
            wrapped = wrap(font, clean, size, boxW);
            float need = wrapped.size() * size * 1.18f;
            if (need <= boxH + size * 0.4f) {                   // 容许约 1/3 行的微溢
                break;
            }
        }
        size = Math.max(size, MIN_FONT_PT);
        if (wrapped.isEmpty()) {
            wrapped = wrap(font, clean, size, boxW);
        }
        float lineH = size * 1.18f;
        float baseX = crop.getLowerLeftX() + x0;
        // 首行基线：段顶向下一个字号
        float baseY = crop.getUpperRightY() - top - size;
        cs.setNonStrokingColor(Color.BLACK);
        for (String lnText : wrapped) {
            if (lnText.isBlank()) {
                baseY -= lineH;
                continue;
            }
            cs.beginText();
            cs.setFont(font, size);
            cs.newLineAtOffset(baseX, baseY);
            cs.showText(lnText);
            cs.endText();
            baseY -= lineH;
        }
    }

    /** 按框宽换行：token = 连续 ASCII 词 / 单个其他字符；词放不下整体换行，超宽词硬切。 */
    private static List<String> wrap(PDType0Font font, String text, float size, float boxW) throws IOException {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        float lineW = 0;
        int i = 0;
        while (i < text.length()) {
            int j = i;
            char c = text.charAt(i);
            if (isAsciiWord(c)) {
                while (j < text.length() && isAsciiWord(text.charAt(j))) {
                    j++;
                }
            } else {
                j = i + 1;
            }
            String token = text.substring(i, j);
            float w = width(font, token, size);
            if (lineW + w > boxW && line.length() > 0) {
                out.add(line.toString());
                line.setLength(0);
                lineW = 0;
                if (" ".equals(token)) {                        // 行首不留空格
                    i = j;
                    continue;
                }
            }
            if (w > boxW) {                                     // 单 token 超框宽：逐字符硬切
                for (int k = 0; k < token.length(); k++) {
                    String ch = String.valueOf(token.charAt(k));
                    float cw = width(font, ch, size);
                    if (lineW + cw > boxW && line.length() > 0) {
                        out.add(line.toString());
                        line.setLength(0);
                        lineW = 0;
                    }
                    line.append(ch);
                    lineW += cw;
                }
            } else {
                line.append(token);
                lineW += w;
            }
            i = j;
        }
        if (line.length() > 0) {
            out.add(line.toString());
        }
        return out;
    }

    private static float width(PDType0Font font, String s, float size) throws IOException {
        return font.getStringWidth(s) / 1000f * size;
    }

    private static boolean isAsciiWord(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '\'' || c == '-';
    }

    /** 去掉字体没有字形的字符（替换为空格），换行归一为空格——排版由 wrap 决定。 */
    private static String sanitize(PDType0Font font, String text) {
        StringBuilder sb = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '\n' || cp == '\r' || cp == '\t') {
                sb.append(' ');
                continue;
            }
            String ch = new String(Character.toChars(cp));
            try {
                font.encode(ch);
                sb.append(ch);
            } catch (Exception e) {
                sb.append(' ');
            }
        }
        return sb.toString().replaceAll(" {3,}", "  ").strip();
    }

    /**
     * 加载系统中文字体（子集嵌入）：黑体 simhei.ttf（单文件最稳）→ 雅黑/宋体 TTC。
     * TTC 从内存流加载，不占文件句柄；全部缺失抛异常 → rebuild 降级 DOCX。
     */
    private static PDType0Font loadCjkFont(PDDocument pd) throws IOException {
        String winDir = System.getenv("WINDIR");
        Path fonts = Path.of(winDir == null ? "C:\\Windows" : winDir, "Fonts");
        Path simhei = fonts.resolve("simhei.ttf");
        if (Files.exists(simhei)) {
            return PDType0Font.load(pd, simhei.toFile());
        }
        for (String name : new String[]{"msyh.ttc", "simsun.ttc", "msjh.ttc", "meiryo.ttc"}) {
            Path p = fonts.resolve(name);
            if (!Files.exists(p)) {
                continue;
            }
            try {
                TrueTypeCollection ttc = new TrueTypeCollection(
                        new ByteArrayInputStream(Files.readAllBytes(p)));
                TrueTypeFont[] first = new TrueTypeFont[1];
                ttc.processAllFonts(f -> {
                    if (first[0] == null) {
                        first[0] = f;
                    }
                });
                if (first[0] != null) {
                    return PDType0Font.load(pd, first[0], true);
                }
            } catch (IOException e) {
                // 该字体文件异常，试下一个候选
            }
        }
        throw new IOException("系统未找到可用的中文字体（simhei/msyh/simsun）");
    }

    @Override
    public String outputExt() {
        return outExt;
    }
}
