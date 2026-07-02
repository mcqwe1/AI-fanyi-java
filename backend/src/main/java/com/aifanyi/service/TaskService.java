package com.aifanyi.service;

import com.aifanyi.common.BizException;
import com.aifanyi.controller.dto.TaskDtos.RetryReq;
import com.aifanyi.domain.TaskMode;
import com.aifanyi.domain.TaskStatus;
import com.aifanyi.entity.Subtitle;
import com.aifanyi.entity.TranslationTask;
import com.aifanyi.mapper.SubtitleMapper;
import com.aifanyi.mapper.TranslationTaskMapper;
import com.aifanyi.storage.StorageService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskService {

    private final TranslationTaskMapper taskMapper;
    private final SubtitleMapper subtitleMapper;
    private final StorageService storage;
    private final TaskPipeline pipeline;

    /**
     * 创建任务、保存上传视频并异步启动流水线。
     */
    public Long createAndStart(MultipartFile file, Long userId, String mode, Long projectId,
                               String sourceLang, String targetLang, String asrProvider,
                               String llmModel, boolean burn, boolean bilingual) {
        if (file == null || file.isEmpty()) {
            throw new BizException("视频文件为空");
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
        task.setBurnSubtitle(burn ? 1 : 0);
        task.setBilingual(bilingual ? 1 : 0);
        task.setOriginalFilename(file.getOriginalFilename());
        task.setProgress(0);
        taskMapper.insert(task);

        try {
            Path saved = storage.saveUpload(file, task.getId());
            task.setVideoPath(saved.toString());
            taskMapper.updateById(task);
        } catch (Exception e) {
            throw new BizException("保存上传文件失败: " + e.getMessage());
        }

        pipeline.runAsync(task.getId());
        return task.getId();
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
            throw new BizException("原视频文件不存在，无法重跑，请重新上传");
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
                .set(TranslationTask::getOutputVideoPath, null);
        if (req != null) {
            if (notBlank(req.asrProvider())) upd.set(TranslationTask::getAsrProvider, req.asrProvider());
            if (notBlank(req.sourceLang())) upd.set(TranslationTask::getSourceLang, req.sourceLang());
            if (notBlank(req.targetLang())) upd.set(TranslationTask::getTargetLang, req.targetLang());
            if (req.llmModel() != null) upd.set(TranslationTask::getLlmModel, req.llmModel());
            if (req.bilingual() != null) upd.set(TranslationTask::getBilingual, req.bilingual() ? 1 : 0);
        }
        taskMapper.update(null, upd);
        pipeline.runAsync(taskId);
        log.info("重试任务 {}（用户 {}）", taskId, userId);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
