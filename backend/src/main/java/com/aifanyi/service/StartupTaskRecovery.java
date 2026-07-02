package com.aifanyi.service;

import com.aifanyi.domain.TaskStatus;
import com.aifanyi.entity.TranslationTask;
import com.aifanyi.mapper.TranslationTaskMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动时把残留在“运行中”状态的任务标记为 FAILED。
 * 后端重启/崩溃会中断异步流水线线程，这些任务本会永久卡在中间状态（如 BUILDING_KB）；
 * 启动统一收尾后，用户可对其点“重试”重新处理。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StartupTaskRecovery implements ApplicationRunner {

    private final TranslationTaskMapper taskMapper;

    /** 非终态（DONE/FAILED 之外）即视为被中断的运行中任务。 */
    private static final List<String> RUNNING = List.of(
            TaskStatus.PENDING.name(),
            TaskStatus.EXTRACTING_AUDIO.name(),
            TaskStatus.TRANSCRIBING.name(),
            TaskStatus.ANALYZING_VIDEO.name(),
            TaskStatus.BUILDING_KB.name(),
            TaskStatus.TRANSLATING.name(),
            TaskStatus.BURNING.name());

    @Override
    public void run(ApplicationArguments args) {
        int n = taskMapper.update(null, Wrappers.<TranslationTask>lambdaUpdate()
                .in(TranslationTask::getStatus, RUNNING)
                .set(TranslationTask::getStatus, TaskStatus.FAILED.name())
                .set(TranslationTask::getErrorMsg, "后端重启导致任务中断，请点“重试”重新处理"));
        if (n > 0) {
            log.warn("启动恢复：{} 个残留在运行中状态的任务已标记 FAILED（可重试）", n);
        }
    }
}
