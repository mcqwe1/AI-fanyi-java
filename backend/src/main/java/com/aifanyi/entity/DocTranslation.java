package com.aifanyi.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/** 文档翻译任务（文档翻译模式，异步；与视频翻译任务/文本翻译历史分开）。 */
@Data
@TableName("doc_translation")
public class DocTranslation {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 上传的原始文件名 */
    private String filename;

    /** 输入格式（扩展名小写：pdf/epub/html/txt/json/docx/md/srt/vtt/ass） */
    private String format;

    /** PDF 处理档位：layout=保版式(BabelDOC) / fast=内置引擎；null=layout。非 PDF 忽略 */
    private String pdfMode;

    /** 输出格式（多数与输入一致；pdf → docx） */
    private String outputFormat;

    /** 内容预览（前几段拼接截断，工作台卡片按格式渲染用），翻译完成时写入 */
    private String preview;

    private String targetLang;

    private String stylePrompt;

    /** PENDING / RUNNING / SUCCESS / FAILED */
    private String status;

    /** 0~100 */
    private Integer progress;

    private Integer totalSegments;

    private Integer doneSegments;

    private String errorMsg;

    /** 源文件绝对路径 */
    private String sourcePath;

    /** 译文文件绝对路径 */
    private String outputPath;

    /** 译文下载文件名 */
    private String outputName;

    private String model;

    private Long elapsedMs;

    /** 疑似未翻译（译文=原文）的段数 */
    private Integer untranslatedSegments;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
