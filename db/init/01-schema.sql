-- AI 视频翻译系统 — 初始化表结构
-- 容器首次启动时由 MySQL 自动执行（/docker-entrypoint-initdb.d）

CREATE DATABASE IF NOT EXISTS aifanyi DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE aifanyi;

-- 用户
CREATE TABLE IF NOT EXISTS `user` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT,
  `username`   VARCHAR(64)  NOT NULL,
  `password`   VARCHAR(100) NOT NULL COMMENT 'BCrypt 哈希',
  `nickname`   VARCHAR(64)  DEFAULT NULL,
  `deleted`    TINYINT      NOT NULL DEFAULT 0,
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户';

-- 翻译任务
CREATE TABLE IF NOT EXISTS `translation_task` (
  `id`                BIGINT       NOT NULL AUTO_INCREMENT,
  `user_id`           BIGINT       NOT NULL,
  `mode`              VARCHAR(20)  NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL/KB',
  `project_id`        BIGINT       DEFAULT NULL COMMENT 'KB 模式所属系列项目',
  `status`            VARCHAR(30)  NOT NULL DEFAULT 'PENDING',
  `source_lang`       VARCHAR(20)  DEFAULT 'auto',
  `target_lang`       VARCHAR(20)  DEFAULT 'zh',
  `asr_provider`      VARCHAR(30)  DEFAULT 'groq',
  `llm_model`         VARCHAR(60)  DEFAULT NULL,
  `burn_subtitle`     TINYINT      NOT NULL DEFAULT 0,
  `bilingual`         TINYINT      NOT NULL DEFAULT 0,
  `original_filename` VARCHAR(255) DEFAULT NULL,
  `video_path`        VARCHAR(500) DEFAULT NULL,
  `audio_path`        VARCHAR(500) DEFAULT NULL,
  `srt_path`          VARCHAR(500) DEFAULT NULL,
  `output_video_path` VARCHAR(500) DEFAULT NULL,
  `progress`          INT          NOT NULL DEFAULT 0,
  `error_msg`         VARCHAR(1000) DEFAULT NULL,
  `deleted`           TINYINT      NOT NULL DEFAULT 0,
  `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user` (`user_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='翻译任务';

-- 字幕行
CREATE TABLE IF NOT EXISTS `subtitle` (
  `id`          BIGINT NOT NULL AUTO_INCREMENT,
  `task_id`     BIGINT NOT NULL,
  `seq`         INT    NOT NULL COMMENT '序号，从1开始',
  `start_ms`    BIGINT NOT NULL COMMENT '起始毫秒',
  `end_ms`      BIGINT NOT NULL COMMENT '结束毫秒',
  `source_text` TEXT,
  `target_text` TEXT,
  `created_at`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_task` (`task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='字幕行';

-- 用户设置 / 各平台 API 密钥（每用户一行）
CREATE TABLE IF NOT EXISTS `user_setting` (
  `user_id`           BIGINT NOT NULL,
  `groq_api_key`      VARCHAR(255) DEFAULT NULL,
  `llm_base_url`      VARCHAR(255) DEFAULT NULL,
  `llm_api_key`       VARCHAR(255) DEFAULT NULL,
  `llm_model`         VARCHAR(100) DEFAULT NULL,
  `dashscope_api_key` VARCHAR(255) DEFAULT NULL,
  `zhipu_api_key`     VARCHAR(255) DEFAULT NULL,
  `gemini_base_url`   VARCHAR(255) DEFAULT NULL,
  `gemini_api_key`    VARCHAR(255) DEFAULT NULL,
  `gemini_model`      VARCHAR(128) DEFAULT NULL,
  `updated_at`        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户设置/密钥';

-- KB 系列项目（一系列视频共用一张术语表）
CREATE TABLE IF NOT EXISTS `kb_project` (
  `id`          BIGINT      NOT NULL AUTO_INCREMENT,
  `user_id`     BIGINT      NOT NULL,
  `name`        VARCHAR(128) NOT NULL,
  `source_lang` VARCHAR(20)  DEFAULT 'auto',
  `target_lang` VARCHAR(20)  DEFAULT '中文',
  `deleted`     TINYINT     NOT NULL DEFAULT 0,
  `created_at`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='KB 系列项目';

-- 术语表条目（属于某个系列项目）
CREATE TABLE IF NOT EXISTS `glossary_term` (
  `id`          BIGINT      NOT NULL AUTO_INCREMENT,
  `project_id`  BIGINT      NOT NULL,
  `source_term` VARCHAR(255) NOT NULL COMMENT '原文术语',
  `target_term` VARCHAR(255) NOT NULL COMMENT '指定译法',
  `category`    VARCHAR(40)  DEFAULT NULL COMMENT '术语类别（人名/地名与地址/机构组织与品牌/作品名/文化专有项/科技专名）',
  `note`        VARCHAR(500) DEFAULT NULL COMMENT '说明/来源依据',
  `origin`      VARCHAR(10)  NOT NULL DEFAULT 'manual' COMMENT 'auto=模型抽取/manual=人工',
  `enabled`     TINYINT     NOT NULL DEFAULT 1,
  `deleted`     TINYINT     NOT NULL DEFAULT 0,
  `created_at`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_project` (`project_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='术语表条目';
