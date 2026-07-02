package com.aifanyi.controller;

import com.aifanyi.common.BizException;
import com.aifanyi.common.R;
import com.aifanyi.controller.dto.TaskDtos.CreateTaskResp;
import com.aifanyi.controller.dto.TaskDtos.RetryReq;
import com.aifanyi.controller.dto.TaskDtos.SubtitleVO;
import com.aifanyi.controller.dto.TaskDtos.TaskVO;
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
import org.springframework.http.HttpHeaders;
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
            @RequestParam(defaultValue = "false") boolean bilingual) {
        Long uid = SecurityUtils.currentUserId();
        Long taskId = taskService.createAndStart(file, uid, mode, projectId, sourceLang, targetLang,
                asrProvider, llmModel, burnSubtitle, bilingual);
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

    /** 字幕样式预览：抽一帧 + 烧样例字幕，返回 JPG 图片。 */
    @PostMapping("/{id}/style/preview")
    public ResponseEntity<byte[]> stylePreview(@PathVariable Long id,
                                               @RequestBody(required = false) SubtitleStyle style) {
        Long uid = SecurityUtils.currentUserId();
        TranslationTask task = taskService.getOwned(id, uid);
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
                t.getBurnSubtitle(), t.getBilingual(), t.getOriginalFilename(),
                t.getErrorMsg(), t.getOutputVideoPath() != null, t.getCreatedAt());
    }
}
