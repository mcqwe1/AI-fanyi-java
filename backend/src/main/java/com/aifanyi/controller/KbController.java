package com.aifanyi.controller;

import com.aifanyi.common.R;
import com.aifanyi.controller.dto.KbDtos.CreateProjectReq;
import com.aifanyi.controller.dto.KbDtos.ProjectVO;
import com.aifanyi.controller.dto.KbDtos.SaveTermsReq;
import com.aifanyi.controller.dto.KbDtos.TermVO;
import com.aifanyi.security.SecurityUtils;
import com.aifanyi.service.KbService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * KB 系列项目 + 术语表接口。
 */
@RestController
@RequestMapping("/api/kb")
@RequiredArgsConstructor
public class KbController {

    private final KbService kbService;

    @GetMapping("/projects")
    public R<List<ProjectVO>> projects() {
        return R.ok(kbService.listProjects(SecurityUtils.currentUserId()));
    }

    @PostMapping("/projects")
    public R<Map<String, Long>> createProject(@RequestBody CreateProjectReq req) {
        Long id = kbService.createProject(SecurityUtils.currentUserId(), req);
        return R.ok(Map.of("projectId", id));
    }

    @DeleteMapping("/projects/{id}")
    public R<Void> deleteProject(@PathVariable Long id) {
        kbService.deleteProject(SecurityUtils.currentUserId(), id);
        return R.ok();
    }

    @GetMapping("/projects/{id}/terms")
    public R<List<TermVO>> terms(@PathVariable Long id) {
        return R.ok(kbService.listTerms(SecurityUtils.currentUserId(), id));
    }

    @PutMapping("/projects/{id}/terms")
    public R<Void> saveTerms(@PathVariable Long id, @RequestBody SaveTermsReq req) {
        kbService.saveTerms(SecurityUtils.currentUserId(), id, req);
        return R.ok();
    }

    @DeleteMapping("/terms/{termId}")
    public R<Void> deleteTerm(@PathVariable Long termId) {
        kbService.deleteTerm(SecurityUtils.currentUserId(), termId);
        return R.ok();
    }
}
