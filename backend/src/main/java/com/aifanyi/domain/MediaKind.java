package com.aifanyi.domain;

import java.util.Locale;
import java.util.Set;

/** 上传媒体类型：按扩展名识别（① 接入路由，纯代码零 token）。
 *  音频任务全流程与视频一致，仅不可烧录/预览字幕样式；文本任务无时间轴、不进 ffmpeg。 */
public enum MediaKind {
    VIDEO, AUDIO, TEXT;

    private static final Set<String> VIDEO_EXTS = Set.of(
            "mp4", "mkv", "mov", "avi", "webm", "flv", "ts", "m4v", "wmv", "mpg", "mpeg", "3gp");
    private static final Set<String> AUDIO_EXTS = Set.of(
            "mp3", "wav", "m4a", "aac", "flac", "ogg", "opus", "wma", "amr", "aiff");
    private static final Set<String> TEXT_EXTS = Set.of(
            "txt", "md", "markdown", "srt", "vtt");

    /** 按文件扩展名判断；识别不了返回 null（由调用方决定拒绝）。 */
    public static MediaKind fromFilename(String filename) {
        if (filename == null) {
            return null;
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return null;
        }
        String ext = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (VIDEO_EXTS.contains(ext)) {
            return VIDEO;
        }
        if (AUDIO_EXTS.contains(ext)) {
            return AUDIO;
        }
        if (TEXT_EXTS.contains(ext)) {
            return TEXT;
        }
        return null;
    }
}
