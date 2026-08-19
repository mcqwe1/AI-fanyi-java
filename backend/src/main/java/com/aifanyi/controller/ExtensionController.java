package com.aifanyi.controller;

import com.aifanyi.common.R;
import com.aifanyi.controller.dto.ExtDtos.ExtImageReq;
import com.aifanyi.controller.dto.ExtDtos.ExtImageResp;
import com.aifanyi.controller.dto.ExtDtos.ExtPingResp;
import com.aifanyi.controller.dto.ExtDtos.ExtTokenResp;
import com.aifanyi.controller.dto.ExtDtos.ExtTranslateReq;
import com.aifanyi.controller.dto.ExtDtos.ExtTranslateResp;
import com.aifanyi.controller.dto.TextTranslateDtos.HistoryDetail;
import com.aifanyi.controller.dto.TextTranslateDtos.HistoryItem;
import com.aifanyi.security.AuthUser;
import com.aifanyi.security.JwtUtil;
import com.aifanyi.security.SecurityUtils;
import com.aifanyi.service.ExtensionService;
import com.aifanyi.service.TextTranslateService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 狐译浏览器扩展（划词翻译/整页翻译）接口。
 * 走统一鉴权：网页版「划词翻译」页登录态下签发 90 天长效 token，
 * 扩展内容脚本从页面拿走保存，之后所有请求带 Bearer 走同一条 JWT 链路。
 */
@RestController
@RequestMapping("/api/ext")
@RequiredArgsConstructor
public class ExtensionController {

    /** 扩展 token 有效期：90 天。过期后打开网页版「划词翻译」页即自动续签。 */
    private static final long EXT_TOKEN_TTL_MILLIS = 90L * 24 * 60 * 60 * 1000;

    private final ExtensionService service;
    private final TextTranslateService textService;
    private final JwtUtil jwtUtil;

    /** 网页版划词翻译页调用：为浏览器扩展签发长效 token（同一用户、同一密钥链路）。 */
    @PostMapping("/token")
    public R<ExtTokenResp> token() {
        AuthUser u = SecurityUtils.current();
        String token = jwtUtil.generate(u.userId(), u.username(), EXT_TOKEN_TTL_MILLIS);
        return R.ok(new ExtTokenResp(token, System.currentTimeMillis() + EXT_TOKEN_TTL_MILLIS, u.username()));
    }

    /** 扩展连通性检测（弹窗状态灯用）。 */
    @GetMapping("/ping")
    public R<ExtPingResp> ping() {
        return R.ok(new ExtPingResp(SecurityUtils.current().username()));
    }

    /** 扩展批量翻译：划词一次一段，整页翻译分批多段；历史写进划词翻译自己的历史。 */
    @PostMapping("/translate")
    public R<ExtTranslateResp> translate(@RequestBody ExtTranslateReq req) {
        return R.ok(service.translate(SecurityUtils.currentUserId(), req));
    }

    /** 扩展图片翻译：右键网页图片 → OCR 提字后走文本翻译链路；历史写进划词翻译自己的历史。 */
    @PostMapping("/image")
    public R<ExtImageResp> image(@RequestBody ExtImageReq req) {
        return R.ok(service.translateImage(SecurityUtils.currentUserId(), req));
    }

    /**
     * 划词翻译历史（2026-08 需求改版）：与「文本 AI 翻译」历史彻底分离——
     * 这里只返回扩展渠道（划词/整页/图片/输入框）的记录，文本翻译页看不到它们，反之亦然。
     */
    @GetMapping("/history")
    public R<List<HistoryItem>> history() {
        return R.ok(textService.extHistory(SecurityUtils.currentUserId()));
    }

    @GetMapping("/history/{id}")
    public R<HistoryDetail> historyDetail(@PathVariable Long id) {
        return R.ok(textService.detail(SecurityUtils.currentUserId(), id, true));
    }

    @DeleteMapping("/history/{id}")
    public R<Void> removeHistory(@PathVariable Long id) {
        textService.remove(SecurityUtils.currentUserId(), id, true);
        return R.ok();
    }

    /** 清空当前用户的全部划词翻译历史。 */
    @DeleteMapping("/history")
    public R<Map<String, Integer>> clearHistory() {
        return R.ok(Map.of("count", textService.clearExtHistory(SecurityUtils.currentUserId())));
    }

    /** 下载扩展 zip（前端引导页用 access_token 查询参数带鉴权直链下载）。 */
    @GetMapping("/download")
    public void download(HttpServletResponse response) throws IOException {
        response.setContentType("application/zip");
        response.setHeader("Content-Disposition", "attachment; filename=\"huyi-extension.zip\"");
        service.writeExtensionZip(response.getOutputStream());
    }
}
