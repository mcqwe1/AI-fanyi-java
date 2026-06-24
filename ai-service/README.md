# aifanyi-ai-service

Python AI 微服务，负责本地模型相关的重计算。

## 阶段划分
- **阶段1**：仅 FastAPI 骨架（`/health`、`/transcribe` 占位）。普通模式的 ASR 由后端直接调用 Groq 云 Whisper，本服务暂不参与。
- **阶段2**：接入本地 `faster-whisper`（GPU）做 whisper-large-v3 转写；`WhisperX` 做强制对齐，给无时间戳的 ASR 结果补时间轴。
- **阶段3**：Gemini 原生视频理解（知识库模式）。

## ⚠️ Python 版本
本机默认 Python 3.14 过新，`torch` / `faster-whisper` / `whisperx` 等暂无对应 wheel。
阶段2 接入本地模型时，请用 **Python 3.11 或 3.12** 单独建 venv。

## 运行（阶段1骨架）
```bash
pip install -r requirements.txt
uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
```
