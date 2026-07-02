package com.aifanyi.controller.dto;

import java.time.LocalDateTime;
import java.util.List;

public class KbDtos {

    public record CreateProjectReq(String name, String sourceLang, String targetLang) {
    }

    public record ProjectVO(
            Long id, String name, String sourceLang, String targetLang,
            int termCount, LocalDateTime createdAt
    ) {
    }

    public record TermVO(
            Long id, String sourceTerm, String targetTerm, String category, String note,
            String origin, Integer enabled
    ) {
    }

    /** 批量保存术语：有 id 视为更新，无 id 视为新增（origin=manual）。 */
    public record SaveTermsReq(List<TermInput> terms) {
        public record TermInput(Long id, String sourceTerm, String targetTerm,
                                String category, String note, Integer enabled) {
        }
    }
}
