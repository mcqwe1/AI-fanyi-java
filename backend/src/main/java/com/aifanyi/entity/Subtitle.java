package com.aifanyi.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("subtitle")
public class Subtitle {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long taskId;

    /** 序号，从 1 开始 */
    private Integer seq;

    private Long startMs;
    private Long endMs;

    private String sourceText;
    private String targetText;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
