package com.aifanyi.controller;

import com.aifanyi.common.R;
import com.aifanyi.entity.DocTranslation;
import com.aifanyi.entity.Subtitle;
import com.aifanyi.entity.TextTranslation;
import com.aifanyi.entity.TranslationTask;
import com.aifanyi.mapper.DocTranslationMapper;
import com.aifanyi.mapper.SubtitleMapper;
import com.aifanyi.mapper.TextTranslationMapper;
import com.aifanyi.mapper.TranslationTaskMapper;
import com.aifanyi.security.SecurityUtils;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 统一翻译历史（2026-08 需求改版）：聚合三张互不相通的表——
 * translation_task（音视频/Agent 任务）、doc_translation（文档翻译）、
 * text_translation（文本翻译 + 扩展划词/整页/图片）。
 * 工作台「最近项目」卡片与「翻译历史」页共用此接口。
 */
@RestController
@RequestMapping("/api/history")
@RequiredArgsConstructor
public class HistoryController {

    private final TranslationTaskMapper taskMapper;
    private final DocTranslationMapper docMapper;
    private final TextTranslationMapper textMapper;
    private final SubtitleMapper subtitleMapper;

    /**
     * 一条统一历史记录。
     * kind: media=音视频/Agent 任务 / doc=文档翻译 / text=文本类（含扩展）。
     * mode: media 为任务模式（NORMAL/KB/AGENT）；text 为渠道（text/file/ext_text/ext_page/ext_image）。
     * thumb: 封面相对 URL（img 标签自行拼 ?access_token=），无封面为 null。
     * previewText: 文档/文本的内容预览（前端按 format 的显示方式渲染）。
     */
    public record HistoryVO(
            String kind,
            Long id,
            String mode,
            String mediaType,
            String title,
            String format,
            String sourceLang,
            String targetLang,
            String status,
            Integer progress,
            LocalDateTime createdAt,
            String thumb,
            String previewText) {
    }

    @GetMapping("/all")
    public R<List<HistoryVO>> all(@RequestParam(defaultValue = "40") int limit) {
        Long uid = SecurityUtils.currentUserId();
        int n = Math.max(1, Math.min(100, limit));
        List<HistoryVO> out = new ArrayList<>();

        for (TranslationTask t : taskMapper.selectList(Wrappers.<TranslationTask>lambdaQuery()
                .eq(TranslationTask::getUserId, uid)
                .orderByDesc(TranslationTask::getId)
                .last("limit " + n))) {
            boolean hasCover = "VIDEO".equals(t.getMediaType()) || "AUDIO".equals(t.getMediaType());
            out.add(new HistoryVO("media", t.getId(), t.getMode(), t.getMediaType(),
                    t.getOriginalFilename(), extOf(t.getOriginalFilename()),
                    t.getSourceLang(), t.getTargetLang(), t.getStatus(), t.getProgress(),
                    t.getCreatedAt(),
                    hasCover ? "/api/tasks/" + t.getId() + "/thumbnail" : null,
                    hasCover ? null : textTaskPreview(t.getId())));
        }

        for (DocTranslation d : docMapper.selectList(Wrappers.<DocTranslation>lambdaQuery()
                .eq(DocTranslation::getUserId, uid)
                .orderByDesc(DocTranslation::getId)
                .last("limit " + n))) {
            out.add(new HistoryVO("doc", d.getId(), null, null,
                    d.getFilename(), d.getFormat(),
                    null, d.getTargetLang(), d.getStatus(), d.getProgress(),
                    d.getCreatedAt(),
                    "pdf".equals(d.getFormat()) ? "/api/doc/" + d.getId() + "/thumbnail" : null,
                    d.getPreview()));
        }

        for (TextTranslation x : textMapper.selectList(Wrappers.<TextTranslation>lambdaQuery()
                .select(TextTranslation::getId, TextTranslation::getChannel,
                        TextTranslation::getTargetLang, TextTranslation::getPreview,
                        TextTranslation::getCreatedAt)
                .eq(TextTranslation::getUserId, uid)
                .orderByDesc(TextTranslation::getId)
                .last("limit " + n))) {
            out.add(new HistoryVO("text", x.getId(),
                    x.getChannel() == null ? "text" : x.getChannel(), null,
                    x.getPreview(), "txt",
                    null, x.getTargetLang(), "SUCCESS", 100,
                    x.getCreatedAt(), null, x.getPreview()));
        }

        out.sort(Comparator.comparing(HistoryVO::createdAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return R.ok(out.size() > n ? out.subList(0, n) : out);
    }

    /** Agent 文本任务的内容预览：字幕表前 3 行原文（无时间轴，行即段落）。 */
    private String textTaskPreview(Long taskId) {
        List<Subtitle> rows = subtitleMapper.selectList(Wrappers.<Subtitle>lambdaQuery()
                .eq(Subtitle::getTaskId, taskId)
                .orderByAsc(Subtitle::getSeq)
                .last("limit 3"));
        if (rows.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Subtitle s : rows) {
            if (s.getSourceText() == null || s.getSourceText().isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(s.getSourceText().strip());
        }
        String t = sb.toString();
        return t.length() <= 300 ? t : t.substring(0, 300);
    }

    private static String extOf(String filename) {
        if (filename == null) {
            return null;
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? null : filename.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }
}
