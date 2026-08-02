package com.aifanyi.controller;

import com.aifanyi.common.BizException;
import com.aifanyi.common.R;
import com.aifanyi.controller.dto.TaskDtos.CreateTaskResp;
import com.aifanyi.controller.dto.TaskDtos.RetryReq;
import com.aifanyi.controller.dto.TaskDtos.SubtitleSaveReq;
import com.aifanyi.controller.dto.TaskDtos.SubtitleVO;
import com.aifanyi.controller.dto.TaskDtos.TaskVO;
import com.aifanyi.domain.MediaKind;
import com.aifanyi.domain.SubtitleStyle;
import com.aifanyi.entity.Subtitle;
import com.aifanyi.entity.TranslationTask;
import com.aifanyi.mapper.SubtitleMapper;
import com.aifanyi.security.SecurityUtils;
import com.aifanyi.service.BurnService;
import com.aifanyi.service.TaskService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;
    private final SubtitleMapper subtitleMapper;
    private final BurnService burnService;

    @PostMapping
    public R<CreateTaskResp> create(
            @RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = "NORMAL") String mode,
            @RequestParam(required = false) Long projectId,
            @RequestParam(defaultValue = "auto") String sourceLang,
            @RequestParam(defaultValue = "中文") String targetLang,
            @RequestParam(defaultValue = "groq") String asrProvider,
            @RequestParam(required = false) String llmModel,
            @RequestParam(defaultValue = "false") boolean burnSubtitle,
            @RequestParam(defaultValue = "false") boolean bilingual,
            @RequestParam(required = false) String stylePrompt) {
        Long uid = SecurityUtils.currentUserId();
        Long taskId = taskService.createAndStart(file, uid, mode, projectId, sourceLang, targetLang,
                asrProvider, llmModel, burnSubtitle, bilingual, stylePrompt);
        return R.ok(new CreateTaskResp(taskId));
    }

    @GetMapping
    public R<List<TaskVO>> list() {
        Long uid = SecurityUtils.currentUserId();
        List<TaskVO> vos = taskService.listByUser(uid).stream().map(this::toVO).toList();
        return R.ok(vos);
    }

    @GetMapping("/{id}")
    public R<TaskVO> detail(@PathVariable Long id) {
        Long uid = SecurityUtils.currentUserId();
        return R.ok(toVO(taskService.getOwned(id, uid)));
    }

    /** 删除任务（含字幕记录与磁盘文件）。 */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        Long uid = SecurityUtils.currentUserId();
        taskService.delete(id, uid);
        return R.ok();
    }

    /** 复用已上传视频重跑流水线（可选覆盖 ASR/语言/模型），不必重新上传。 */
    @PostMapping("/{id}/retry")
    public R<Void> retry(@PathVariable Long id, @RequestBody(required = false) RetryReq req) {
        Long uid = SecurityUtils.currentUserId();
        taskService.retry(id, uid, req);
        return R.ok();
    }

    @GetMapping("/{id}/subtitles")
    public R<List<SubtitleVO>> subtitles(@PathVariable Long id) {
        Long uid = SecurityUtils.currentUserId();
        taskService.getOwned(id, uid); // 校验归属
        List<SubtitleVO> list = subtitleMapper.selectList(
                        Wrappers.<Subtitle>lambdaQuery()
                                .eq(Subtitle::getTaskId, id)
                                .orderByAsc(Subtitle::getSeq))
                .stream()
                .map(s -> new SubtitleVO(s.getSeq(), s.getStartMs(), s.getEndMs(),
                        s.getSourceText(), s.getTargetText()))
                .toList();
        return R.ok(list);
    }

    /** 字幕编辑器保存：整表替换 + 重生成 SRT。 */
    @PutMapping("/{id}/subtitles")
    public R<Integer> saveSubtitles(@PathVariable Long id,
                                    @RequestBody SubtitleSaveReq req) {
        Long uid = SecurityUtils.currentUserId();
        int n = taskService.replaceSubtitles(id, uid, req == null ? null : req.subtitles());
        return R.ok(n);
    }

    /**
     * 源视频/音频串流（支持 HTTP Range），供字幕编辑器的播放器加载。
     * track=audio 时改发流水线抽出的 audio.mp3——浏览器放不了的容器（mkv/avi/wmv…）
     * 前端会自动降级成音频对轴。
     * 返回类型固定为 ResourceRegion：Spring 只为这一具体类型注册 Range 消息转换器，
     * 用 ResponseEntity&lt;?&gt; 会导致 206 分片报 "No converter for ResourceRegion"。
     */
    @GetMapping("/{id}/media")
    public ResponseEntity<ResourceRegion> media(@PathVariable Long id,
                                                @RequestParam(required = false) String track,
                                                @RequestHeader HttpHeaders headers) {
        Long uid = SecurityUtils.currentUserId();
        TranslationTask task = taskService.getOwned(id, uid);
        boolean wantDub = "dub".equalsIgnoreCase(track);
        boolean wantAudio = "audio".equalsIgnoreCase(track)
                || MediaKind.AUDIO.name().equals(task.getMediaType());
        String path;
        if (wantDub) {
            path = task.getDubVideoPath();
            wantAudio = false;
        } else {
            path = wantAudio && task.getAudioPath() != null ? task.getAudioPath() : task.getVideoPath();
        }
        if (path == null) {
            throw new BizException(wantDub ? "配音视频尚未生成" : "源文件不存在");
        }
        FileSystemResource res = new FileSystemResource(Path.of(path));
        if (!res.exists()) {
            throw new BizException("源文件不存在");
        }
        long len;
        try {
            len = res.contentLength();
        } catch (IOException e) {
            throw new BizException("读取源文件失败");
        }
        MediaType ct = mediaTypeOf(path, wantAudio);

        List<HttpRange> ranges = headers.getRange();
        // 无 Range（部分浏览器首帧直接 GET）→ region 覆盖整文件，200
        if (ranges.isEmpty()) {
            return ResponseEntity.ok()
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .contentType(ct)
                    .body(new ResourceRegion(res, 0, len));
        }
        // 有 Range → 完整响应请求区间（磁盘流式写出，不占内存；浏览器取够会自行断开）。
        // 切勿人为掐小分片：曾按 1MB 上限分片，大视频被迫发起成百上千次请求，800MB 要加载 2~3 分钟。
        HttpRange r = ranges.get(0);
        long start = r.getRangeStart(len);
        long end = r.getRangeEnd(len);
        ResourceRegion region = new ResourceRegion(res, start, end - start + 1);
        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .contentType(ct)
                .body(region);
    }

    /** 按扩展名给出播放用 Content-Type；浏览器不认识的容器仍标成近似类型，由前端 onerror 降级音频。 */
    private static MediaType mediaTypeOf(String path, boolean audio) {
        String p = path.toLowerCase(java.util.Locale.ROOT);
        String type;
        if (audio) {
            if (p.endsWith(".wav")) type = "audio/wav";
            else if (p.endsWith(".flac")) type = "audio/flac";
            else if (p.endsWith(".ogg") || p.endsWith(".opus")) type = "audio/ogg";
            else if (p.endsWith(".m4a") || p.endsWith(".aac")) type = "audio/mp4";
            else type = "audio/mpeg";
        } else {
            if (p.endsWith(".webm")) type = "video/webm";
            else if (p.endsWith(".mkv")) type = "video/x-matroska";
            else if (p.endsWith(".ogv")) type = "video/ogg";
            else type = "video/mp4";
        }
        return MediaType.parseMediaType(type);
    }

    @GetMapping("/{id}/srt")
    public ResponseEntity<FileSystemResource> downloadSrt(@PathVariable Long id) {
        Long uid = SecurityUtils.currentUserId();
        TranslationTask task = taskService.getOwned(id, uid);
        if (task.getSrtPath() == null) {
            throw new BizException("字幕尚未生成");
        }
        FileSystemResource res = new FileSystemResource(Path.of(task.getSrtPath()));
        if (!res.exists()) {
            throw new BizException("字幕文件不存在");
        }
        String filename = encode("subtitle-" + id + ".srt");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"; filename*=UTF-8''" + filename)
                .contentType(MediaType.parseMediaType("application/x-subrip"))
                .body(res);
    }

    /** 下载纯译文文本（一行一句，播客/音频转录场景）。 */
    @GetMapping("/{id}/txt")
    public ResponseEntity<byte[]> downloadTxt(@PathVariable Long id) {
        Long uid = SecurityUtils.currentUserId();
        taskService.getOwned(id, uid); // 校验归属
        List<Subtitle> subs = subtitleMapper.selectList(
                Wrappers.<Subtitle>lambdaQuery()
                        .eq(Subtitle::getTaskId, id)
                        .orderByAsc(Subtitle::getSeq));
        if (subs.isEmpty()) {
            throw new BizException("字幕尚未生成");
        }
        StringBuilder sb = new StringBuilder();
        for (Subtitle s : subs) {
            sb.append(s.getTargetText() == null ? "" : s.getTargetText()).append("\r\n");
        }
        String filename = encode("transcript-" + id + ".txt");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"; filename*=UTF-8''" + filename)
                .contentType(MediaType.parseMediaType("text/plain;charset=UTF-8"))
                .body(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** 字幕样式预览：抽一帧 + 烧样例字幕，返回 JPG 图片。 */
    @PostMapping("/{id}/style/preview")
    public ResponseEntity<byte[]> stylePreview(@PathVariable Long id,
                                               @RequestBody(required = false) SubtitleStyle style) {
        Long uid = SecurityUtils.currentUserId();
        TranslationTask task = taskService.getOwned(id, uid);
        requireVideoTask(task);
        if (task.getVideoPath() == null) {
            throw new BizException("视频不存在");
        }
        Path jpg = burnService.previewImage(task, style == null ? new SubtitleStyle() : style);
        try {
            byte[] bytes = Files.readAllBytes(jpg);
            return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).body(bytes);
        } catch (IOException e) {
            throw new BizException("读取预览图失败: " + e.getMessage());
        }
    }

    /** 按样式把字幕烧录进视频（异步）。 */
    @PostMapping("/{id}/burn")
    public R<Void> burn(@PathVariable Long id, @RequestBody(required = false) SubtitleStyle style) {
        Long uid = SecurityUtils.currentUserId();
        TranslationTask task = taskService.getOwned(id, uid);
        requireVideoTask(task);
        if (task.getSrtPath() == null) {
            throw new BizException("请先完成字幕翻译再烧录");
        }
        burnService.burnAsync(id, style == null ? new SubtitleStyle() : style);
        return R.ok();
    }

    /** 下载烧录后的视频。 */
    @GetMapping("/{id}/video")
    public ResponseEntity<FileSystemResource> downloadVideo(@PathVariable Long id) {
        Long uid = SecurityUtils.currentUserId();
        TranslationTask task = taskService.getOwned(id, uid);
        if (task.getOutputVideoPath() == null) {
            throw new BizException("烧录视频尚未生成");
        }
        FileSystemResource res = new FileSystemResource(Path.of(task.getOutputVideoPath()));
        if (!res.exists()) {
            throw new BizException("烧录视频文件不存在");
        }
        String filename = encode("burned-" + id + ".mp4");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"; filename*=UTF-8''" + filename)
                .contentType(MediaType.parseMediaType("video/mp4"))
                .body(res);
    }

    /** 下载配音合成视频。 */
    @GetMapping("/{id}/dub-video")
    public ResponseEntity<FileSystemResource> downloadDubVideo(@PathVariable Long id) {
        Long uid = SecurityUtils.currentUserId();
        TranslationTask task = taskService.getOwned(id, uid);
        if (task.getDubVideoPath() == null) {
            throw new BizException("配音视频尚未生成");
        }
        FileSystemResource res = new FileSystemResource(Path.of(task.getDubVideoPath()));
        if (!res.exists()) {
            throw new BizException("配音视频文件不存在");
        }
        String filename = encode("dubbed-" + id + ".mp4");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"; filename*=UTF-8''" + filename)
                .contentType(MediaType.parseMediaType("video/mp4"))
                .body(res);
    }

    /** 烧录/样式预览仅对视频任务开放；音频/文本任务产物是 SRT/TXT。 */
    private static void requireVideoTask(TranslationTask task) {
        // 白名单判定：新增媒体类型（如 TEXT）不会被误放行到 ffmpeg 路径
        if (!MediaKind.VIDEO.name().equals(task.getMediaType())) {
            throw new BizException("该任务不支持烧录字幕，请下载 SRT 或译文 TXT");
        }
    }

    private String encode(String s) {
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8.name()).replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    private TaskVO toVO(TranslationTask t) {
        return new TaskVO(t.getId(), t.getMode(), t.getProjectId(), t.getStatus(), t.getProgress(),
                t.getSourceLang(), t.getTargetLang(), t.getAsrProvider(), t.getLlmModel(),
                t.getBurnSubtitle(), t.getBilingual(), t.getMediaType(), t.getOriginalFilename(),
                t.getErrorMsg(), t.getOutputVideoPath() != null, t.getDubVideoPath() != null,
                t.getTtsVoice(), t.getTtsSpeed(), t.getTtsKeepOriginal(),
                t.getDubStatus(), t.getDubProgress(), t.getDubError(), t.getDubNotice(),
                t.getAgentDomain(), t.getAgentPhase(), t.getAgentDegraded(),
                t.getCreatedAt());
    }
}
