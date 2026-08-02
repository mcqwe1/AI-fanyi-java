package com.aifanyi.controller;

import com.aifanyi.common.BizException;
import com.aifanyi.common.R;
import com.aifanyi.controller.dto.TextTranslateDtos.FileTranslateResp;
import com.aifanyi.controller.dto.TextTranslateDtos.HistoryDetail;
import com.aifanyi.controller.dto.TextTranslateDtos.HistoryItem;
import com.aifanyi.controller.dto.TextTranslateDtos.TextTranslateReq;
import com.aifanyi.controller.dto.TextTranslateDtos.TextTranslateResp;
import com.aifanyi.security.SecurityUtils;
import com.aifanyi.service.TextTranslateService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
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

    /** 上传文本文件（txt/md/srt/vtt）翻译，返回结果 + 建议下载名/格式。 */
    @PostMapping("/file")
    public R<FileTranslateResp> translateFile(
            @RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = "中文") String targetLang,
            @RequestParam(required = false) String stylePrompt) {
        if (file == null || file.isEmpty()) {
            throw new BizException("文件为空");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BizException("读取上传文件失败: " + e.getMessage());
        }
        return R.ok(service.translateFile(SecurityUtils.currentUserId(), bytes,
                file.getOriginalFilename(), targetLang, stylePrompt));
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
