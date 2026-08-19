package com.aifanyi.service;

import com.aifanyi.common.BizException;
import com.aifanyi.controller.dto.TaskDtos;
import com.aifanyi.controller.dto.TaskDtos.RetryReq;
import com.aifanyi.domain.MediaKind;
import com.aifanyi.domain.TaskMode;
import com.aifanyi.domain.TaskStatus;
import com.aifanyi.entity.Subtitle;
import com.aifanyi.entity.KbProject;
import com.aifanyi.entity.TranslationTask;
import com.aifanyi.mapper.SubtitleMapper;
import com.aifanyi.mapper.TranslationTaskMapper;
import com.aifanyi.media.SrtService;
import com.aifanyi.storage.StorageService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskService {

    private final TranslationTaskMapper taskMapper;
    private final SubtitleMapper subtitleMapper;
    private final com.aifanyi.mapper.KbProjectMapper projectMapper;
    private final StorageService storage;
    private final TaskPipeline pipeline;
    private final com.aifanyi.agent.AgentPipeline agentPipeline;
    private final com.aifanyi.media.FfmpegService ffmpeg;

    /** 风格提示词字符上限（与前端 textarea maxlength、DB 列宽一致）。 */
    private static final int STYLE_PROMPT_MAX_CHARS = 500;

    /**
     * 创建任务、保存上传的视频/音频并异步启动流水线。
     * 音频任务：流水线完全一致（ffmpeg 统一转 16k 单声道再转写），仅强制不可烧录。
     *
     * @param subtitleFile 可选的原文字幕（srt/vtt）。带了它就跳过抽音频与语音识别，
     *                     直接翻译这份字幕——实测能把 16.8 分钟视频的 140s 压到 10s 量级。
     */
    public Long createAndStart(MultipartFile file, Long userId, String mode, Long projectId,
                               String sourceLang, String targetLang, String asrProvider,
                               String llmModel, boolean burn, boolean bilingual, String stylePrompt,
                               String glossaryProjectIds, MultipartFile subtitleFile) {
        if (file == null || file.isEmpty()) {
            throw new BizException("上传文件为空");
        }
        MediaKind kind = MediaKind.fromFilename(file.getOriginalFilename());
        if (kind == null) {
            throw new BizException("不支持的文件格式：请上传常见视频（mp4/mkv/mov…）、音频（mp3/wav/m4a…）"
                    + "或文本（txt/md/srt/vtt）");
        }
        boolean agent = TaskMode.AGENT.name().equalsIgnoreCase(mode);
        if (kind == MediaKind.TEXT && !agent) {
            // 文本链目前只在 Agent 模式接入；普通文本翻译走 /api/translate（同步接口）
            throw new BizException("文本文件请使用「AI 文本翻译」模式，或选择「全能 AI 翻译」");
        }
        boolean hasSubtitle = subtitleFile != null && !subtitleFile.isEmpty();
        if (hasSubtitle) {
            if (kind == MediaKind.TEXT) {
                throw new BizException("文本任务本身就是文字，不需要再带字幕文件");
            }
            if (!com.aifanyi.media.SubtitleParser.isSubtitleFile(subtitleFile.getOriginalFilename())) {
                throw new BizException("自带字幕只支持 .srt / .vtt 文件");
            }
        }
        // projectId = 新词入库目标。必须校验归属：它会被直接写进术语库，
        // 不校验等于任何人都能往别人的术语库里塞词。
        if (projectId != null) {
            KbProject p = projectMapper.selectById(projectId);
            if (p == null || !userId.equals(p.getUserId())) {
                throw new BizException("指定的术语库不存在或不属于你");
            }
        }
        TranslationTask task = new TranslationTask();
        task.setUserId(userId);
        task.setMode(mode == null ? TaskMode.NORMAL.name() : mode);
        task.setProjectId(projectId);
        task.setStatus(TaskStatus.PENDING.name());
        task.setSourceLang(sourceLang == null ? "auto" : sourceLang);
        task.setTargetLang(targetLang == null ? "中文" : targetLang);
        task.setAsrProvider(asrProvider == null ? "groq" : asrProvider);
        task.setLlmModel(llmModel);
        task.setMediaType(kind.name());
        // 仅视频可烧录（音频与文本任务产物是 SRT/TXT）
        task.setBurnSubtitle(kind == MediaKind.VIDEO && burn ? 1 : 0);
        task.setBilingual(bilingual ? 1 : 0);
        task.setStylePrompt(normalizeStylePrompt(stylePrompt));
        task.setGlossaryProjectIds(normalizeGlossaryIds(glossaryProjectIds));
        task.setOriginalFilename(file.getOriginalFilename());
        task.setProgress(0);
        taskMapper.insert(task);

        try {
            Path saved = storage.saveUpload(file, task.getId());
            task.setVideoPath(saved.toString());
            if (hasSubtitle) {
                Path sub = storage.resolve(task.getId(), "supplied" + ext(subtitleFile.getOriginalFilename()));
                subtitleFile.transferTo(sub);
                task.setSubtitleSourcePath(sub.toString());
            }
            taskMapper.updateById(task);
        } catch (Exception e) {
            throw new BizException("保存上传文件失败: " + e.getMessage());
        }

        dispatch(task.getMode(), task.getId());
        return task.getId();
    }

    /** 取扩展名（带点，小写）；没有扩展名回退 .srt。 */
    private static String ext(String filename) {
        if (filename == null) {
            return ".srt";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? ".srt" : filename.substring(dot).toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 按模式分发到对应流水线。
     * <p>必须从本类调用（跨 bean），@Async 自调用会绕过代理退化成同步执行。
     */
    private void dispatch(String mode, Long taskId) {
        if (TaskMode.AGENT.name().equalsIgnoreCase(mode)) {
            agentPipeline.runAsync(taskId);
        } else {
            pipeline.runAsync(taskId);
        }
    }

    public List<TranslationTask> listByUser(Long userId) {
        return taskMapper.selectList(Wrappers.<TranslationTask>lambdaQuery()
                .eq(TranslationTask::getUserId, userId)
                .orderByDesc(TranslationTask::getId));
    }

    public TranslationTask getOwned(Long taskId, Long userId) {
        TranslationTask t = taskMapper.selectById(taskId);
        if (t == null || !t.getUserId().equals(userId)) {
            throw new BizException(404, "任务不存在");
        }
        return t;
    }

    /**
     * 删除任务：校验归属 → 物理删除字幕记录 → 删除磁盘工作目录 → 逻辑删除任务行。
     * 注：subtitle 表无逻辑删除，磁盘文件无自动清理，故都在此显式处理。
     */
    public void delete(Long taskId, Long userId) {
        getOwned(taskId, userId); // 不存在或非本人 → 404
        subtitleMapper.delete(Wrappers.<Subtitle>lambdaQuery()
                .eq(Subtitle::getTaskId, taskId));
        storage.deleteTaskDir(taskId);
        taskMapper.deleteById(taskId); // @TableLogic → UPDATE deleted=1
        log.info("已删除任务 {}（用户 {}）", taskId, userId);
    }

    /**
     * 复用已上传任务重跑流水线：校验归属与原视频存在，可选覆盖 ASR/语言/模型，
     * 清旧字幕、重置状态后重新异步执行。仅允许对 DONE / FAILED 的任务重试。
     */
    public void retry(Long taskId, Long userId, RetryReq req) {
        TranslationTask task = getOwned(taskId, userId);
        String st = task.getStatus();
        if (!TaskStatus.DONE.name().equals(st) && !TaskStatus.FAILED.name().equals(st)) {
            throw new BizException("任务正在处理中，无法重试");
        }
        if (task.getVideoPath() == null || !java.nio.file.Files.exists(Path.of(task.getVideoPath()))) {
            throw new BizException("原上传文件不存在，无法重跑，请重新上传");
        }
        // 清旧字幕（subtitle 物理删除）
        subtitleMapper.delete(Wrappers.<Subtitle>lambdaQuery().eq(Subtitle::getTaskId, taskId));
        // 重置状态 + 可选覆盖（用 lambdaUpdate 以便把 errorMsg/旧产物显式置空——updateById 会忽略 null）
        var upd = Wrappers.<TranslationTask>lambdaUpdate()
                .eq(TranslationTask::getId, taskId)
                .set(TranslationTask::getStatus, TaskStatus.PENDING.name())
                .set(TranslationTask::getProgress, 0)
                .set(TranslationTask::getErrorMsg, null)
                .set(TranslationTask::getSrtPath, null)
                .set(TranslationTask::getOutputVideoPath, null)
                .set(TranslationTask::getDubVideoPath, null);
        if (req != null) {
            if (notBlank(req.asrProvider())) upd.set(TranslationTask::getAsrProvider, req.asrProvider());
            if (notBlank(req.sourceLang())) upd.set(TranslationTask::getSourceLang, req.sourceLang());
            if (notBlank(req.targetLang())) upd.set(TranslationTask::getTargetLang, req.targetLang());
            if (req.llmModel() != null) upd.set(TranslationTask::getLlmModel, req.llmModel());
            if (req.bilingual() != null) upd.set(TranslationTask::getBilingual, req.bilingual() ? 1 : 0);
        }
        taskMapper.update(null, upd);
        dispatch(task.getMode(), taskId);
        log.info("重试任务 {}（用户 {}）", taskId, userId);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * 字幕编辑器保存：整表替换（按 startMs 排序、seq 重排、钳制非法时间），
     * 并重新生成 SRT 文件（沿用任务的双语设置）。仅允许对已结束的任务编辑，
     * 避免与流水线落库互相覆盖。
     *
     * @return 保存后的行数
     */
    public int replaceSubtitles(Long taskId, Long userId, List<TaskDtos.SubtitleLine> lines) {
        TranslationTask task = getOwned(taskId, userId);
        String st = task.getStatus();
        if (!TaskStatus.DONE.name().equals(st) && !TaskStatus.FAILED.name().equals(st)) {
            throw new BizException("任务正在处理中，暂不能编辑字幕");
        }
        List<Subtitle> subs = new ArrayList<>();
        if (lines != null) {
            for (TaskDtos.SubtitleLine l : lines) {
                if (l == null) {
                    continue;
                }
                String src = l.sourceText() == null ? "" : l.sourceText().trim();
                String tgt = l.targetText() == null ? "" : l.targetText().trim();
                if (src.isEmpty() && tgt.isEmpty()) {
                    continue;                       // 两栏全空的行直接丢弃
                }
                long start = l.startMs() == null ? 0 : Math.max(0, l.startMs());
                long end = l.endMs() == null ? start + 1000 : l.endMs();
                if (end <= start) {
                    end = start + 500;              // 非法区间兜底,不因手误丢行
                }
                Subtitle s = new Subtitle();
                s.setTaskId(taskId);
                s.setStartMs(start);
                s.setEndMs(end);
                s.setSourceText(src);
                s.setTargetText(tgt);
                subs.add(s);
            }
        }
        if (subs.isEmpty()) {
            throw new BizException("至少保留一行字幕");
        }
        subs.sort(Comparator.comparingLong(Subtitle::getStartMs));
        for (int i = 0; i < subs.size(); i++) {
            subs.get(i).setSeq(i + 1);
        }

        subtitleMapper.delete(Wrappers.<Subtitle>lambdaQuery().eq(Subtitle::getTaskId, taskId));
        for (Subtitle s : subs) {
            subtitleMapper.insert(s);
        }

        // 重新生成 SRT（失败任务此前可能没有 srtPath，编辑保存后即产生）
        try {
            String srt = task.getBilingual() != null && task.getBilingual() == 1
                    ? SrtService.toBilingualSrt(subs)
                    : SrtService.toSrt(subs);
            Path srtPath = storage.resolve(taskId, "subtitle.srt");
            Files.writeString(srtPath, srt, StandardCharsets.UTF_8);
            if (task.getSrtPath() == null) {
                task.setSrtPath(srtPath.toString());
                taskMapper.updateById(task);
            }
        } catch (Exception e) {
            throw new BizException("字幕已保存，但重写 SRT 文件失败: " + e.getMessage());
        }
        log.info("字幕编辑保存: 任务 {} 共 {} 行（用户 {}）", taskId, subs.size(), userId);
        return subs.size();
    }

    /**
     * 任务封面（工作台「最近项目」卡片）：视频=某一帧、音频=波形图。
     * 首次请求时用 ffmpeg 生成并缓存在任务目录，之后直接命中文件。
     * TEXT 任务或源文件已被清理时返回 null（前端退回文本样式卡片）。
     */
    public Path ensureCover(TranslationTask task) {
        boolean video = MediaKind.VIDEO.name().equals(task.getMediaType());
        boolean audio = MediaKind.AUDIO.name().equals(task.getMediaType());
        if (!video && !audio) {
            return null;
        }
        Path out = storage.resolve(task.getId(), video ? "cover.jpg" : "cover.png");
        if (Files.exists(out)) {
            return out;
        }
        if (task.getVideoPath() == null) {
            return null;
        }
        Path src = Path.of(task.getVideoPath());
        if (!Files.exists(src)) {
            return null;
        }
        // 同一任务并发首次请求会同时起 ffmpeg 写同一文件，按任务串行化
        synchronized (("aifanyi-cover-" + task.getId()).intern()) {
            if (Files.exists(out)) {
                return out;
            }
            try {
                if (video) {
                    // 跳过片头黑场：取 10% 处，上限 10 秒
                    double dur = ffmpeg.probeDurationSec(src);
                    double seek = dur > 0 ? Math.min(10, dur * 0.1) : 1;
                    ffmpeg.coverFrame(src, out, seek);
                } else {
                    ffmpeg.coverWaveform(src, out);
                }
                return Files.exists(out) ? out : null;
            } catch (Exception e) {
                log.warn("任务 {} 封面生成失败: {}", task.getId(), e.getMessage());
                return null;
            }
        }
    }

    /** 归一化风格提示词：空白→null（不启用），超长直接报错（与 DB 列宽一致）。 */
    static String normalizeStylePrompt(String stylePrompt) {
        if (stylePrompt == null || stylePrompt.isBlank()) {
            return null;
        }
        String s = stylePrompt.trim();
        if (s.length() > STYLE_PROMPT_MAX_CHARS) {
            throw new BizException("风格提示词过长（" + s.length() + " 字，上限 " + STYLE_PROMPT_MAX_CHARS + "）");
        }
        return s;
    }

    /** 归一化勾选的术语库 id 串：只留合法数字、去重，空/非法→null（不套用）。 */
    static String normalizeGlossaryIds(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        for (String p : raw.split(",")) {
            String t = p.trim();
            if (t.matches("\\d+") && !"0".equals(t)) {
                ids.add(t);
            }
        }
        return ids.isEmpty() ? null : String.join(",", ids);
    }
}
