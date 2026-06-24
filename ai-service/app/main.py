"""
aifanyi AI 微服务（FastAPI）

阶段1：占位。后端在普通模式下直接调用 Groq 云 Whisper，本服务暂不参与。
阶段2：接入本地 faster-whisper（GPU）做 ASR，并用 WhisperX 做强制对齐兜底；
        以及 Gemini 视频理解（知识库模式）。
"""
from typing import Optional

from fastapi import FastAPI
from pydantic import BaseModel

app = FastAPI(title="aifanyi-ai-service", version="0.1.0")


@app.get("/health")
def health():
    return {"status": "ok"}


class TranscribeRequest(BaseModel):
    audio_path: str
    language: Optional[str] = None


@app.post("/transcribe")
def transcribe(req: TranscribeRequest):
    # TODO 阶段2：faster-whisper 加载 whisper-large-v3，返回带时间戳的 segments
    return {
        "implemented": False,
        "message": "本地 Whisper 将在阶段2接入；阶段1请用后端的 Groq provider",
    }
