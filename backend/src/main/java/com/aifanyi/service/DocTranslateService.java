package com.aifanyi.service;

import com.aifanyi.common.BizException;
import com.aifanyi.config.AifanyiProperties;
import com.aifanyi.controller.dto.DocDtos.DocCompare;
import com.aifanyi.controller.dto.DocDtos.DocItem;
import com.aifanyi.controller.dto.DocDtos.SegmentPair;
import com.aifanyi.doc.DocParsers;
import com.aifanyi.doc.ParsedDoc;
import com.aifanyi.entity.DocTranslation;
import com.aifanyi.llm.LlmConfig;
import com.aifanyi.llm.LlmTranslator;
import com.aifanyi.mapper.DocTranslationMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;

/**
 * 文档翻译模式：上传文档 → 异步解析/翻译/重建 → 下载同格式译文。
 * 支持 pdf / docx / xlsx / pptx / epub / html / txt / json / md / srt / vtt / ass。
 * 文档可能是整本书（ePub 几十万字），必须异步 + 进度轮询，不能学文本模式同步等。
 */
@Slf4j
@Service
public class DocTranslateService {

    /** 上传大小上限：解析在内存进行（ZIP/DOM 全量展开），放开会 OOM。 */
    private static final long MAX_FILE_BYTES = 100L * 1024 * 1024;
    /** 单段送翻上限（超长段按句子边界切块后送翻，译文再拼回一段）。 */
    private static final int MAX_SEG_CHARS = com.aifanyi.media.TextSplitter.MAX_SEG_CHARS;
    /** 动态批大小的目标字符量（与文本翻译模式同一把尺子）。 */
    private static final int TARGET_BATCH_CHARS = 3000;
    private static final int LIST_LIMIT = 100;
    /** 原文-译文分段对照数据文件名（任务目录下）。 */
    private static final String SEGMENTS_FILE = "segments.json";
    /** PDF 档位：快速（内置 pdfbox 引擎）。其余值/留空都走保版式 BabelDOC。 */
    static final String PDF_MODE_FAST = "fast";
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final SettingsService settings;
    private final LlmTranslator translator;
    private final DocTranslationMapper mapper;
    private final Executor executor;
    private final Path docRoot;
    private final com.aifanyi.doc.BabelDocClient babel;
    private final com.aifanyi.asr.AiServiceLauncher aiLauncher;

    public DocTranslateService(SettingsService settings, LlmTranslator translator,
                               DocTranslationMapper mapper,
                               @Qualifier("taskExecutor") Executor executor,
                               AifanyiProperties props,
                               com.aifanyi.doc.BabelDocClient babel,
                               com.aifanyi.asr.AiServiceLauncher aiLauncher) {
        this.settings = settings;
        this.translator = translator;
        this.mapper = mapper;
        this.executor = executor;
        this.babel = babel;
        this.aiLauncher = aiLauncher;
        String root = StringUtils.hasText(props.getStorage().getRoot())
                ? props.getStorage().getRoot()
                : System.getProperty("java.io.tmpdir") + "/aifanyi-data";
        this.docRoot = Paths.get(root).toAbsolutePath().normalize().resolve("docs");
    }

    // ---------------- 上传/启动 ----------------

    public DocItem upload(Long userId, MultipartFile file, String targetLang, String stylePrompt,
                          String pdfMode) {
        String original = file.getOriginalFilename() == null ? "document"
                : Paths.get(file.getOriginalFilename().replace('\\', '/')).getFileName().toString();
        String ext = ext(original);
        if (!DocParsers.supported(ext)) {
            throw new BizException("不支持的文档格式 ." + ext + "，支持："
                    + "pdf / docx / xlsx / pptx / epub / html / txt / json / md / srt / vtt / ass");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new BizException("文件过大（" + file.getSize() / 1024 / 1024 + "MB，上限 100MB）");
        }

        DocTranslation row = new DocTranslation();
        row.setUserId(userId);
        row.setFilename(original);
        row.setFormat(ext);
        row.setTargetLang(StringUtils.hasText(targetLang) ? targetLang.trim() : "中文");
        row.setStylePrompt(TaskService.normalizeStylePrompt(stylePrompt));
        // 只有 PDF 有档位之分；其余格式记 null，免得历史列表里出现无意义的标记
        row.setPdfMode("pdf".equals(ext) && PDF_MODE_FAST.equalsIgnoreCase(pdfMode)
                ? PDF_MODE_FAST : null);
        row.setStatus("PENDING");
        row.setProgress(0);
        row.setTotalSegments(0);
        row.setDoneSegments(0);
        mapper.insert(row);

        try {
            Path dir = dir(row.getId());
            Files.createDirectories(dir);
            Path src = dir.resolve("source." + ext);
            file.transferTo(src);
            row.setSourcePath(src.toString());
            mapper.updateById(row);
        } catch (IOException e) {
            row.setStatus("FAILED");
            row.setErrorMsg("保存上传文件失败: " + e.getMessage());
            mapper.updateById(row);
            throw new BizException("保存上传文件失败: " + e.getMessage());
        }

        Long id = row.getId();
        executor.execute(() -> run(id));
        return toItem(row);
    }

    // ---------------- 异步执行 ----------------

    /** 后台执行一个文档翻译任务。任何异常都落到 FAILED + errorMsg，绝不静默。 */
    void run(Long id) {
        DocTranslation row = mapper.selectById(id);
        if (row == null) {
            return;                                         // 已被删除
        }
        long t0 = System.currentTimeMillis();
        try {
            row.setStatus("RUNNING");
            mapper.updateById(row);

            byte[] bytes = Files.readAllBytes(Path.of(row.getSourcePath()));
            ParsedDoc doc = DocParsers.parse(row.getFormat(), bytes);
            List<String> segments = doc.segments();
            if (segments.isEmpty()) {
                throw new BizException("未从文档中解析出可翻译文本");
            }

            LlmConfig base = settings.effectiveLlm(row.getUserId());

            // PDF + OpenAI 兼容协议：默认走 BabelDOC 版式保持翻译（版面分析模型 + 原位重排版，
            // 效果远好于内置的“盖白重画”）。失败回退内置 pdfbox 引擎，翻译不因此中断。
            // claude / 传统机翻协议直接走内置引擎——babeldoc 内部用 openai SDK 直连。
            // 用户选了「快速」档（pdfMode=fast）也直接走内置：实测同一份 72 段 PDF
            // 内置 13.4s、BabelDOC 93.3s，慢 7 倍，「只想快点看懂」是合法诉求。
            boolean wantLayout = !PDF_MODE_FAST.equalsIgnoreCase(row.getPdfMode());
            if ("pdf".equals(row.getFormat()) && wantLayout
                    && LlmConfig.PROTO_OPENAI.equals(base.protocol())) {
                try {
                    if (runBabelDoc(id, row, base, segments, t0)) {
                        return;
                    }
                } catch (Exception e) {
                    log.warn("文档翻译 #{} BabelDOC 失败，回退内置 PDF 引擎: {}", id, e.toString());
                    // 进度已经被 BabelDOC 推到中途，重来一遍前清零，免得进度条往回跳
                    mapper.update(null, Wrappers.<DocTranslation>lambdaUpdate()
                            .eq(DocTranslation::getId, id)
                            .set(DocTranslation::getProgress, 0)
                            .set(DocTranslation::getDoneSegments, 0));
                }
            }

            // 超长段切块：piece 记住所属段号，译完按段拼回
            List<String> pieces = new ArrayList<>();
            List<Integer> pieceSeg = new ArrayList<>();
            for (int s = 0; s < segments.size(); s++) {
                for (String part : com.aifanyi.media.TextSplitter.splitLong(segments.get(s), MAX_SEG_CHARS)) {
                    pieces.add(part);
                    pieceSeg.add(s);
                }
            }
            mapper.update(null, Wrappers.<DocTranslation>lambdaUpdate()
                    .eq(DocTranslation::getId, id)
                    .set(DocTranslation::getTotalSegments, pieces.size()));

            // 动态批大小（同文本模式）。withBatchSize 而非重新 new：后者会把用户选的
            // claude / 机翻协议和自定义超时静默改回 openai + 60s（见 LlmConfig 注释）。
            long chars = pieces.stream().mapToLong(String::length).sum();
            long avgLen = Math.max(1, chars / pieces.size());
            int batch = (int) Math.max(1, Math.min(40, TARGET_BATCH_CHARS / avgLen));
            LlmConfig cfg = base.withBatchSize(batch);
            log.info("文档翻译 #{}：{} ({}) {} 段 / {} 块 / {} 字符，batch={} 并发={} 模型 {}",
                    id, row.getFilename(), row.getFormat(), segments.size(), pieces.size(),
                    chars, batch, cfg.concurrency(), cfg.model());

            // 一次性提交全部批次，靠回调刷进度。
            // 旧写法按「波」（batch × concurrency）切开、每波 join 一次，于是每波都要等最慢的那批——
            // 实测同一份 1778 行 txt：分波 43.9s（6 波里有 13.0s 和 10.1s 两个拖后腿的），
            // 一次性全提交 27.7s，白省 36%。顺带进度条也从「每波跳一大格」变成平滑推进。
            // keepGoing 把原先「波间检查任务是否被删」换成了逐批检查，中止反而更及时。
            final int total = pieces.size();
            List<String> out = translator.translate(pieces, row.getTargetLang(), cfg,
                    java.util.Map.of(), row.getStylePrompt(),
                    com.aifanyi.llm.TranslateHooks.of(
                            done -> mapper.update(null, Wrappers.<DocTranslation>lambdaUpdate()
                                    .eq(DocTranslation::getId, id)
                                    .set(DocTranslation::getDoneSegments, done)
                                    .set(DocTranslation::getProgress, (int) (done * 99L / total))),
                            () -> mapper.selectById(id) != null));

            // 按段拼回译文块，并统计疑似未翻译段
            List<String> translated = joinBySegment(segments.size(), out, pieceSeg);
            int untranslated = 0;
            for (int s = 0; s < segments.size(); s++) {
                if (translated.get(s).strip().equals(segments.get(s).strip())) {
                    untranslated++;
                }
            }

            byte[] result = doc.rebuild(translated);
            String outExt = doc.outputExt();
            Path outFile = dir(id).resolve("output." + outExt);
            Files.write(outFile, result);
            writeSegments(id, segments, translated);

            String outName = baseName(row.getFilename()) + "." + targetTag(row.getTargetLang())
                    + "." + outExt;
            DocTranslation cur = mapper.selectById(id);
            if (cur == null) {
                return;                                     // 翻译期间被删除
            }
            mapper.update(null, Wrappers.<DocTranslation>lambdaUpdate()
                    .eq(DocTranslation::getId, id)
                    .set(DocTranslation::getStatus, "SUCCESS")
                    .set(DocTranslation::getProgress, 100)
                    .set(DocTranslation::getOutputFormat, outExt)
                    .set(DocTranslation::getOutputPath, outFile.toString())
                    .set(DocTranslation::getOutputName, outName)
                    .set(DocTranslation::getPreview, buildPreview(segments))
                    .set(DocTranslation::getModel, cfg.model())
                    .set(DocTranslation::getElapsedMs, System.currentTimeMillis() - t0)
                    .set(DocTranslation::getUntranslatedSegments, untranslated));
            log.info("文档翻译 #{} 完成：{} 段，{} 疑似未翻译，耗时 {}s",
                    id, segments.size(), untranslated, (System.currentTimeMillis() - t0) / 1000);
        } catch (Exception e) {
            log.error("文档翻译 #{} 失败", id, e);
            mapper.update(null, Wrappers.<DocTranslation>lambdaUpdate()
                    .eq(DocTranslation::getId, id)
                    .set(DocTranslation::getStatus, "FAILED")
                    .set(DocTranslation::getElapsedMs, System.currentTimeMillis() - t0)
                    .set(DocTranslation::getErrorMsg, readable(e)));
        }
    }

    // ---------------- BabelDOC 版式保持翻译 ----------------

    /** 轮询间隔与硬上限：大文档合法地慢（几百页 + QPS 限速），上限只防 ai-service 假死。 */
    private static final long BABEL_POLL_MS = 2_000;
    private static final long BABEL_MAX_MS = 4L * 60 * 60 * 1000;

    /**
     * BabelDOC 路径：提交给 ai-service 子进程翻译，轮询进度写回 DB。
     *
     * @return true=已处理完（成功或任务被删除），调用方直接返回；
     *         失败一律抛异常，由调用方回退内置引擎重翻。
     */
    private boolean runBabelDoc(Long id, DocTranslation row, LlmConfig cfg,
                                List<String> segments, long t0) throws Exception {
        if (!aiLauncher.ensureRunning(false)) {
            throw new BizException("本地 ai-service 不可用");
        }
        Path outDir = dir(id).resolve("babeldoc");
        Files.createDirectories(outDir);
        String langIn = guessLangIn(segments);
        String langOut = babelLang(row.getTargetLang());
        String jobId = babel.start(Path.of(row.getSourcePath()), outDir, cfg, langIn, langOut);
        log.info("文档翻译 #{}：BabelDOC 任务 {} 已提交（{} → {}，模型 {}）",
                id, jobId, langIn, langOut, cfg.model());

        // 进度条语义沿用「段」：BabelDOC 整体进度百分比映射到段数
        mapper.update(null, Wrappers.<DocTranslation>lambdaUpdate()
                .eq(DocTranslation::getId, id)
                .set(DocTranslation::getTotalSegments, segments.size()));

        com.aifanyi.doc.BabelDocClient.JobStatus st;
        int lastPct = -1;
        long deadline = System.currentTimeMillis() + BABEL_MAX_MS;
        while (true) {
            Thread.sleep(BABEL_POLL_MS);
            if (mapper.selectById(id) == null) {
                log.info("文档翻译 #{} 已被删除，取消 BabelDOC 任务", id);
                babel.cancel(jobId);
                return true;
            }
            st = babel.poll(jobId);
            if (!st.running()) {
                break;
            }
            if (System.currentTimeMillis() > deadline) {
                babel.cancel(jobId);
                throw new BizException("BabelDOC 超时（4 小时）");
            }
            int pct = (int) Math.min(99, st.progress());
            if (pct != lastPct) {
                lastPct = pct;
                mapper.update(null, Wrappers.<DocTranslation>lambdaUpdate()
                        .eq(DocTranslation::getId, id)
                        .set(DocTranslation::getProgress, pct)
                        .set(DocTranslation::getDoneSegments, pct * segments.size() / 100));
            }
        }
        if (!st.success() || !StringUtils.hasText(st.monoPath())) {
            throw new BizException(st.error() == null ? "BabelDOC 未返回产物" : st.error());
        }
        Path mono = Path.of(st.monoPath());
        if (!Files.exists(mono)) {
            throw new BizException("BabelDOC 产物文件不存在: " + mono);
        }
        Path outFile = dir(id).resolve("output.pdf");
        Files.copy(mono, outFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        // BabelDOC 自己完成翻译，没有段级对照数据——不写 segments.json，
        // 对照页只提供版式对照（compare() 对缺失文件返回空列表）。
        String outName = baseName(row.getFilename()) + "." + targetTag(row.getTargetLang()) + ".pdf";
        if (mapper.selectById(id) == null) {
            return true;
        }
        mapper.update(null, Wrappers.<DocTranslation>lambdaUpdate()
                .eq(DocTranslation::getId, id)
                .set(DocTranslation::getStatus, "SUCCESS")
                .set(DocTranslation::getProgress, 100)
                .set(DocTranslation::getDoneSegments, segments.size())
                .set(DocTranslation::getOutputFormat, "pdf")
                .set(DocTranslation::getOutputPath, outFile.toString())
                .set(DocTranslation::getOutputName, outName)
                .set(DocTranslation::getPreview, buildPreview(segments))
                .set(DocTranslation::getModel, cfg.model())
                .set(DocTranslation::getElapsedMs, System.currentTimeMillis() - t0)
                .set(DocTranslation::getUntranslatedSegments, 0));
        log.info("文档翻译 #{} BabelDOC 完成，耗时 {}s", id, (System.currentTimeMillis() - t0) / 1000);
        return true;
    }

    /** 源语言粗判：抽样前 2000 字符，CJK 占比过三成按中文，否则按英文（只影响 babeldoc 断词）。 */
    static String guessLangIn(List<String> segments) {
        int total = 0;
        int cjk = 0;
        for (String s : segments) {
            for (int i = 0; i < s.length() && total < 2000; i++) {
                char c = s.charAt(i);
                if (Character.isWhitespace(c)) {
                    continue;
                }
                total++;
                if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                    cjk++;
                }
            }
            if (total >= 2000) {
                break;
            }
        }
        return total > 0 && cjk * 10 >= total * 3 ? "zh" : "en";
    }

    /** 目标语言自由文本 → babeldoc 语言代码；不认识的原样传（只进提示词，模型看得懂）。 */
    static String babelLang(String targetLang) {
        String t = targetLang == null ? "" : targetLang.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) {
            return "zh";
        }
        if (t.contains("繁") || t.contains("traditional")) {
            return "zh-TW";
        }
        if (t.contains("中") || t.contains("chinese") || t.equals("zh") || t.contains("简")) {
            return "zh";
        }
        if (t.contains("英") || t.contains("english") || t.equals("en")) {
            return "en";
        }
        if (t.contains("日") || t.contains("japanese") || t.equals("ja")) {
            return "ja";
        }
        if (t.contains("韩") || t.contains("korean") || t.equals("ko")) {
            return "ko";
        }
        if (t.contains("法") || t.contains("french") || t.equals("fr")) {
            return "fr";
        }
        if (t.contains("德") || t.contains("german") || t.equals("de")) {
            return "de";
        }
        if (t.contains("西班牙") || t.contains("spanish") || t.equals("es")) {
            return "es";
        }
        if (t.contains("俄") || t.contains("russian") || t.equals("ru")) {
            return "ru";
        }
        if (t.contains("葡") || t.contains("portuguese") || t.equals("pt")) {
            return "pt";
        }
        return targetLang.trim();
    }

    /** 同段多块译文拼接：前块尾与后块头都是 ASCII 字母/数字时补空格，CJK 无缝。 */    static List<String> joinBySegment(int segCount, List<String> pieceOut, List<Integer> pieceSeg) {
        StringBuilder[] acc = new StringBuilder[segCount];
        for (int i = 0; i < pieceOut.size(); i++) {
            int s = pieceSeg.get(i);
            String t = pieceOut.get(i);
            if (acc[s] == null) {
                acc[s] = new StringBuilder(t);
            } else {
                if (acc[s].length() > 0 && !t.isEmpty()
                        && isAsciiWord(acc[s].charAt(acc[s].length() - 1)) && isAsciiWord(t.charAt(0))) {
                    acc[s].append(' ');
                }
                acc[s].append(t);
            }
        }
        List<String> out = new ArrayList<>(segCount);
        for (StringBuilder sb : acc) {
            out.add(sb == null ? "" : sb.toString());
        }
        return out;
    }

    private static boolean isAsciiWord(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    // ---------------- 查询/下载/删除 ----------------

    public List<DocItem> list(Long userId) {
        return mapper.selectList(Wrappers.<DocTranslation>lambdaQuery()
                        .eq(DocTranslation::getUserId, userId)
                        .orderByDesc(DocTranslation::getId)
                        .last("limit " + LIST_LIMIT))
                .stream().map(this::toItem).toList();
    }

    public DocItem get(Long userId, Long id) {
        return toItem(getOwned(userId, id));
    }

    /** 下载译文：返回 [路径, 下载名]。 */
    public Object[] download(Long userId, Long id) {
        DocTranslation r = getOwned(userId, id);
        if (!"SUCCESS".equals(r.getStatus()) || !StringUtils.hasText(r.getOutputPath())) {
            throw new BizException("译文尚未生成");
        }
        Path p = Path.of(r.getOutputPath());
        if (!Files.exists(p)) {
            throw new BizException("译文文件已丢失，请重新翻译");
        }
        return new Object[]{p, r.getOutputName()};
    }

    /**
     * 内联预览用文件（对照页 iframe 里浏览器原生渲染 PDF/HTML）：
     * kind=source 给原文件，kind=output 给译文文件。返回 [路径, 文件名]。
     */
    public Object[] fileFor(Long userId, Long id, String kind) {
        DocTranslation r = getOwned(userId, id);
        if ("source".equals(kind)) {
            if (!StringUtils.hasText(r.getSourcePath()) || !Files.exists(Path.of(r.getSourcePath()))) {
                throw new BizException("源文件已丢失");
            }
            return new Object[]{Path.of(r.getSourcePath()), r.getFilename()};
        }
        return download(userId, id);
    }

    /**
     * 文档封面（工作台卡片）：PDF 用 pdfbox 渲染第一页 PNG 并缓存；
     * 非 PDF 返回 null（前端按格式渲染 preview 文本）。
     */
    public Path ensureThumbnail(Long userId, Long id) {
        DocTranslation r = getOwned(userId, id);
        if (!"pdf".equals(r.getFormat()) || !StringUtils.hasText(r.getSourcePath())) {
            return null;
        }
        Path src = Path.of(r.getSourcePath());
        if (!Files.exists(src)) {
            return null;
        }
        Path out = dir(id).resolve("thumb.png");
        if (Files.exists(out)) {
            return out;
        }
        synchronized (("aifanyi-doc-thumb-" + id).intern()) {
            if (Files.exists(out)) {
                return out;
            }
            try (org.apache.pdfbox.pdmodel.PDDocument pd =
                         org.apache.pdfbox.Loader.loadPDF(Files.readAllBytes(src))) {
                var renderer = new org.apache.pdfbox.rendering.PDFRenderer(pd);
                // 72dpi 足够卡片清晰度，再等比压到宽 480
                java.awt.image.BufferedImage img = renderer.renderImageWithDPI(0, 72);
                int w = 480;
                int h = Math.max(1, img.getHeight() * w / Math.max(1, img.getWidth()));
                java.awt.image.BufferedImage scaled =
                        new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
                var g = scaled.createGraphics();
                g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                        java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(img, 0, 0, w, h, null);
                g.dispose();
                javax.imageio.ImageIO.write(scaled, "png", out.toFile());
                return Files.exists(out) ? out : null;
            } catch (Exception e) {
                log.warn("文档 #{} PDF 缩略图生成失败: {}", id, e.getMessage());
                return null;
            }
        }
    }

    /** 原文-译文分段对照（供对照页面）。段级数据不入库，落在任务目录 segments.json。 */
    public DocCompare compare(Long userId, Long id) {
        DocTranslation r = getOwned(userId, id);
        if (!"SUCCESS".equals(r.getStatus())) {
            throw new BizException("翻译尚未完成，暂无对照数据");
        }
        Path f = dir(id).resolve(SEGMENTS_FILE);
        if (!Files.exists(f)) {
            // BabelDOC 路径（自带翻译，无段级数据）或旧版本任务：只给版式对照，段对为空
            return new DocCompare(r.getId(), r.getFilename(), r.getFormat(),
                    r.getTargetLang(), r.getModel(), List.of());
        }
        try {
            SegmentsFile sf = JSON.readValue(f.toFile(), SegmentsFile.class);
            List<SegmentPair> pairs = new ArrayList<>(sf.source().size());
            for (int i = 0; i < sf.source().size(); i++) {
                String src = sf.source().get(i);
                String dst = sf.target().get(i);
                pairs.add(new SegmentPair(i + 1, src, dst, src.strip().equals(dst.strip())));
            }
            return new DocCompare(r.getId(), r.getFilename(), r.getFormat(),
                    r.getTargetLang(), r.getModel(), pairs);
        } catch (IOException e) {
            throw new BizException("读取对照数据失败: " + e.getMessage());
        }
    }

    private void writeSegments(Long id, List<String> source, List<String> target) {
        try {
            JSON.writeValue(dir(id).resolve(SEGMENTS_FILE).toFile(),
                    new SegmentsFile(source, target));
        } catch (IOException e) {
            // 对照数据是附加能力，写失败不影响翻译结果本身
            log.warn("文档翻译 #{} 写对照数据失败: {}", id, e.getMessage());
        }
    }

    /** segments.json 的存储结构。 */
    record SegmentsFile(List<String> source, List<String> target) {
    }

    public void remove(Long userId, Long id) {
        getOwned(userId, id);
        mapper.deleteById(id);                              // 逻辑删除；运行中的线程会在波间检测到并中止
        deleteDirQuietly(dir(id));
    }

    private DocTranslation getOwned(Long userId, Long id) {
        DocTranslation r = mapper.selectById(id);
        if (r == null || !r.getUserId().equals(userId)) {
            throw new BizException("任务不存在");
        }
        return r;
    }

    // ---------------- 工具 ----------------

    private Path dir(Long id) {
        return docRoot.resolve(String.valueOf(id));
    }

    private void deleteDirQuietly(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException e) {
            log.warn("删除文档任务目录失败 {}: {}", dir, e.getMessage());
        }
    }

    private DocItem toItem(DocTranslation r) {
        return new DocItem(r.getId(), r.getFilename(), r.getFormat(), r.getOutputFormat(),
                r.getPreview(), r.getTargetLang(), r.getStatus(),
                r.getProgress() == null ? 0 : r.getProgress(),
                r.getTotalSegments() == null ? 0 : r.getTotalSegments(),
                r.getDoneSegments() == null ? 0 : r.getDoneSegments(),
                r.getErrorMsg(), r.getOutputName(), r.getModel(),
                r.getElapsedMs() == null ? 0 : r.getElapsedMs(),
                r.getUntranslatedSegments() == null ? 0 : r.getUntranslatedSegments(),
                r.getCreatedAt());
    }

    /** 内容预览：前几段原文拼接，压缩空白，截 300 字（工作台卡片渲染用）。 */
    private static String buildPreview(List<String> segments) {
        StringBuilder sb = new StringBuilder();
        for (String s : segments) {
            if (sb.length() >= 300) {
                break;
            }
            String flat = s.strip();
            if (flat.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(flat);
        }
        String out = sb.toString();
        return out.length() <= 300 ? out : out.substring(0, 300);
    }

    private static String ext(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String baseName(String name) {
        String n = name.replaceAll("[\\\\/]", "_");
        int dot = n.lastIndexOf('.');
        return dot <= 0 ? n : n.substring(0, dot);
    }

    private static String targetTag(String targetLang) {
        String t = targetLang == null ? "译文" : targetLang.trim();
        return t.isEmpty() ? "译文" : t.replaceAll("[\\s\\\\/]", "");
    }

    /** 用户可读的失败原因（BizException 原样，其他类型带上异常类名便于排查）。 */
    private static String readable(Exception e) {
        String msg = e.getMessage();
        if (e instanceof BizException || e instanceof IllegalArgumentException) {
            return msg;
        }
        return (msg == null ? e.getClass().getSimpleName()
                : msg + "（" + e.getClass().getSimpleName() + "）");
    }
}
