package com.aifanyi.domain;

import lombok.Data;

/**
 * 字幕样式（用于烧录与预览）。颜色用 #RRGGBB。
 */
@Data
public class SubtitleStyle {

    /** 字体族名（libass 按名匹配系统字体） */
    private String fontName = "Microsoft YaHei";

    /** 字号（相对视频分辨率 PlayResY） */
    private int fontSize = 42;

    /** 字体颜色 #RRGGBB */
    private String primaryColor = "#FFFFFF";

    /** 描边/背景框颜色 #RRGGBB */
    private String outlineColor = "#000000";

    /** 描边宽度 */
    private double outline = 2.0;

    /** 特效：OUTLINE 描边 / SHADOW 阴影 / BOX 背景框 */
    private String effect = "OUTLINE";

    /** 位置：BOTTOM 底部 / TOP 顶部 / MIDDLE 居中 */
    private String position = "BOTTOM";

    /** 是否加粗 */
    private boolean bold = true;
}
