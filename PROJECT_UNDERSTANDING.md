# aifanyi 项目理解文档

> 我（Claude）维护的项目认知速查。每次开工前先读这份，避免重复踩坑。
> 最后更新：2026-06-27

---

## 1. 一句话定位
AI 视频翻译平台（`D:\aifanyi`），monorepo。上传视频 → 抽音频 → ASR 转写 → LLM 翻译 → 生成/烧录字幕。两种模式：①普通翻译（已完成）②知识库模式（Gemini 视频理解，阶段3未做）。

## 2. 组成与端口
| 模块 | 目录 | 技术 | 端口 | 启动 |
|------|------|------|------|------|
| 后端编排 | `backend/` | Spring Boot 3.5.15 / JDK25(target21) / MyBatis-Plus | 8080 | `powershell -File run-backend.ps1`（读 .env 起 Spring Boot） |
| 前端 | `frontend/` | Vue 2.7 + Vite + ElementUI | 5173（代理 /api→8080） | `npm run dev` |
| AI 微服务 | `ai-service/` | FastAPI + faster-whisper | **8001** | `cd ai-service && .venv/Scripts/python -m uvicorn app.main:app --port 8001` |
| MySQL | docker | mysql:8 root/aifanyi123 库 aifanyi | 3306 | `docker compose up -d` |
| Redis | docker | redis:7 | 6379 | 同上 |

demo 账号：`demo / demo123`（userId=1）。

## 3. 数据流（普通模式流水线 `TaskPipeline.runAsync`）
```
上传 → 存 source.mp4 → PENDING
 → FFmpeg 抽 audio.mp3            EXTRACTING_AUDIO
 → AsrProvider 转写（带词级时间戳） TRANSCRIBING
 → 静音幻觉过滤 + 时间轴修正
 → OpenAiTranslator 批量并发翻译   TRANSLATING
 → 落库 subtitle 表 + 生成 subtitle.srt
 → (可选) 烧录 ASS 字幕进视频       BURNING
 → DONE / 任一步异常 FAILED
```
状态枚举 `TaskStatus`：PENDING / EXTRACTING_AUDIO / TRANSCRIBING / ANALYZING_VIDEO / BUILDING_KB / TRANSLATING / BURNING / DONE / FAILED。

## 4. 后端关键结构（`com.aifanyi`）
- **controller**：`TaskController`(/api/tasks)、`AuthController`(/api/auth)、`SettingsController`(/api/settings)。DTO 在 `controller/dto/`。
- **service**：`TaskService`（创建/列表/`getOwned` 归属校验）、`TaskPipeline`（异步流水线）、`BurnService`（预览+烧录）、`AuthService`、`SettingsService`（用户设置优先 env 兜底）。
- **entity**：`TranslationTask`（任务主表，`@TableLogic deleted` 逻辑删除）、`Subtitle`（字幕，**无逻辑删除=物理删除**）、`User`（`@TableLogic`）、`UserSetting`（每用户密钥）。
- **mapper**：都是空的 `BaseMapper<T>`，用 MP 通用 CRUD，无自定义 SQL。
- **asr**：`AsrProvider` 接口 + `AsrProviderFactory`（按 name 路由）。实现：`GroqWhisperProvider`(name=groq)、`LocalWhisperProvider`(name=local，调 ai-service:8001)。`Segment(startMs,endMs,text)`。
- **llm**：`OpenAiTranslator`（OpenAI 兼容 /chat/completions，批量并发、关思维链、json_object）。
- **media**：`FfmpegService`、`SrtService`、`AssSubtitleWriter`、`SubtitleTimingFixer`。
- **storage**：`StorageService`/`LocalStorageService`，根目录 `aifanyi.storage.root`(=`D:/aifanyi/data`)，每任务目录 `data/tasks/{id}/`（source.mp4 / audio.mp3 / subtitle.srt / burned.mp4 / preview.*）。
- **security**：JWT。`SecurityUtils.currentUserId()` 取当前用户；`JwtAuthFilter` 解析 Bearer token。
- **common**：`R<T>{code,msg,data}`（code=0 成功）；`BizException(code,msg)`→`GlobalExceptionHandler`→`R.fail`。

### 鉴权 + 归属校验范式（写新接口照抄）
```java
Long uid = SecurityUtils.currentUserId();
TranslationTask task = taskService.getOwned(id, uid); // 不存在或非本人 → 404
```

### 逻辑删除机制
`application.yml`：`logic-delete-field: deleted / value 1 / not-delete 0`。`TranslationTask.deleted` 带 `@TableLogic`。调 `taskMapper.deleteById(id)` 自动变 `UPDATE ... SET deleted=1`，查询自动过滤 deleted=0。**但 `subtitle` 表无逻辑删除、磁盘文件无清理逻辑——删任务要手动清这两样。**

## 5. 前端关键结构（`frontend/src`）
- **路由** `router/index.js`：`/login`(免登)、`/`(Home 模式选择)、`/normal`(NormalMode)、`/kb`(占位)、`/settings`。全局守卫：无 token 跳 /login。
- **API 层** `api/http.js`：axios 实例 baseURL=`/api`、超时 10min。请求拦截自动加 `Authorization: Bearer`。响应拦截：`code===0` 返回 `r.data`；401 清 token 跳登录；blob 直通。**注意：业务数据在 `r.data`（已剥一层 R）。**
- **视图**：
  - `NormalMode.vue`：左卡片建任务，右卡片「我的任务」`el-table :data="tasks"`，2 秒轮询 `fetchTasks`。操作列：下载SRT / 字幕样式烧录 / 下载视频。
  - `Settings.vue`：三标签（API密钥 / 改密码 / 历史记录）。历史记录 `el-table :data="tasks"`，`loadHistory` 拉 `/tasks`。
  - `components/StyleDialog.vue`：字幕样式弹窗 + 实时预览 + 烧录。
- **UI 库**：ElementUI。确认框用 `this.$confirm(msg,title,{type:'warning'}).then(...).catch(...)`；提示 `this.$message.success/warning`。

## 6. TaskController 现有接口
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/tasks | 建任务（multipart：file/mode/sourceLang/targetLang/asrProvider/llmModel/burnSubtitle/bilingual） |
| GET | /api/tasks | 当前用户任务列表 |
| GET | /api/tasks/{id} | 任务详情 |
| GET | /api/tasks/{id}/subtitles | 字幕列表 |
| GET | /api/tasks/{id}/srt | 下载 SRT |
| POST | /api/tasks/{id}/style/preview | 字幕样式预览图(jpg) |
| POST | /api/tasks/{id}/burn | 烧录(异步) |
| GET | /api/tasks/{id}/video | 下载烧录视频 |
| DELETE | /api/tasks/{id} | 删除任务（清字幕表+磁盘目录+逻辑删任务行）✅2026-06-26 |

## 7. ASR provider 选择映射（`TaskPipeline.resolveAsr`）前端下拉值 → (provider, model, key)：
- `groq` / `groq-turbo` → GroqWhisperProvider（key 来自用户设置/env）
- `qwen` / `glm` → 未实现（前端 disabled）
- `local-<size>` → LocalWhisperProvider，model=size（base/small/medium/large-v3），调 ai-service:8001，**无需 key**

## 8. 已知坑（务必记住）1. **写 .ps1 要带 BOM**（中文 Win + PS5.1，否则中文乱码解析失败）。见全局记忆 ps1-encoding-bom。
2. ✅ **run-backend.ps1 .env 漏读（已根治 2026-06-27）**：根因=PS 5.1 按 GBK 读 UTF-8 无 BOM 的 `.env`，中文注释行以中文字符结尾时 GBK 吞掉换行、把下一行 env 并进注释而跳过（坑掉 `LLM_BASE_URL`/`FFMPEG_PATH`）。修复：`Get-Content $envFile -Encoding UTF8`。同 .ps1 BOM 一家的编码坑。
3. **ai-service 跑 8001**，不是 8000（8000 被本机 node 占）。
4. **faster-whisper GPU**：CTranslate2 只认 PATH，不认 `os.add_dll_directory`；main.py 启动时把 `nvidia/*/bin` 前置进 PATH 才能加载 cublas/cudnn。GPU=GTX1660S 6G，用 cuda+int8_float16。
5. **本机 Python 3.14 太新**，torch/ct2 无 wheel；ai-service 用独立 `ai-service/.venv`（Python 3.12）。
6. Git Bash 内联中文按 GBK 发出→后端 `Invalid UTF-8`；测 API 用 ASCII 或写文件。curl 上传文件用 `D:/...` 不用 `/d/...`。
7. 后端日志 `run-backend.ps1 *> log` 是 UTF-16，读要 `iconv -f UTF-16LE`。
8. 重启后端先杀 8080；docker/前后端进程跨会话会停。
9. **翻译性能/正确性（2026-06-26 优化）**：`OpenAiTranslator` 按 index 对齐回填（漏译只影响单行，不再整批退回原文）；批大小默认 **40**、并发 **8**（`LLM_BATCH_SIZE`/`LLM_CONCURRENCY` 可调）；重试只在网络异常/整批失败时触发。**翻译端点务必用 deepseek 直连**（api.deepseek.com，~1.7s/批）；曾用 `api.pioneer.ai` 中转→极慢且 `EOF reached while reading` 断流，是"翻译变慢/请求翻倍"的元凶。

## 9. 已完成 vs 待办
- ✅ 阶段0/1：JWT 登录、普通流水线、ASR 抽象、翻译优化、字幕烧录+样式+预览、设置页、静音幻觉修复。
- ✅ 本地 faster-whisper（base/small/medium/large-v3，GPU，VAD）端到端验证（2026-06-26）。
- ⏳ 待办：Qwen3-ASR / GLM-ASR provider 未实现；阶段3 知识库模式（Gemini）。
