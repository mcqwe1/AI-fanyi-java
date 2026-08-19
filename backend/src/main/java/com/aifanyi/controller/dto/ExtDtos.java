package com.aifanyi.controller.dto;

import java.util.List;

/** 狐译浏览器扩展（划词翻译/整页翻译）专用 DTO。 */
public class ExtDtos {

    /**
     * 扩展批量翻译请求：texts 逐条对应返回。
     * kind 标记来源（selection=划词 page=整页 input=输入框 hover=悬停），用于写划词翻译历史：
     * selection/page/input 各写一条聚合记录，hover 太碎不写；null（旧版扩展）不写。
     */
    public record ExtTranslateReq(List<String> texts, String targetLang, String kind) {
    }

    /** translations 与请求 texts 等长同序（失败块降级返回原文，与视频链路同哲学）。 */
    public record ExtTranslateResp(List<String> translations, String model, long elapsedMs) {
    }

    /** 图片翻译请求：imageBase64 为不带 data: 前缀的纯 base64（mime 字段兼容保留，OCR 路径不需要）。 */
    public record ExtImageReq(String imageBase64, String mime, String targetLang) {
    }

    /**
     * 图片翻译的一行：OCR 原文 + 译文 + 该行在原图上的 [x,y,w,h] 包围盒（像素）。
     * box 可能为 null（旧版 ai-service 或坐标解析失败），扩展端此时退回浮层展示。
     */
    public record ExtImageLine(String text, String translation, List<Double> box) {
    }

    /**
     * 图片翻译结果：lines 为行级明细（扩展据此把译文绘回图片原位）；
     * translation/ocrText 为换行连接的整块文本（兼容旧版扩展的浮层展示）。
     * 图中没有文字时 translation 为提示语、ocrText 为空串、lines 为空列表。
     */
    public record ExtImageResp(String translation, String ocrText, List<ExtImageLine> lines,
                               String model, long elapsedMs) {
    }

    /** 长效扩展 token（浏览器扩展保存在 chrome.storage，过期后重新打开网页版即可续签）。 */
    public record ExtTokenResp(String token, long expiresAt, String username) {
    }

    /** 扩展连通性检测：返回当前登录用户名供弹窗展示。 */
    public record ExtPingResp(String username) {
    }
}
