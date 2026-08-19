package com.aifanyi.service;

import com.aifanyi.common.BizException;
import com.aifanyi.controller.dto.KbDtos.BatchDeleteTermsReq;
import com.aifanyi.controller.dto.KbDtos.CreateProjectReq;
import com.aifanyi.controller.dto.KbDtos.ProjectVO;
import com.aifanyi.controller.dto.KbDtos.SaveTermsReq;
import com.aifanyi.controller.dto.KbDtos.TermVO;
import com.aifanyi.controller.dto.KbDtos.TransferTermsReq;
import com.aifanyi.entity.GlossaryTerm;
import com.aifanyi.entity.KbProject;
import com.aifanyi.mapper.GlossaryTermMapper;
import com.aifanyi.mapper.KbProjectMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * KB 系列项目与术语表的读写。所有操作校验项目归属当前用户。
 */
@Service
@RequiredArgsConstructor
public class KbService {

    private final KbProjectMapper projectMapper;
    private final GlossaryTermMapper termMapper;
    private final com.aifanyi.agent.vector.VectorIndexService vectorIndex;

    public List<ProjectVO> listProjects(Long userId) {
        List<KbProject> ps = projectMapper.selectList(Wrappers.<KbProject>lambdaQuery()
                .eq(KbProject::getUserId, userId)
                .orderByDesc(KbProject::getId));
        return ps.stream().map(p -> new ProjectVO(
                p.getId(), p.getName(), p.getSourceLang(), p.getTargetLang(),
                countTerms(p.getId()), p.getCreatedAt())).toList();
    }

    public Long createProject(Long userId, CreateProjectReq req) {
        if (!StringUtils.hasText(req.name())) {
            throw new BizException("项目名不能为空");
        }
        KbProject p = new KbProject();
        p.setUserId(userId);
        p.setName(req.name().trim());
        p.setSourceLang(StringUtils.hasText(req.sourceLang()) ? req.sourceLang() : "auto");
        p.setTargetLang(StringUtils.hasText(req.targetLang()) ? req.targetLang() : "中文");
        projectMapper.insert(p);
        return p.getId();
    }

    public void deleteProject(Long userId, Long projectId) {
        getOwned(userId, projectId);
        termMapper.delete(Wrappers.<GlossaryTerm>lambdaQuery().eq(GlossaryTerm::getProjectId, projectId));
        projectMapper.deleteById(projectId);
    }

    public List<TermVO> listTerms(Long userId, Long projectId) {
        getOwned(userId, projectId);
        return termMapper.selectList(Wrappers.<GlossaryTerm>lambdaQuery()
                        .eq(GlossaryTerm::getProjectId, projectId)
                        .orderByDesc(GlossaryTerm::getId))
                .stream()
                .map(t -> new TermVO(t.getId(), t.getSourceTerm(), t.getTargetTerm(),
                        t.getNote(), t.getOrigin(), t.getEnabled(),
                        // 存量行可能是旧枚举名（VERIFIED/PENDING…），统一归一后再给前端
                        t.getStatus() == null ? null
                                : String.valueOf(com.aifanyi.agent.model.TermState.parse(t.getStatus()))))
                .toList();
    }

    public void saveTerms(Long userId, Long projectId, SaveTermsReq req) {
        getOwned(userId, projectId);
        if (req == null || req.terms() == null) return;
        for (SaveTermsReq.TermInput in : req.terms()) {
            if (!StringUtils.hasText(in.sourceTerm()) || !StringUtils.hasText(in.targetTerm())) {
                continue;
            }
            if (in.id() != null) {
                GlossaryTerm t = termMapper.selectById(in.id());
                if (t == null || !t.getProjectId().equals(projectId)) continue;
                t.setSourceTerm(in.sourceTerm().trim());
                t.setTargetTerm(in.targetTerm().trim());
                t.setNote(in.note());
                if (in.enabled() != null) t.setEnabled(in.enabled());
                // 数据飞轮：用户亲手改过的条目即最终结论，此后 Agent 一律不覆盖
                // （TermStateMachine 对任何已存在的条目都只增不改）。
                // 这让现有术语库 UI 零改动就成了 Agent 的人工校正入口。
                t.setOrigin("manual");
                t.setStatus(com.aifanyi.agent.model.TermState.ACTIVE.name());
                termMapper.updateById(t);
            } else {
                GlossaryTerm t = new GlossaryTerm();
                t.setProjectId(projectId);
                t.setSourceTerm(in.sourceTerm().trim());
                t.setTargetTerm(in.targetTerm().trim());
                t.setNote(in.note());
                t.setOrigin("manual");
                // 手工录入同样是用户意图，直接启用
                t.setStatus(com.aifanyi.agent.model.TermState.ACTIVE.name());
                t.setEnabled(in.enabled() == null ? 1 : in.enabled());
                termMapper.insert(t);
            }
        }
        // 术语变了，向量索引作废，下次用时重建
        vectorIndex.invalidate(projectId);
    }

    public void deleteTerm(Long userId, Long termId) {
        GlossaryTerm t = termMapper.selectById(termId);
        if (t == null) return;
        getOwned(userId, t.getProjectId()); // 校验该术语所属项目归当前用户
        termMapper.deleteById(termId);
        vectorIndex.invalidate(t.getProjectId());
    }

    /**
     * 批量移动/复制术语到另一系列项目。
     * 仅处理「所属项目归当前用户」的术语，其余静默跳过；返回实际处理条数。
     */
    public int transferTerms(Long userId, TransferTermsReq req) {
        if (req == null || req.termIds() == null || req.termIds().isEmpty()) {
            throw new BizException("请先选择术语");
        }
        boolean copy = "copy".equals(req.mode());
        if (!copy && !"move".equals(req.mode())) {
            throw new BizException("mode 仅支持 move / copy");
        }
        KbProject target = getOwned(userId, req.targetProjectId());

        List<GlossaryTerm> terms = ownedTerms(userId, req.termIds());
        int n = 0;
        for (GlossaryTerm t : terms) {
            if (t.getProjectId().equals(target.getId())) continue; // 已在目标项目，跳过
            if (copy) {
                GlossaryTerm c = new GlossaryTerm();
                c.setProjectId(target.getId());
                c.setSourceTerm(t.getSourceTerm());
                c.setTargetTerm(t.getTargetTerm());
                c.setNote(t.getNote());
                c.setOrigin(t.getOrigin());
                c.setEnabled(t.getEnabled());
                termMapper.insert(c);
            } else {
                t.setProjectId(target.getId());
                termMapper.updateById(t);
            }
            n++;
        }
        // 源桶与目标桶的索引都可能变了
        vectorIndex.invalidate(target.getId());
        terms.stream().map(GlossaryTerm::getProjectId).distinct()
                .forEach(vectorIndex::invalidate);
        return n;
    }

    /** 批量删除术语（仅删所属项目归当前用户的），返回实际删除条数。 */
    public int batchDeleteTerms(Long userId, BatchDeleteTermsReq req) {
        if (req == null || req.termIds() == null || req.termIds().isEmpty()) {
            throw new BizException("请先选择术语");
        }
        List<GlossaryTerm> terms = ownedTerms(userId, req.termIds());
        for (GlossaryTerm t : terms) {
            termMapper.deleteById(t.getId());
        }
        terms.stream().map(GlossaryTerm::getProjectId).distinct()
                .forEach(vectorIndex::invalidate);
        return terms.size();
    }

    /** 按 id 批量取术语，过滤出所属项目归当前用户的那些。 */
    private List<GlossaryTerm> ownedTerms(Long userId, List<Long> termIds) {
        List<GlossaryTerm> terms = termMapper.selectByIds(termIds);
        if (terms.isEmpty()) return terms;
        Set<Long> ownedProjectIds = projectMapper.selectList(Wrappers.<KbProject>lambdaQuery()
                        .eq(KbProject::getUserId, userId)
                        .in(KbProject::getId, terms.stream().map(GlossaryTerm::getProjectId).distinct().toList()))
                .stream().map(KbProject::getId).collect(Collectors.toSet());
        return terms.stream().filter(t -> ownedProjectIds.contains(t.getProjectId())).toList();
    }

    public KbProject getOwned(Long userId, Long projectId) {
        KbProject p = projectMapper.selectById(projectId);
        if (p == null || !p.getUserId().equals(userId)) {
            throw new BizException(404, "项目不存在");
        }
        return p;
    }

    /**
     * 载入用户勾选的若干术语桶为 原文→译法 注入映射（普通/KB/Agent 任务共用）。
     * <p>idsCsv 为 kb_project id 逗号串（前端多选），逐个校验归属，越权/非法 id 静默丢弃；
     * appendProjectId 追加在最后（KB 模式的专属桶 → 同名术语以它为准）。
     * 结果长词优先排序，与 OpenAiTranslator 的按序注入约定一致。
     */
    public java.util.Map<String, String> loadForInjection(Long userId, String idsCsv, Long appendProjectId) {
        List<Long> ids = new java.util.ArrayList<>();
        if (StringUtils.hasText(idsCsv)) {
            for (String p : idsCsv.split(",")) {
                try {
                    ids.add(Long.parseLong(p.trim()));
                } catch (NumberFormatException ignore) {
                    // 非法片段直接丢弃
                }
            }
        }
        if (appendProjectId != null) {
            ids.remove(appendProjectId);
            ids.add(appendProjectId);
        }
        if (ids.isEmpty()) {
            return java.util.Map.of();
        }
        Set<Long> owned = projectMapper.selectList(Wrappers.<KbProject>lambdaQuery()
                        .eq(KbProject::getUserId, userId)
                        .in(KbProject::getId, ids))
                .stream().map(KbProject::getId).collect(Collectors.toSet());

        List<GlossaryTerm> terms = new java.util.ArrayList<>();
        for (Long pid : ids) {
            if (!owned.contains(pid)) {
                continue;
            }
            terms.addAll(termMapper.selectList(Wrappers.<GlossaryTerm>lambdaQuery()
                    .eq(GlossaryTerm::getProjectId, pid)
                    .eq(GlossaryTerm::getEnabled, 1)));
        }
        // 长词优先（稳定排序保持桶序 → 同名词后面的桶覆盖前面的）
        terms.sort((a, b) -> Integer.compare(
                b.getSourceTerm() == null ? 0 : b.getSourceTerm().length(),
                a.getSourceTerm() == null ? 0 : a.getSourceTerm().length()));
        java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
        for (GlossaryTerm t : terms) {
            if (t.getSourceTerm() != null && t.getTargetTerm() != null) {
                map.put(t.getSourceTerm(), t.getTargetTerm());
            }
        }
        return map;
    }

    private int countTerms(Long projectId) {
        return Math.toIntExact(termMapper.selectCount(Wrappers.<GlossaryTerm>lambdaQuery()
                .eq(GlossaryTerm::getProjectId, projectId)));
    }
}
