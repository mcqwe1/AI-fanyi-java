package com.aifanyi.controller;

import com.aifanyi.common.BizException;
import com.aifanyi.common.R;
import com.aifanyi.entity.StylePreset;
import com.aifanyi.mapper.StylePresetMapper;
import com.aifanyi.security.SecurityUtils;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 用户自定义翻译风格预设的增删改查（内置预设由前端常量提供，不入库）。 */
@RestController
@RequestMapping("/api/style-presets")
@RequiredArgsConstructor
public class StylePresetController {

    private static final int LABEL_MAX = 60;
    private static final int PROMPT_MAX = 500;

    private final StylePresetMapper mapper;

    public record PresetVO(Long id, String label, String prompt) {
    }

    public record PresetReq(String label, String prompt) {
    }

    @GetMapping
    public R<List<PresetVO>> list() {
        Long uid = SecurityUtils.currentUserId();
        List<PresetVO> out = mapper.selectList(Wrappers.<StylePreset>lambdaQuery()
                        .eq(StylePreset::getUserId, uid)
                        .orderByAsc(StylePreset::getId))
                .stream()
                .map(p -> new PresetVO(p.getId(), p.getLabel(), p.getPrompt()))
                .toList();
        return R.ok(out);
    }

    @PostMapping
    public R<PresetVO> create(@RequestBody PresetReq req) {
        Long uid = SecurityUtils.currentUserId();
        String label = normalize(req.label(), LABEL_MAX, "风格名称");
        String prompt = normalize(req.prompt(), PROMPT_MAX, "风格提示词");
        StylePreset p = new StylePreset();
        p.setUserId(uid);
        p.setLabel(label);
        p.setPrompt(prompt);
        mapper.insert(p);
        return R.ok(new PresetVO(p.getId(), p.getLabel(), p.getPrompt()));
    }

    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody PresetReq req) {
        Long uid = SecurityUtils.currentUserId();
        StylePreset p = owned(id, uid);
        p.setLabel(normalize(req.label(), LABEL_MAX, "风格名称"));
        p.setPrompt(normalize(req.prompt(), PROMPT_MAX, "风格提示词"));
        mapper.updateById(p);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        Long uid = SecurityUtils.currentUserId();
        owned(id, uid);
        mapper.deleteById(id);
        return R.ok();
    }

    private StylePreset owned(Long id, Long uid) {
        StylePreset p = mapper.selectById(id);
        if (p == null || !p.getUserId().equals(uid)) {
            throw new BizException(404, "风格预设不存在");
        }
        return p;
    }

    private static String normalize(String v, int max, String name) {
        if (!StringUtils.hasText(v)) {
            throw new BizException(name + "不能为空");
        }
        String s = v.trim();
        if (s.length() > max) {
            throw new BizException(name + "过长（上限 " + max + " 字）");
        }
        return s;
    }
}
