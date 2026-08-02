-- AI 视频翻译系统 — H2 版建表脚本（便携版默认库,MODE=MySQL）
-- 由 spring.sql.init 每次启动执行,全程 IF NOT EXISTS 幂等:首启建库,升级时可安全加表。
-- 语法按 MySQL 方言书写,与 H2 的差异处理:
--   * KEY xxx (...) 拆成独立 CREATE INDEX(H2 索引名全库唯一,故加表名前缀)
--   * 去掉 ENGINE/CHARSET/COMMENT;MEDIUMTEXT→TEXT

-- 用户
CREATE TABLE IF NOT EXISTS `user` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT,
  `username`   VARCHAR(64)  NOT NULL,
  `password`   VARCHAR(100) NOT NULL,
  `nickname`   VARCHAR(64)  DEFAULT NULL,
  `deleted`    TINYINT      NOT NULL DEFAULT 0,
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  CONSTRAINT `uk_username` UNIQUE (`username`)
);

-- 翻译任务
CREATE TABLE IF NOT EXISTS `translation_task` (
  `id`                BIGINT       NOT NULL AUTO_INCREMENT,
  `user_id`           BIGINT       NOT NULL,
  `mode`              VARCHAR(20)  NOT NULL DEFAULT 'NORMAL',
  `project_id`        BIGINT       DEFAULT NULL,
  `status`            VARCHAR(30)  NOT NULL DEFAULT 'PENDING',
  `source_lang`       VARCHAR(20)  DEFAULT 'auto',
  `target_lang`       VARCHAR(20)  DEFAULT 'zh',
  `asr_provider`      VARCHAR(30)  DEFAULT 'groq',
  `llm_model`         VARCHAR(60)  DEFAULT NULL,
  `burn_subtitle`     TINYINT      NOT NULL DEFAULT 0,
  `bilingual`         TINYINT      NOT NULL DEFAULT 0,
  `media_type`        VARCHAR(10)  NOT NULL DEFAULT 'VIDEO',
  `original_filename` VARCHAR(255) DEFAULT NULL,
  `video_path`        VARCHAR(500) DEFAULT NULL,
  `audio_path`        VARCHAR(500) DEFAULT NULL,
  `srt_path`          VARCHAR(500) DEFAULT NULL,
  `output_video_path` VARCHAR(500) DEFAULT NULL,
  `tts_voice`         VARCHAR(60)  DEFAULT NULL,
  `tts_speed`         VARCHAR(10)  DEFAULT NULL,
  `tts_keep_original` TINYINT      NOT NULL DEFAULT 0,
  `dub_video_path`    VARCHAR(500) DEFAULT NULL,
  `dub_status`        VARCHAR(20)  DEFAULT NULL,
  `dub_progress`      INT          NOT NULL DEFAULT 0,
  `dub_error`         VARCHAR(1000) DEFAULT NULL,
  `dub_notice`        VARCHAR(500) DEFAULT NULL,
  `style_prompt`      VARCHAR(500) DEFAULT NULL,
  `progress`          INT          NOT NULL DEFAULT 0,
  `error_msg`         VARCHAR(1000) DEFAULT NULL,
  `deleted`           TINYINT      NOT NULL DEFAULT 0,
  `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
);
CREATE INDEX IF NOT EXISTS `idx_task_user`   ON `translation_task` (`user_id`);
CREATE INDEX IF NOT EXISTS `idx_task_status` ON `translation_task` (`status`);
-- 老库升级:2026-07 加的媒体类型列(音频翻译功能);新库建表已含,此句幂等兜底
ALTER TABLE `translation_task` ADD COLUMN IF NOT EXISTS `media_type` VARCHAR(10) NOT NULL DEFAULT 'VIDEO';
-- 老库升级:2026-07 加的 TTS 配音列;新库建表已含,此句幂等兜底
ALTER TABLE `translation_task` ADD COLUMN IF NOT EXISTS `tts_voice` VARCHAR(60) DEFAULT NULL;
ALTER TABLE `translation_task` ADD COLUMN IF NOT EXISTS `tts_speed` VARCHAR(10) DEFAULT NULL;
ALTER TABLE `translation_task` ADD COLUMN IF NOT EXISTS `tts_keep_original` TINYINT NOT NULL DEFAULT 0;
ALTER TABLE `translation_task` ADD COLUMN IF NOT EXISTS `dub_video_path` VARCHAR(500) DEFAULT NULL;
ALTER TABLE `translation_task` ADD COLUMN IF NOT EXISTS `dub_status` VARCHAR(20) DEFAULT NULL;
ALTER TABLE `translation_task` ADD COLUMN IF NOT EXISTS `dub_progress` INT NOT NULL DEFAULT 0;
ALTER TABLE `translation_task` ADD COLUMN IF NOT EXISTS `dub_error` VARCHAR(1000) DEFAULT NULL;
-- 配音成功但有需要用户知晓的情况（如某些行译文过长、变速后仍与下一行重叠）
ALTER TABLE `translation_task` ADD COLUMN IF NOT EXISTS `dub_notice` VARCHAR(500) DEFAULT NULL;

-- 字幕行
CREATE TABLE IF NOT EXISTS `subtitle` (
  `id`          BIGINT NOT NULL AUTO_INCREMENT,
  `task_id`     BIGINT NOT NULL,
  `seq`         INT    NOT NULL,
  `start_ms`    BIGINT NOT NULL,
  `end_ms`      BIGINT NOT NULL,
  `source_text` TEXT,
  `target_text` TEXT,
  `created_at`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
);
CREATE INDEX IF NOT EXISTS `idx_subtitle_task` ON `subtitle` (`task_id`);

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
  `tts_provider`      VARCHAR(20)  DEFAULT NULL,
  `tts_base_url`      VARCHAR(255) DEFAULT NULL,
  `tts_api_key`       VARCHAR(255) DEFAULT NULL,
  `tts_model`         VARCHAR(100) DEFAULT NULL,
  `style_prompt`      VARCHAR(500) DEFAULT NULL,
  `term_extract_prompt` TEXT       DEFAULT NULL,
  `updated_at`        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`user_id`)
);
-- 老库升级:自定义术语抽取提示词(空=用内置)
ALTER TABLE `user_setting` ADD COLUMN IF NOT EXISTS `term_extract_prompt` TEXT DEFAULT NULL;
-- 老库升级:2026-07 加的 TTS 引擎配置(引擎选择 + OpenAI 兼容 /audio/speech)
ALTER TABLE `user_setting` ADD COLUMN IF NOT EXISTS `tts_provider` VARCHAR(20) DEFAULT NULL;
ALTER TABLE `user_setting` ADD COLUMN IF NOT EXISTS `tts_base_url` VARCHAR(255) DEFAULT NULL;
ALTER TABLE `user_setting` ADD COLUMN IF NOT EXISTS `tts_api_key` VARCHAR(255) DEFAULT NULL;
ALTER TABLE `user_setting` ADD COLUMN IF NOT EXISTS `tts_model` VARCHAR(100) DEFAULT NULL;

-- 自定义翻译风格预设（每用户可存多个，内置预设由前端提供不入库）
CREATE TABLE IF NOT EXISTS `style_preset` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT,
  `user_id`    BIGINT       NOT NULL,
  `label`      VARCHAR(60)  NOT NULL,
  `prompt`     VARCHAR(500) NOT NULL,
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
);
CREATE INDEX IF NOT EXISTS `idx_style_user` ON `style_preset` (`user_id`);

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
  PRIMARY KEY (`id`)
);
CREATE INDEX IF NOT EXISTS `idx_kbproj_user` ON `kb_project` (`user_id`);

-- 术语表条目（属于某个系列项目）
CREATE TABLE IF NOT EXISTS `glossary_term` (
  `id`          BIGINT      NOT NULL AUTO_INCREMENT,
  `project_id`  BIGINT      NOT NULL,
  `source_term` VARCHAR(255) NOT NULL,
  `target_term` VARCHAR(255) NOT NULL,
  `category`    VARCHAR(40)  DEFAULT NULL,
  `note`        VARCHAR(500) DEFAULT NULL,
  `origin`      VARCHAR(10)  NOT NULL DEFAULT 'manual',
  `enabled`     TINYINT     NOT NULL DEFAULT 1,
  `deleted`     TINYINT     NOT NULL DEFAULT 0,
  `created_at`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
);
CREATE INDEX IF NOT EXISTS `idx_term_project` ON `glossary_term` (`project_id`);

-- 文本翻译历史（AI 文本翻译模式，与视频翻译任务分开）
CREATE TABLE IF NOT EXISTS `text_translation` (
  `id`                 BIGINT       NOT NULL AUTO_INCREMENT,
  `user_id`            BIGINT       NOT NULL,
  `target_lang`        VARCHAR(50)  NOT NULL DEFAULT '中文',
  `style_prompt`       VARCHAR(500) DEFAULT NULL,
  `preview`            VARCHAR(200) DEFAULT NULL,
  `source_text`        TEXT   NOT NULL,
  `plain_target`       TEXT,
  `pairs_json`         TEXT,
  `model`              VARCHAR(100) DEFAULT NULL,
  `elapsed_ms`         BIGINT       NOT NULL DEFAULT 0,
  `untranslated_lines` INT          NOT NULL DEFAULT 0,
  `deleted`            TINYINT      NOT NULL DEFAULT 0,
  `created_at`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
);
CREATE INDEX IF NOT EXISTS `idx_text_user` ON `text_translation` (`user_id`, `created_at`);

-- 开箱即用的演示账号 demo/demo123（BCrypt);已存在则跳过
INSERT INTO `user` (`username`, `password`, `nickname`)
SELECT 'demo', '$2a$10$8HTgsiGE8OFQA53QHPLWHOd6y9qpsuazM1ln/Vo/gASjP3l1qVpz2', '演示用户'
WHERE NOT EXISTS (SELECT 1 FROM `user` WHERE `username` = 'demo');

-- ═══════════════════════════════════════════════════════════════════
-- Agent 模式（全能 AI 翻译，2026-07-29）
-- ═══════════════════════════════════════════════════════════════════

-- 术语元数据（Agent 模式产出；KB 模式不写这些列，全部可空，互不影响）
ALTER TABLE `glossary_term` ADD COLUMN IF NOT EXISTS `source_norm`   VARCHAR(255) DEFAULT NULL;
ALTER TABLE `glossary_term` ADD COLUMN IF NOT EXISTS `confidence`    DECIMAL(4,3) DEFAULT NULL;
ALTER TABLE `glossary_term` ADD COLUMN IF NOT EXISTS `status`        VARCHAR(16)  DEFAULT NULL;
ALTER TABLE `glossary_term` ADD COLUMN IF NOT EXISTS `evidence`      VARCHAR(1000) DEFAULT NULL;
ALTER TABLE `glossary_term` ADD COLUMN IF NOT EXISTS `strategy`      VARCHAR(20)  DEFAULT NULL;
ALTER TABLE `glossary_term` ADD COLUMN IF NOT EXISTS `profile_code`  VARCHAR(40)  DEFAULT NULL;
-- 0=未向量化 1=已同步（异步向量化崩溃后可据此补偿，否则行永久不进向量库）
ALTER TABLE `glossary_term` ADD COLUMN IF NOT EXISTS `vector_status` TINYINT NOT NULL DEFAULT 0;
-- 最后一次提出/确认该词的任务：跨任务确认飞轮据此防「同一任务重试刷分」
ALTER TABLE `glossary_term` ADD COLUMN IF NOT EXISTS `last_task_id`  BIGINT DEFAULT NULL;
CREATE INDEX IF NOT EXISTS `idx_term_norm` ON `glossary_term` (`project_id`, `source_norm`);

-- 术语桶定位：Agent 为每个「用户×领域」自动建一个 kb_project 存术语，
-- 按 (user_id, domain_code) 查找而非按 name——用户改名不会产生重复桶并孤立术语
ALTER TABLE `kb_project` ADD COLUMN IF NOT EXISTS `domain_code`  VARCHAR(40) DEFAULT NULL;
ALTER TABLE `kb_project` ADD COLUMN IF NOT EXISTS `auto_created` TINYINT NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS `idx_kbproj_domain` ON `kb_project` (`user_id`, `domain_code`);

-- 任务上的 Agent 字段：场景推测结果缓存（重试不重跑）+ 降级标记
ALTER TABLE `translation_task` ADD COLUMN IF NOT EXISTS `agent_domain`   VARCHAR(200) DEFAULT NULL;
ALTER TABLE `translation_task` ADD COLUMN IF NOT EXISTS `agent_degraded` TINYINT NOT NULL DEFAULT 0;
ALTER TABLE `translation_task` ADD COLUMN IF NOT EXISTS `agent_phase`    VARCHAR(40)  DEFAULT NULL;

-- 主/子 Agent 分开配置（实验功能，高级用户可给主 Agent 配强模型、子 Agent 配便宜模型）
ALTER TABLE `user_setting` ADD COLUMN IF NOT EXISTS `agent_main_base_url` VARCHAR(255) DEFAULT NULL;
ALTER TABLE `user_setting` ADD COLUMN IF NOT EXISTS `agent_main_api_key`  VARCHAR(255) DEFAULT NULL;
ALTER TABLE `user_setting` ADD COLUMN IF NOT EXISTS `agent_main_model`    VARCHAR(100) DEFAULT NULL;
ALTER TABLE `user_setting` ADD COLUMN IF NOT EXISTS `agent_sub_base_url`  VARCHAR(255) DEFAULT NULL;
ALTER TABLE `user_setting` ADD COLUMN IF NOT EXISTS `agent_sub_api_key`   VARCHAR(255) DEFAULT NULL;
ALTER TABLE `user_setting` ADD COLUMN IF NOT EXISTS `agent_sub_model`     VARCHAR(100) DEFAULT NULL;
ALTER TABLE `user_setting` ADD COLUMN IF NOT EXISTS `search_provider`     VARCHAR(20)  DEFAULT NULL;
ALTER TABLE `user_setting` ADD COLUMN IF NOT EXISTS `search_base_url`     VARCHAR(255) DEFAULT NULL;
ALTER TABLE `user_setting` ADD COLUMN IF NOT EXISTS `search_api_key`      VARCHAR(255) DEFAULT NULL;
ALTER TABLE `user_setting` ADD COLUMN IF NOT EXISTS `vector_url`          VARCHAR(255) DEFAULT NULL;
-- LangSmith 云端观测（可选调试功能）：填了 Key 才上报，留空零开销
ALTER TABLE `user_setting` ADD COLUMN IF NOT EXISTS `langsmith_api_key`   VARCHAR(255) DEFAULT NULL;
ALTER TABLE `user_setting` ADD COLUMN IF NOT EXISTS `langsmith_project`   VARCHAR(100) DEFAULT NULL;

-- ⑥ 领域档案表：user_id=0 为系统内置档案，>0 为用户自定义
CREATE TABLE IF NOT EXISTS `agent_profile` (
  `id`                BIGINT       NOT NULL AUTO_INCREMENT,
  `user_id`           BIGINT       NOT NULL DEFAULT 0,
  `domain_code`       VARCHAR(40)  NOT NULL,
  `name`              VARCHAR(80)  NOT NULL,
  `judge_criteria`    TEXT,
  `conventions`       TEXT,
  `search_hints`      TEXT,
  `few_shot`          TEXT,
  `conflict_priority` VARCHAR(500) DEFAULT NULL,
  `source`            VARCHAR(10)  NOT NULL DEFAULT 'builtin',
  `version`           INT          NOT NULL DEFAULT 1,
  `enabled`           TINYINT      NOT NULL DEFAULT 1,
  `deleted`           TINYINT      NOT NULL DEFAULT 0,
  `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
);
CREATE INDEX IF NOT EXISTS `idx_profile_user` ON `agent_profile` (`user_id`, `domain_code`);

-- ④→⑥ 档案修正提议：基于真实搜索结果的惯例纠错/搜索词增删。永不自动应用，需人工确认
CREATE TABLE IF NOT EXISTS `agent_profile_proposal` (
  `id`           BIGINT       NOT NULL AUTO_INCREMENT,
  `profile_id`   BIGINT       NOT NULL,
  `task_id`      BIGINT       DEFAULT NULL,
  `user_id`      BIGINT       NOT NULL,
  `kind`         VARCHAR(20)  NOT NULL,
  `payload`      TEXT,
  `status`       VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
  `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
);
CREATE INDEX IF NOT EXISTS `idx_proposal_profile` ON `agent_profile_proposal` (`profile_id`, `status`);

-- ⑩ Trace：每次 LLM/工具调用落库，供运行详情面板与问题定位
CREATE TABLE IF NOT EXISTS `agent_trace` (
  `id`                BIGINT       NOT NULL AUTO_INCREMENT,
  `task_id`           BIGINT       NOT NULL,
  `node`              VARCHAR(20)  NOT NULL,
  `profile_code`      VARCHAR(40)  DEFAULT NULL,
  `seq`               INT          NOT NULL DEFAULT 0,
  `input_digest`      VARCHAR(500) DEFAULT NULL,
  `output_digest`     VARCHAR(2000) DEFAULT NULL,
  `elapsed_ms`        BIGINT       NOT NULL DEFAULT 0,
  `prompt_tokens`     INT          DEFAULT NULL,
  `completion_tokens` INT          DEFAULT NULL,
  `total_tokens`      INT          DEFAULT NULL,
  `stop_reason`       VARCHAR(30)  DEFAULT NULL,
  `degraded`          TINYINT      NOT NULL DEFAULT 0,
  `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
);
CREATE INDEX IF NOT EXISTS `idx_trace_task` ON `agent_trace` (`task_id`, `seq`);

-- 内置领域档案种子（user_id=0）。已存在则跳过，用户改不动内置项但可新建自己的
INSERT INTO `agent_profile` (`user_id`, `domain_code`, `name`, `judge_criteria`, `conventions`, `search_hints`, `conflict_priority`, `source`)
SELECT 0, 'general', '通用', '任何未能明确归类到其他领域的内容。这是兜底档案。',
       '专有名词优先采用目标语言already通用的官方译名；无官方译名时含义清楚的意译、含义不明的音译。',
       '官方译名;标准译法', '官方译名 > 通用译名 > 自拟', 'builtin'
WHERE NOT EXISTS (SELECT 1 FROM `agent_profile` WHERE `user_id` = 0 AND `domain_code` = 'general');

INSERT INTO `agent_profile` (`user_id`, `domain_code`, `name`, `judge_criteria`, `conventions`, `search_hints`, `conflict_priority`, `source`)
SELECT 0, 'it', 'IT / 软件技术', '出现编程语言、框架、协议、云服务、开发工具、算法、数据结构等计算机技术内容。',
       'API/SDK/HTTP 等业界通用缩写保留原文不译；产品名与公司名保留官方写法（如 Kubernetes、GitHub）；技术概念用中文社区既有译法（如 container→容器、deployment→部署）；版本号与命令行原样保留。',
       '官方文档 中文;技术术语 标准译法;社区通用译名', '官方中文文档 > 中文社区通用 > 音译', 'builtin'
WHERE NOT EXISTS (SELECT 1 FROM `agent_profile` WHERE `user_id` = 0 AND `domain_code` = 'it');

INSERT INTO `agent_profile` (`user_id`, `domain_code`, `name`, `judge_criteria`, `conventions`, `search_hints`, `conflict_priority`, `source`)
SELECT 0, 'medical', '医疗 / 生命科学', '出现疾病名、药物名、解剖结构、检查项目、治疗术式、医学机构等内容。',
       '疾病与药物必须使用国家药典/医学名词审定委员会的规范中文名（如 Alzheimer''s disease→阿尔茨海默病）；不得自创译名；药物区分通用名与商品名；基因/蛋白名保留国际标准符号。',
       '医学名词 规范译名;药典 通用名;疾病 标准中文名', '国家规范名 > 学界通用 > 音译', 'builtin'
WHERE NOT EXISTS (SELECT 1 FROM `agent_profile` WHERE `user_id` = 0 AND `domain_code` = 'medical');

INSERT INTO `agent_profile` (`user_id`, `domain_code`, `name`, `judge_criteria`, `conventions`, `search_hints`, `conflict_priority`, `source`)
SELECT 0, 'legal', '法律 / 合规', '出现法律条文、法院judgments、合同条款、监管机构、法律程序等内容。',
       '法律术语必须用对应法域的规范译名，不可望文生义（如 consideration 在合同法语境是「对价」而非「考虑」）；法院/机构名用官方中文名；法条编号原样保留；区分大陆法系与普通法系术语差异。',
       '法律术语 规范译名;机构 官方中文名;法条 标准译法', '官方译名 > 权威法律词典 > 直译', 'builtin'
WHERE NOT EXISTS (SELECT 1 FROM `agent_profile` WHERE `user_id` = 0 AND `domain_code` = 'legal');

INSERT INTO `agent_profile` (`user_id`, `domain_code`, `name`, `judge_criteria`, `conventions`, `search_hints`, `conflict_priority`, `source`)
SELECT 0, 'game', '游戏 / 动漫', '出现游戏标题、角色名、技能名、道具、副本、动漫作品、声优等内容。',
       '优先采用官方中文版译名（如有简中/繁中版）；无官方版时采用玩家社区最广泛使用的译名；角色名音译需符合中文取名习惯；技能/道具名可意译以传达效果；日文作品注意区分官译与民间译名。',
       '官方中文名;简中版 译名;玩家社区 通用译名', '官方简中 > 官方繁中 > 社区通用 > 音译', 'builtin'
WHERE NOT EXISTS (SELECT 1 FROM `agent_profile` WHERE `user_id` = 0 AND `domain_code` = 'game');
