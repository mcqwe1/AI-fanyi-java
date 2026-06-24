package com.aifanyi.service;

import com.aifanyi.common.BizException;
import com.aifanyi.domain.TaskMode;
import com.aifanyi.domain.TaskStatus;
import com.aifanyi.entity.TranslationTask;
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
    private final StorageService storage;
    private final TaskPipeline pipeline;

    /**
     * 创建任务、保存上传视频并异步启动流水线。
     */
    public Long createAndStart(MultipartFile file, Long userId, String mode,
                               String sourceLang, String targetLang, String asrProvider,
                               String llmModel, boolean burn, boolean bilingual) {
        if (file == null || file.isEmpty()) {
            throw new BizException("视频文件为空");
        }
        TranslationTask task = new TranslationTask();
        task.setUserId(userId);
        task.setMode(mode == null ? TaskMode.NORMAL.name() : mode);
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
}
