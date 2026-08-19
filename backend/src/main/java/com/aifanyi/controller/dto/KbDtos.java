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
            Long id, String sourceTerm, String targetTerm, String note,
            String origin, Integer enabled,
            /** ACTIVE=已启用 / CANDIDATE=Agent 抽出但暂无佐证的备选，待用户点头；手工录入为 ACTIVE */
            String status
    ) {
    }

    /** 批量保存术语：有 id 视为更新，无 id 视为新增（origin=manual）。 */
    public record SaveTermsReq(List<TermInput> terms) {
        public record TermInput(Long id, String sourceTerm, String targetTerm,
                                String note, Integer enabled) {
        }
    }

    /** 批量移动/复制术语到另一系列项目。mode: move=移动（改归属）/ copy=复制（原项目保留）。 */
    public record TransferTermsReq(List<Long> termIds, Long targetProjectId, String mode) {
    }

    /** 批量删除术语。 */
    public record BatchDeleteTermsReq(List<Long> termIds) {
    }
}
