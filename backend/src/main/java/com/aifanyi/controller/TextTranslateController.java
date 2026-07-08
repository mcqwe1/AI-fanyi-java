package com.aifanyi.controller;

import com.aifanyi.common.R;
import com.aifanyi.controller.dto.TextTranslateDtos.HistoryDetail;
import com.aifanyi.controller.dto.TextTranslateDtos.HistoryItem;
import com.aifanyi.controller.dto.TextTranslateDtos.TextTranslateReq;
import com.aifanyi.controller.dto.TextTranslateDtos.TextTranslateResp;
import com.aifanyi.security.SecurityUtils;
import com.aifanyi.service.TextTranslateService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** AI 文本翻译模式：同步翻译 + 独立历史（与视频翻译任务分开）。 */
@RestController
@RequestMapping("/api/translate")
@RequiredArgsConstructor
public class TextTranslateController {

    private final TextTranslateService service;

    /** 同步翻译并保存历史；长文本可能耗时数分钟，前端页面内等待。 */
    @PostMapping("/text")
    public R<TextTranslateResp> translate(@RequestBody TextTranslateReq req) {
        return R.ok(service.translate(SecurityUtils.currentUserId(), req));
    }

    @GetMapping("/history")
    public R<List<HistoryItem>> history() {
        return R.ok(service.history(SecurityUtils.currentUserId()));
    }

    @GetMapping("/history/{id}")
    public R<HistoryDetail> detail(@PathVariable Long id) {
        return R.ok(service.detail(SecurityUtils.currentUserId(), id));
    }

    @DeleteMapping("/history/{id}")
    public R<Void> remove(@PathVariable Long id) {
        service.remove(SecurityUtils.currentUserId(), id);
        return R.ok();
    }
}
