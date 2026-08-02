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

    @Override
    public void run(ApplicationArguments args) {
        // 终态之外一律视为被中断（黑名单而非白名单）：新增运行中状态时无需再改这里，
        // 漏改会让任务永久卡死——retry 与字幕编辑都拒绝非终态，用户只能删任务。
        int n = taskMapper.update(null, Wrappers.<TranslationTask>lambdaUpdate()
                .notIn(TranslationTask::getStatus, TaskStatus.DONE.name(), TaskStatus.FAILED.name())
                .set(TranslationTask::getStatus, TaskStatus.FAILED.name())
                .set(TranslationTask::getErrorMsg, "后端重启导致任务中断，请点“重试”重新处理"));
        if (n > 0) {
            log.warn("启动恢复：{} 个残留在运行中状态的任务已标记 FAILED（可重试）", n);
        }
        // 配音是独立于主状态的附加操作：中断的配音只重置 dub_status，绝不动主 status/字幕
        int d = taskMapper.update(null, Wrappers.<TranslationTask>lambdaUpdate()
                .eq(TranslationTask::getDubStatus, "DUBBING")
                .set(TranslationTask::getDubStatus, "FAILED")
                .set(TranslationTask::getDubError, "后端重启导致配音中断，请重新配音"));
        if (d > 0) {
            log.warn("启动恢复：{} 个中断的配音已标记失败（可重新配音，翻译成果不受影响）", d);
        }
    }
}
