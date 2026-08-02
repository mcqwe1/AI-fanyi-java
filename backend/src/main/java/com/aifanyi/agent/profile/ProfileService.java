package com.aifanyi.agent.profile;

import com.aifanyi.mapper.AgentProfileMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * ⑥ 领域档案的读写（harness 状态与记忆层）。
 * <p>档案池 = 系统内置（user_id=0）+ 该用户自定义，用户档案同 code 时覆盖内置。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileService {

    private final AgentProfileMapper mapper;

    /** 该用户可用的全部启用档案（用户自定义优先于同名内置）。 */
    public List<AgentProfile> pool(Long userId) {
        List<AgentProfile> builtin = mapper.selectList(Wrappers.<AgentProfile>lambdaQuery()
                .eq(AgentProfile::getUserId, AgentProfile.SYSTEM_USER)
                .eq(AgentProfile::getEnabled, 1)
                .orderByAsc(AgentProfile::getId));
        List<AgentProfile> mine = mapper.selectList(Wrappers.<AgentProfile>lambdaQuery()
                .eq(AgentProfile::getUserId, userId)
                .eq(AgentProfile::getEnabled, 1)
                .orderByAsc(AgentProfile::getId));

        java.util.LinkedHashMap<String, AgentProfile> merged = new java.util.LinkedHashMap<>();
        for (AgentProfile p : builtin) {
            merged.put(p.getDomainCode(), p);
        }
        for (AgentProfile p : mine) {
            merged.put(p.getDomainCode(), p);       // 同 code 覆盖内置
        }
        return new ArrayList<>(merged.values());
    }

    /** 按 code 取档案（先查用户自定义再回落内置）；找不到返回 null。 */
    public AgentProfile byCode(Long userId, String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        AgentProfile mine = mapper.selectOne(Wrappers.<AgentProfile>lambdaQuery()
                .eq(AgentProfile::getUserId, userId)
                .eq(AgentProfile::getDomainCode, code)
                .eq(AgentProfile::getEnabled, 1)
                .last("limit 1"));
        if (mine != null) {
            return mine;
        }
        return mapper.selectOne(Wrappers.<AgentProfile>lambdaQuery()
                .eq(AgentProfile::getUserId, AgentProfile.SYSTEM_USER)
                .eq(AgentProfile::getDomainCode, code)
                .last("limit 1"));
    }

    /**
     * 兜底档案（general）。场景推测失败、未命中任何领域时用它——
     * 保证「③ 场景推测 LLM 超时/输出异常 → 用 domain=auto 默认档案继续」这条降级路径永远有路可走。
     */
    public AgentProfile fallback(Long userId) {
        AgentProfile p = byCode(userId, AgentProfile.GENERAL);
        if (p != null) {
            return p;
        }
        // 种子数据缺失时兜最后一层，绝不让 Agent 因为查不到档案而崩
        AgentProfile tmp = new AgentProfile();
        tmp.setUserId(AgentProfile.SYSTEM_USER);
        tmp.setDomainCode(AgentProfile.GENERAL);
        tmp.setName("通用");
        tmp.setConventions("专有名词优先采用目标语言已通用的官方译名；无官方译名时含义清楚的意译、含义不明的音译。");
        tmp.setSource("builtin");
        log.warn("内置 general 档案缺失，使用内存兜底档案");
        return tmp;
    }

    /** ③ 未命中长尾时把 LLM 生成的档案草稿入库（source=draft，待人工精化）。 */
    public AgentProfile saveDraft(Long userId, String code, String name, String criteria,
                                  String conventions, String searchHints) {
        if (code == null || code.isBlank()) {
            return null;
        }
        AgentProfile exist = byCode(userId, code);
        if (exist != null) {
            return exist;                            // 已有同 code，不重复建
        }
        AgentProfile p = new AgentProfile();
        p.setUserId(userId);
        p.setDomainCode(code.trim());
        p.setName(name == null || name.isBlank() ? code : name.trim());
        p.setJudgeCriteria(criteria);
        p.setConventions(conventions);
        p.setSearchHints(searchHints);
        p.setSource("draft");
        p.setVersion(1);
        p.setEnabled(1);
        try {
            mapper.insert(p);
            log.info("新增领域档案草稿：{}（{}）", p.getDomainCode(), p.getName());
            return p;
        } catch (Exception e) {
            log.warn("档案草稿入库失败（忽略，本次仍可用）: {}", e.toString());
            return p;
        }
    }

    // ─────────────── 用户自建专家（前端「我的专家」面板）───────────────

    /** 设置页/专家面板的列表：内置 + 我的（含停用的——管理界面要能重新启用）。 */
    public List<AgentProfile> listForManage(Long userId) {
        List<AgentProfile> out = new ArrayList<>(mapper.selectList(
                Wrappers.<AgentProfile>lambdaQuery()
                        .eq(AgentProfile::getUserId, AgentProfile.SYSTEM_USER)
                        .orderByAsc(AgentProfile::getId)));
        out.addAll(mapper.selectList(Wrappers.<AgentProfile>lambdaQuery()
                .eq(AgentProfile::getUserId, userId)
                .orderByAsc(AgentProfile::getId)));
        return out;
    }

    /**
     * 创建自定义专家。domainCode 自动生成（u{userId}x{序号}）——
     * code 是术语桶 (userId, domainCode) 的定位键，创建后不可改，
     * 让用户手填反而会踩「改名孤立术语」的坑，索性不暴露。
     */
    public AgentProfile create(Long userId, String name, String judgeCriteria,
                               String conventions, String searchHints) {
        if (name == null || name.isBlank()) {
            throw new com.aifanyi.common.BizException("专家名称不能为空");
        }
        long mine = mapper.selectCount(Wrappers.<AgentProfile>lambdaQuery()
                .eq(AgentProfile::getUserId, userId));
        if (mine >= 20) {
            throw new com.aifanyi.common.BizException("自定义专家最多 20 个（多了会稀释场景匹配的准确率）");
        }
        AgentProfile p = new AgentProfile();
        p.setUserId(userId);
        p.setDomainCode("u" + userId + "x" + (mine + 1) + "-" + Long.toString(
                System.nanoTime() % 46656, 36));   // 序号 + 短随机，避免删了重建撞 code
        p.setName(name.trim());
        p.setJudgeCriteria(trimTo(judgeCriteria, 500));
        p.setConventions(trimTo(conventions, 2000));
        p.setSearchHints(trimTo(searchHints, 500));
        p.setSource("user");
        p.setVersion(1);
        p.setEnabled(1);
        mapper.insert(p);
        log.info("用户 {} 新建专家：{}（{}）", userId, p.getName(), p.getDomainCode());
        return p;
    }

    /** 更新自己的专家（内置档案不可改，enabled 也在这里切）。 */
    public void update(Long userId, Long id, String name, String judgeCriteria,
                       String conventions, String searchHints, Integer enabled) {
        AgentProfile p = owned(userId, id);
        if (name != null && !name.isBlank()) {
            p.setName(name.trim());
        }
        if (judgeCriteria != null) {
            p.setJudgeCriteria(trimTo(judgeCriteria, 500));
        }
        if (conventions != null) {
            p.setConventions(trimTo(conventions, 2000));
        }
        if (searchHints != null) {
            p.setSearchHints(trimTo(searchHints, 500));
        }
        if (enabled != null) {
            p.setEnabled(enabled == 1 ? 1 : 0);
        }
        p.setVersion(p.getVersion() == null ? 1 : p.getVersion() + 1);
        mapper.updateById(p);
    }

    /** 删除自己的专家（逻辑删）。已产出的术语桶与术语不动。 */
    public void delete(Long userId, Long id) {
        AgentProfile p = owned(userId, id);
        mapper.deleteById(p.getId());
        log.info("用户 {} 删除专家：{}（{}）", userId, p.getName(), p.getDomainCode());
    }

    private AgentProfile owned(Long userId, Long id) {
        AgentProfile p = mapper.selectById(id);
        if (p == null || !userId.equals(p.getUserId())) {
            // 内置档案 userId=0，天然通不过归属校验 → 不可改不可删
            throw new com.aifanyi.common.BizException(404, "专家不存在或不属于你（内置专家不可修改）");
        }
        return p;
    }

    private static String trimTo(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
