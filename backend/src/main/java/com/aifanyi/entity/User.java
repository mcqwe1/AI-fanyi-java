package com.aifanyi.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("user")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    /** BCrypt hashed password. */
    private String password;

    private String nickname;

    /** 头像（data URL 小图；null=用默认 logo） */
    private String avatar;

    /** USER / ADMIN. */
    private String role;

    /** 1 means the account is allowed to sign in; 0 means disabled. */
    private Integer enabled;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
