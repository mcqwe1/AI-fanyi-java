"""
aifanyi AI 微服务（FastAPI）

阶段2：本地 faster-whisper（GPU 优先，自动回退 CPU）做 ASR。
- 按模型尺寸（base/small/medium/large-v3）懒加载并缓存（同机 6GB 显存，一次只驻留一个）
- word 级时间戳 + VAD 过滤，从源头消除静音幻觉，并把每条字幕收紧到“首词出声 ~ 末词结束”
- 后端 LocalWhisperProvider 通过 /transcribe 调用本服务（同机直接传 audio_path，免上传）

阶段3：Gemini 原生视频理解（知识库模式）。
"""
import os
import threading
from typing import List, Optional

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel


def _register_cuda_dll_dirs():
    """Windows 下把 pip 装的 nvidia cuBLAS/cuDNN 目录加入 DLL 搜索路径，
    否则 CTranslate2 找不到 cublas64_12.dll / cudnn64_9.dll 而无法用 GPU。
    CTranslate2 的原生加载器只认 PATH，add_dll_directory 对它不生效，故两者都加。"""
    if os.name != "nt":
        return
    try:
        import importlib.util
        spec = importlib.util.find_spec("nvidia")
        if not spec or not spec.submodule_search_locations:
            return
        nvidia_root = list(spec.submodule_search_locations)[0]
        bin_dirs = []
        for sub in os.listdir(nvidia_root):
            bin_dir = os.path.join(nvidia_root, sub, "bin")
            if os.path.isdir(bin_dir):
                bin_dirs.append(bin_dir)
                if hasattr(os, "add_dll_directory"):
                    os.add_dll_directory(bin_dir)
        if bin_dirs:
            os.environ["PATH"] = os.pathsep.join(bin_dirs) + os.pathsep + os.environ.get("PATH", "")
    except Exception:
        pass


_register_cuda_dll_dirs()

app = FastAPI(title="aifanyi-ai-service", version="0.2.0")

# ---- 配置（环境变量可覆盖）----
# device: auto | cuda | cpu；compute_type 留空时按 device 自动选
DEVICE = os.getenv("WHISPER_DEVICE", "auto").lower()
COMPUTE_TYPE = os.getenv("WHISPER_COMPUTE_TYPE", "").strip()
# 模型下载缓存目录（默认 HuggingFace 缓存；设置后可固定到项目内）
MODEL_DIR = os.getenv("WHISPER_MODEL_DIR", "").strip() or None

# faster-whisper 尺寸名 → CTranslate2 模型名
SIZE_ALIASES = {
    "base": "base",
    "small": "small",
    "medium": "medium",
    "large": "large-v3",
    "large-v3": "large-v3",
}

_models = {}            # size -> WhisperModel
_resolved = {}          # 实际生效的 device/compute_type（首次加载后填充）
_lock = threading.Lock()


def _pick_device_and_compute():
    """决定 device 与 compute_type。GTX 16 系纯 fp16 有坑，CUDA 默认 int8_float16。"""
    want = DEVICE
    if want == "auto":
        try:
            import ctranslate2
            want = "cuda" if ctranslate2.get_cuda_device_count() > 0 else "cpu"
        except Exception:
            want = "cpu"
    if want == "cuda":
        compute = COMPUTE_TYPE or "int8_float16"
    else:
        want = "cpu"
        compute = COMPUTE_TYPE or "int8"
    return want, compute


def _load(size: str):
    """按尺寸加载模型并缓存；GPU 加载失败自动回退 CPU。"""
    norm = SIZE_ALIASES.get(size.lower())
    if not norm:
        raise HTTPException(status_code=400,
                            detail=f"不支持的模型尺寸: {size}，可用: {list(SIZE_ALIASES)}")
    with _lock:
        if norm in _models:
            return _models[norm]

        from faster_whisper import WhisperModel
        device, compute = _pick_device_and_compute()

        # 显存有限：换尺寸时只保留当前一个模型
        _models.clear()

        try:
            model = WhisperModel(norm, device=device, compute_type=compute,
                                 download_root=MODEL_DIR)
        except Exception as e:
            if device == "cuda":
                # GPU 不可用（缺 cuDNN/cuBLAS 或显存不足）→ 回退 CPU
                device, compute = "cpu", COMPUTE_TYPE or "int8"
                model = WhisperModel(norm, device=device, compute_type=compute,
                                     download_root=MODEL_DIR)
            else:
                raise HTTPException(status_code=500, detail=f"加载模型失败: {e}")

        _resolved["device"] = device
        _resolved["compute_type"] = compute
        _models[norm] = model
        return model


@app.get("/health")
def health():
    return {"status": "ok", "resolved": _resolved or None}


class VadRequest(BaseModel):
    audio_path: str


class VadRegion(BaseModel):
    start: float                        # 秒
    end: float


class VadResponse(BaseModel):
    regions: List[VadRegion]


@app.post("/vad", response_model=VadResponse)
def vad(req: VadRequest):
    """Silero VAD 语音区间检测（CPU，一次全音频）。
    后端用它把字幕起点吸附到真实说话点，治“字幕比说话早出现”
    （Whisper 把开场噪声并进首段、词级时间戳也被对齐抹到 0 的场景）。
    threshold=0.3 偏宽松：轻声/气声也算语音，保证吸附绝不吃掉真实语音起点。"""
    if not os.path.isfile(req.audio_path):
        raise HTTPException(status_code=400, detail=f"音频文件不存在: {req.audio_path}")
    try:
        from faster_whisper.audio import decode_audio
        from faster_whisper.vad import VadOptions, get_speech_timestamps
        audio = decode_audio(req.audio_path, sampling_rate=16000)
        opts = VadOptions(threshold=0.3, min_silence_duration_ms=400, speech_pad_ms=120)
        ts = get_speech_timestamps(audio, opts)
        regions = [VadRegion(start=t["start"] / 16000.0, end=t["end"] / 16000.0) for t in ts]
        return VadResponse(regions=regions)
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"VAD 失败: {e}")


class TranscribeRequest(BaseModel):
    audio_path: str
    language: Optional[str] = None      # 源语言（如 "en"），None/"auto" 自动检测
    size: str = "large-v3"              # base/small/medium/large-v3


class Word(BaseModel):
    start: float
    end: float
    text: str


class SegmentOut(BaseModel):
    start: float                        # 秒（已用 word 收紧）
    end: float
    text: str
    words: List[Word] = []


class TranscribeResponse(BaseModel):
    language: Optional[str]
    duration: float
    device: str
    compute_type: str
    segments: List[SegmentOut]


# 人类语言标签 → Whisper 语言代码（无法识别则回退自动检测）
LANG_MAP = {
    "auto": None, "": None,
    "日语": "ja", "日文": "ja", "japanese": "ja", "ja": "ja",
    "英语": "en", "英文": "en", "english": "en", "en": "en",
    "韩语": "ko", "韩文": "ko", "korean": "ko", "ko": "ko",
    "中文": "zh", "汉语": "zh", "chinese": "zh", "zh": "zh",
    "法语": "fr", "fr": "fr", "德语": "de", "de": "de",
    "西班牙语": "es", "es": "es", "俄语": "ru", "ru": "ru",
    "葡萄牙语": "pt", "pt": "pt", "意大利语": "it", "it": "it",
    "泰语": "th", "th": "th", "越南语": "vi", "vi": "vi",
    "阿拉伯语": "ar", "ar": "ar", "印地语": "hi", "hi": "hi",
}


def _norm_lang(v):
    """把请求里的 language 规范化为 Whisper 代码；识别不了就 None（自动检测），绝不报错。"""
    if not v:
        return None
    key = v.strip().lower()
    if key in LANG_MAP:
        return LANG_MAP[key]
    # 已是 2 字母代码就直接用；否则交给自动检测
    return key if len(key) == 2 and key.isalpha() else None


@app.post("/transcribe", response_model=TranscribeResponse)
def transcribe(req: TranscribeRequest):
    if not os.path.isfile(req.audio_path):
        raise HTTPException(status_code=400, detail=f"音频文件不存在: {req.audio_path}")

    model = _load(req.size)
    lang = _norm_lang(req.language)

    try:
        # vad_filter 在静音处直接切断，从源头消除 Whisper 的静音幻觉；
        # condition_on_previous_text=False 切断上下文传染，治复读循环与错误扩散；
        # hallucination_silence_threshold 配合 word 时间戳跳过疑似幻觉的长静音段
        segments, info = model.transcribe(
            req.audio_path,
            language=lang,
            word_timestamps=True,
            vad_filter=True,
            vad_parameters={"min_silence_duration_ms": 500},
            condition_on_previous_text=False,
            hallucination_silence_threshold=2.0,
        )
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"转写失败: {e}")

    out: List[SegmentOut] = []
    for s in segments:
        text = (s.text or "").strip()
        if not text:
            continue
        # 反幻觉不在此重复判定：faster-whisper 已按 no_speech_threshold=0.6 / log_prob_threshold=-1.0
        # 在解码时整窗丢弃低置信度静音段（见 transcribe 默认阈值），产出的 segment 必然已通过该门槛。
        words = [Word(start=float(w.start), end=float(w.end), text=w.word)
                 for w in (s.words or []) if w.start is not None and w.end is not None]
        # 用 word 收紧到“首词出声 ~ 末词结束”，没有 word 时回退 segment 原始区间
        if words:
            start, end = words[0].start, words[-1].end
        else:
            start, end = float(s.start), float(s.end)
        out.append(SegmentOut(start=start, end=end, text=text, words=words))

    return TranscribeResponse(
        language=info.language,
        duration=float(info.duration),
        device=_resolved.get("device", ""),
        compute_type=_resolved.get("compute_type", ""),
        segments=out,
    )
