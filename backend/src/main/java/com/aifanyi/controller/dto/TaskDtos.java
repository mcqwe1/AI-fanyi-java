package com.aifanyi.controller.dto;

import java.time.LocalDateTime;

public class TaskDtos {

    public record CreateTaskResp(Long taskId) {
    }

    public record TaskVO(
            Long id,
            String mode,
            String status,
            Integer progress,
            String sourceLang,
            String targetLang,
            String asrProvider,
            String llmModel,
            Integer burnSubtitle,
            Integer bilingual,
            String originalFilename,
            String errorMsg,
            boolean hasVideo,
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
}
