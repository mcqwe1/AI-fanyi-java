package com.aifanyi.service;

import com.aifanyi.common.BizException;
import com.aifanyi.controller.dto.KbDtos.CreateProjectReq;
import com.aifanyi.controller.dto.KbDtos.ProjectVO;
import com.aifanyi.controller.dto.KbDtos.SaveTermsReq;
import com.aifanyi.controller.dto.KbDtos.TermVO;
import com.aifanyi.entity.GlossaryTerm;
import com.aifanyi.entity.KbProject;
import com.aifanyi.mapper.GlossaryTermMapper;
import com.aifanyi.mapper.KbProjectMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * KB 系列项目与术语表的读写。所有操作校验项目归属当前用户。
 */
@Service
@RequiredArgsConstructor
public class KbService {

    private final KbProjectMapper projectMapper;
    private final GlossaryTermMapper termMapper;

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
                        t.getCategory(), t.getNote(), t.getOrigin(), t.getEnabled()))
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
                t.setCategory(in.category());
                t.setNote(in.note());
                if (in.enabled() != null) t.setEnabled(in.enabled());
                termMapper.updateById(t);
            } else {
                GlossaryTerm t = new GlossaryTerm();
                t.setProjectId(projectId);
                t.setSourceTerm(in.sourceTerm().trim());
                t.setTargetTerm(in.targetTerm().trim());
                t.setCategory(in.category());
                t.setNote(in.note());
                t.setOrigin("manual");
                t.setEnabled(in.enabled() == null ? 1 : in.enabled());
                termMapper.insert(t);
            }
        }
    }

    public void deleteTerm(Long userId, Long termId) {
        GlossaryTerm t = termMapper.selectById(termId);
        if (t == null) return;
        getOwned(userId, t.getProjectId()); // 校验该术语所属项目归当前用户
        termMapper.deleteById(termId);
    }

    public KbProject getOwned(Long userId, Long projectId) {
        KbProject p = projectMapper.selectById(projectId);
        if (p == null || !p.getUserId().equals(userId)) {
            throw new BizException(404, "项目不存在");
        }
        return p;
    }

    private int countTerms(Long projectId) {
        return Math.toIntExact(termMapper.selectCount(Wrappers.<GlossaryTerm>lambdaQuery()
                .eq(GlossaryTerm::getProjectId, projectId)));
    }
}
