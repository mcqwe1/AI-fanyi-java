package com.aifanyi.agent.profile;

import com.aifanyi.common.R;
import com.aifanyi.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 用户自建领域专家（⑥ 档案）的管理接口。
 * <p>内置专家（userId=0）只读展示；用户自建的可增改删。
 * 新建的专家自动加入场景匹配池（{@link ProfileService#pool}），无需额外「启用到任务」步骤。
 */
@RestController
@RequestMapping("/api/agent/profiles")
@RequiredArgsConstructor
public class AgentProfileController {

    private final ProfileService profiles;

    /** 一个专家（管理面板视图）。 */
    public record ProfileVO(Long id, String domainCode, String name, String judgeCriteria,
                            String conventions, String searchHints, String source,
                            boolean builtin, Integer enabled) {
        static ProfileVO of(AgentProfile p) {
            return new ProfileVO(p.getId(), p.getDomainCode(), p.getName(), p.getJudgeCriteria(),
                    p.getConventions(), p.getSearchHints(), p.getSource(),
                    p.getUserId() != null && p.getUserId() == AgentProfile.SYSTEM_USER,
                    p.getEnabled());
        }
    }

    /** 新建/修改入参（表单式：四个人话字段，domainCode 由后端生成不可改）。 */
    public record SaveReq(String name, String judgeCriteria, String conventions,
                          String searchHints, Integer enabled) {
    }

    @GetMapping
    public R<List<ProfileVO>> list() {
        return R.ok(profiles.listForManage(SecurityUtils.currentUserId())
                .stream().map(ProfileVO::of).toList());
    }

    @PostMapping
    public R<ProfileVO> create(@RequestBody SaveReq req) {
        AgentProfile p = profiles.create(SecurityUtils.currentUserId(),
                req.name(), req.judgeCriteria(), req.conventions(), req.searchHints());
        return R.ok(ProfileVO.of(p));
    }

    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody SaveReq req) {
        profiles.update(SecurityUtils.currentUserId(), id,
                req.name(), req.judgeCriteria(), req.conventions(), req.searchHints(), req.enabled());
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        profiles.delete(SecurityUtils.currentUserId(), id);
        return R.ok();
    }
}
