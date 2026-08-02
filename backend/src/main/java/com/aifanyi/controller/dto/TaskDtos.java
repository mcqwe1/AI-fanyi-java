package com.aifanyi.controller.dto;

import java.time.LocalDateTime;

public class TaskDtos {

    public record CreateTaskResp(Long taskId) {
    }

    /** 重试/复用已上传任务重跑时的可选覆盖参数（全部可空，空则沿用任务原值）。 */
    public record RetryReq(
            String asrProvider,
            String sourceLang,
            String targetLang,
            String llmModel,
            Boolean bilingual
    ) {
    }

    public record TaskVO(
            Long id,
            String mode,
            Long projectId,
            String status,
            Integer progress,
            String sourceLang,
            String targetLang,
            String asrProvider,
            String llmModel,
            Integer burnSubtitle,
            Integer bilingual,
            String mediaType,
            String originalFilename,
            String errorMsg,
            boolean hasVideo,
            boolean hasDub,
            String ttsVoice,
            String ttsSpeed,
            Integer ttsKeepOriginal,
            String dubStatus,
            Integer dubProgress,
            String dubError,
            String dubNotice,
            String agentDomain,
            String agentPhase,
            Integer agentDegraded,
            LocalDateTime createdAt
    ) {
    }

    public record SubtitleVO(
            Integer seq,
            Long startMs,
            Long endMs,
            String sourceText,
            String targetText
    ) {
    }

    /** 字幕编辑器批量保存：整表替换，服务端按 startMs 排序并重排 seq。 */
    public record SubtitleSaveReq(java.util.List<SubtitleLine> subtitles) {
    }

    public record SubtitleLine(
            Long startMs,
            Long endMs,
            String sourceText,
            String targetText
    ) {
    }
}
