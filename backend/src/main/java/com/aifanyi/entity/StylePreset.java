package com.aifanyi.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 用户自定义翻译风格预设（内置预设不入库，由前端常量提供）。 */
@Data
@TableName("style_preset")
public class StylePreset {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String label;
    private String prompt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
