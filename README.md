# AI 视频翻译系统

Spring Boot + Vue2 + FastAPI 的 AI 视频翻译平台。普通模式：上传视频 → 抽音频 → 语音转文字 → AI 翻译 → 输出 SRT / 烧录。知识库模式（阶段3）：AI 看视频建知识库后再翻译。

## 目录
- `backend/` — Spring Boot 3.5.15 编排层（鉴权 / 任务 / ASR / 翻译 / FFmpeg）
- `frontend/` — Vue2 + Element UI（登录 / 模式入口 / 上传 / 进度 / 下载）
- `ai-service/` — FastAPI（阶段2 接本地 Whisper / Gemini）
- `db/init/` — MySQL 建表脚本
- `docker-compose.yml` — MySQL8 + Redis

## 环境要求
JDK 21+（本机 25）、Node 20、Docker、FFmpeg。Maven 用项目自带 `mvnw`，国内镜像已配在 `~/.m2/settings.xml`。

## 快速启动
1. **密钥**：复制下方模板到 `.env`（已 gitignore），填入自己的 key：
   ```
   GROQ_API_KEY=gsk_xxx
   LLM_BASE_URL=https://api.deepseek.com/v1
   LLM_API_KEY=sk-xxx
   LLM_MODEL=deepseek-v4-flash
   FFMPEG_PATH=C:/.../ffmpeg.exe
   ```
2. **起数据库**：`docker compose up -d`
3. **起后端**（自动加载 .env）：
   ```
   powershell -ExecutionPolicy Bypass -File run-backend.ps1
   ```
4. **起前端**：`cd frontend && npm install && npm run dev`
5. 浏览器打开 **http://localhost:5173**，注册/登录后进入「普通模式」上传视频。

## 端口
后端 8080 · 前端 5173 · MySQL 3306（root/aifanyi123，库 aifanyi）· Redis 6379

## 状态
阶段0+1 完成并端到端验证通过（上传→转写→翻译→SRT，时间轴对齐）。
后续：阶段2（本地 faster-whisper 多 provider、字幕烧录、SSE 进度）→ 阶段3（知识库模式）。
