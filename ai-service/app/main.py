"""
aifanyi AI 微服务（FastAPI）

阶段2：本地 faster-whisper（GPU 优先，自动回退 CPU）做 ASR。
- 按模型尺寸（base/small/medium/large-v3）懒加载并缓存（同机 6GB 显存，一次只驻留一个）
- word 级时间戳 + VAD 过滤，从源头消除静音幻觉，并把每条字幕收紧到“首词出声 ~ 末词结束”
- 后端 LocalWhisperProvider 通过 /transcribe 调用本服务（同机直接传 audio_path，免上传）

阶段3：Gemini 原生视频理解（知识库模式）。
"""
import math
import os
import threading
import time
from typing import List, Optional

from fastapi import FastAPI, HTTPException
from fastapi.responses import Response
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

# Whisper 模型下载走国内镜像（hf-mirror.com 是 HuggingFace 的公开代理）：
# 国内网络直连 huggingface.co 大概率超时/失败，导致首次转写 500。
# 用户已自行设置 HF_ENDPOINT 时尊重用户配置。
os.environ.setdefault("HF_ENDPOINT", "https://hf-mirror.com")
# 拉长 HuggingFace 元数据/下载超时（默认 10s，对国内网络偏紧）
os.environ.setdefault("HF_HUB_ETAG_TIMEOUT", "30")
os.environ.setdefault("HF_HUB_DOWNLOAD_TIMEOUT", "30")
# hf-mirror 是国内站点，默认绕过系统代理直连：Clash/游戏加速器把它也塞进代理时，
# 大文件下载常在中途被节点切换/出口抖动掐断（SSL EOF）。直连实测更稳。
_no_proxy = os.environ.get("NO_PROXY", "")
if "hf-mirror.com" not in _no_proxy.lower():
    os.environ["NO_PROXY"] = (_no_proxy + "," if _no_proxy else "") + "hf-mirror.com"

# 预热导入：faster_whisper 采用函数内惰性导入，服务被后端自动拉起后
# /vad 与 /transcribe 首请求并发到达时，两个线程同时导入重叠模块会触发
# Python 模块锁死锁（_ModuleLock DeadlockError）。启动时单线程导完即根治。
try:
    from faster_whisper import WhisperModel as _warm_wm            # noqa: F401
    from faster_whisper.audio import decode_audio as _warm_da      # noqa: F401
    from faster_whisper.vad import VadOptions as _warm_vo          # noqa: F401
except Exception as _e:  # 依赖损坏时不挡 /health，留到具体请求时报错
    print(f"[ai-service] faster_whisper 预热导入失败（稍后按需重试）: {_e}")

# fastembed 同样必须在这里预热导入，原因同上且更凶：它首次调用会惰性导入
# onnxruntime（原生扩展、子模块多），与 /transcribe 并发到达时两个线程同时导入
# 重叠的原生模块，模块锁死锁的概率比 whisper 那次更高。
# 装不上（精简包无此依赖）时静默跳过，/embed 会返回可读错误，不影响 ASR/TTS。
try:
    from fastembed import TextEmbedding as _warm_te                 # noqa: F401
except Exception as _e:
    print(f"[ai-service] fastembed 未就绪（/embed 不可用，不影响转写/配音）: {_e}")

app = FastAPI(title="aifanyi-ai-service", version="0.2.0")

# ---- 配置（环境变量可覆盖）----
# device: auto | cuda | cpu；compute_type 留空时按 device 自动选
DEVICE = os.getenv("WHISPER_DEVICE", "auto").lower()
COMPUTE_TYPE = os.getenv("WHISPER_COMPUTE_TYPE", "").strip()
# 模型下载缓存目录（默认 HuggingFace 缓存；设置后可固定到项目内）
MODEL_DIR = os.getenv("WHISPER_MODEL_DIR", "").strip() or None


def _env_int(name: str, default: int) -> int:
    try:
        return int(os.getenv(name, "").strip() or default)
    except ValueError:
        return default


# 批量推理（faster-whisper BatchedInferencePipeline）：按 VAD 切块并行解码，
# GPU 可达 3~4x、CPU 约 2x 提速。<=0 关闭（回退串行）。
BATCH_SIZE = _env_int("WHISPER_BATCH_SIZE", 8)
# CPU 解码线程数：CTranslate2 默认 4，跑不满多核机器；留 1 核给系统/前端
CPU_THREADS = _env_int("WHISPER_CPU_THREADS", max(2, min(8, (os.cpu_count() or 4) - 1)))

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
_forced_cpu = False     # CUDA 运行库缺失/损坏时的进程级降级开关（转写中途触发）


def _load_fail_msg(size: str, e: Exception) -> str:
    """模型加载失败的人话版提示：绝大多数是首次下载模型时网络出错。"""
    return (f"加载模型 {size} 失败：{e}。"
            f"多为首次下载模型时网络中断（已默认走国内镜像 hf-mirror.com，支持断点续传，程序已自动重试 3 次）。"
            f"若你开着 VPN/游戏加速器，它可能干扰到国内镜像的连接——请关闭或为 hf-mirror.com 设置绕行后重试；"
            f"也可先换更小的模型（base/small）。")


# 本地模型库目录：ai-service/models/faster-whisper-<size>/ 存在（含 model.bin）即优先使用，
# 完全不联网。给离线分发/手动放模型用；自动下载只是它的兜底。
LOCAL_MODELS_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "models")


def _local_model_path(norm: str):
    p = os.path.join(LOCAL_MODELS_DIR, f"faster-whisper-{norm}")
    return p if os.path.isfile(os.path.join(p, "model.bin")) else None


def _new_model(norm: str, device: str, compute: str):
    """构造 WhisperModel：本地模型目录优先（零联网）；否则从镜像下载，
    网络类瞬断（SSL EOF/超时/连接重置）自动重试最多 3 次。"""
    from faster_whisper import WhisperModel
    kwargs = {}
    if device == "cpu":
        kwargs["cpu_threads"] = CPU_THREADS
    local = _local_model_path(norm)
    if local:
        print(f"[ai-service] 使用本地模型: {local}")
        return WhisperModel(local, device=device, compute_type=compute, **kwargs)
    last = None
    for attempt in range(3):
        try:
            return WhisperModel(norm, device=device, compute_type=compute,
                                download_root=MODEL_DIR, **kwargs)
        except Exception as e:
            last = e
            s = str(e).lower()
            transient = any(k in s for k in
                            ("ssl", "connect", "timeout", "timed out", "eof",
                             "snapshot", "internet", "reset", "unreachable"))
            if not transient or attempt == 2:
                raise
            wait = 3 * (attempt + 1)
            print(f"[ai-service] 模型下载/加载网络错误（第 {attempt + 1} 次）: {e} → {wait}s 后重试（断点续传）")
            time.sleep(wait)
    raise last


def _cuda_runtime_ok() -> bool:
    """有 N 卡不等于能用 GPU：还需 cuBLAS/cuDNN 运行库。
    满足其一即可：pip 装过 nvidia-* 运行库（启动时已前置进 PATH），
    或系统 PATH 里本就有 cublas64_12.dll（装过 CUDA Toolkit）。"""
    try:
        import importlib.util
        if importlib.util.find_spec("nvidia") is not None:
            return True
        import ctypes.util
        return ctypes.util.find_library("cublas64_12") is not None
    except Exception:
        return False


def _pick_device_and_compute():
    """决定 device 与 compute_type。GTX 16 系纯 fp16 有坑，CUDA 默认 int8_float16。
    检测到显卡但缺 CUDA 运行库时自动用 CPU（并提示补装方法），绝不带病上 GPU。"""
    want = "cpu" if _forced_cpu else DEVICE
    if want == "auto":
        try:
            import ctranslate2
            if ctranslate2.get_cuda_device_count() > 0:
                if _cuda_runtime_ok():
                    want = "cuda"
                else:
                    print("[ai-service] 检测到 NVIDIA 显卡但未安装 CUDA 运行库，本次用 CPU。"
                          "要启用 GPU 加速请运行: .venv\\Scripts\\python -m pip install"
                          " nvidia-cublas-cu12 nvidia-cudnn-cu12 -i https://pypi.tuna.tsinghua.edu.cn/simple")
                    want = "cpu"
            else:
                want = "cpu"
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

        device, compute = _pick_device_and_compute()

        # 显存有限：换尺寸时只保留当前一个模型
        _models.clear()

        try:
            model = _new_model(norm, device, compute)
        except Exception as e:
            if device == "cuda":
                # GPU 不可用（缺 cuDNN/cuBLAS 或显存不足）→ 回退 CPU
                try:
                    device, compute = "cpu", COMPUTE_TYPE or "int8"
                    model = _new_model(norm, device, compute)
                except Exception as e2:
                    raise HTTPException(status_code=500, detail=_load_fail_msg(norm, e2))
            else:
                raise HTTPException(status_code=500, detail=_load_fail_msg(norm, e))

        _resolved["device"] = device
        _resolved["compute_type"] = compute
        _models[norm] = model
        return model


@app.get("/health")
def health():
    return {"status": "ok", "resolved": _resolved or None}


class TtsRequest(BaseModel):
    text: str
    voice: str = "zh-CN-XiaoxiaoNeural"
    rate: str = "+0%"                   # edge-tts 语速格式：+20% / -20%


@app.post("/tts")
async def tts(req: TtsRequest):
    """Edge-TTS（微软语音）合成一段文本，返回 mp3 字节。免费免 Key，需联网。
    后端 EdgeTtsProvider 通过本接口逐行合成配音。"""
    try:
        import edge_tts
    except ImportError:
        raise HTTPException(status_code=500, detail=(
            "edge-tts 未安装：内置运行时请更新完整包；"
            "自装环境在 ai-service 目录执行 .venv\\Scripts\\pip install edge-tts"))
    text = (req.text or "").strip()
    if not text:
        raise HTTPException(status_code=400, detail="text 不能为空")
    try:
        com = edge_tts.Communicate(text, req.voice, rate=req.rate)
        buf = bytearray()
        async for chunk in com.stream():
            if chunk["type"] == "audio":
                buf.extend(chunk["data"])
        if not buf:
            raise HTTPException(status_code=500,
                                detail=f"edge-tts 未返回音频（音色 {req.voice} 可能无效）")
        return Response(content=bytes(buf), media_type="audio/mpeg")
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"edge-tts 合成失败: {e}")


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


EMBED_MODEL_NAME = os.getenv("EMBED_MODEL", "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2")
EMBED_DIM = 384
_embedder = None
_embed_lock = threading.Lock()


def _load_embedder():
    """加载句向量模型（进程内单例）。

    **纯 CPU（ONNX）**：本机只有 6GB 显存，large-v3 转写正跑在 GPU 上；
    embedding 再去抢显存会直接把转写 OOM 掉。它只服务术语近义发现，
    单次几十条、CPU 上几十毫秒足够，没有任何上 GPU 的理由。
    fastembed 默认就是 onnxruntime CPU provider，不装 onnxruntime-gpu 即不可能占显存。
    """
    global _embedder
    if _embedder is not None:
        return _embedder
    with _embed_lock:
        if _embedder is not None:
            return _embedder
        try:
            from fastembed import TextEmbedding
        except ImportError:
            raise HTTPException(status_code=500, detail=(
                "fastembed 未安装：完整包已内置；"
                "自装环境在 ai-service 目录执行 "
                ".venv\\Scripts\\pip install fastembed"))
        # 本地模型目录优先（离线分发/随包内置），否则联网下载 —— 与 whisper 同一套三层防御
        local = os.path.join(LOCAL_MODELS_DIR, "fastembed")
        has_local = os.path.isdir(local) and any(
            name.startswith("models--") for name in os.listdir(local))
        kwargs = {"cache_dir": local} if os.path.isdir(local) else {}
        if has_local:
            # 模型已随包内置：直接离线加载，别去探网。
            # 不置 HF_HUB_OFFLINE 的话，fastembed 仍会先请求 Hub 校验版本——
            # 用户断网或镜像不可达时要卡满整个重试退避（实测 40 秒）才回落到本地，
            # 而本地明明就有。
            os.environ["HF_HUB_OFFLINE"] = "1"
            try:
                _embedder = TextEmbedding(model_name=EMBED_MODEL_NAME, **kwargs)
                print(f"[ai-service] 句向量模型已加载（内置离线）: {local}")
                return _embedder
            except Exception as e:
                print(f"[ai-service] 内置模型加载失败，改为联网获取: {e}")
                os.environ.pop("HF_HUB_OFFLINE", None)
        last = None
        # 镜像优先（国内直连 huggingface.co 多半超时），镜像不可达时回落官方站。
        # 两者都试是必要的：实测存在「hf-mirror 挂了但官方站通」的机器，
        # 只认镜像会让这些用户永远下不到模型，还看不出原因。
        for endpoint in (os.environ.get("HF_ENDPOINT"), "https://huggingface.co"):
            if not endpoint:
                continue
            try:
                # huggingface_hub 在 import 时就把 ENDPOINT 读成模块常量，
                # 改环境变量对已导入的模块无效，必须直接改常量本身
                import huggingface_hub.constants as _hfc
                _hfc.ENDPOINT = endpoint.rstrip("/")
                _hfc.HUGGINGFACE_CO_URL_TEMPLATE = (
                    _hfc.ENDPOINT + "/{repo_id}/resolve/{revision}/{filename}")
                _embedder = TextEmbedding(model_name=EMBED_MODEL_NAME, **kwargs)
                print(f"[ai-service] 句向量模型已加载: {EMBED_MODEL_NAME} (ONNX/CPU, {endpoint})")
                return _embedder
            except Exception as e:
                last = e
                print(f"[ai-service] 从 {endpoint} 加载句向量模型失败，尝试下一个源: {e}")
        raise HTTPException(status_code=500, detail=(
            f"加载句向量模型失败：{last}。多为首次下载时网络中断"
            f"（已依次尝试国内镜像与官方站）。Agent 模式的术语近义发现会自动降级为"
            f"精确匹配，不影响翻译。"))


class EmbedRequest(BaseModel):
    texts: List[str]                    # 批量入参：一次扇出一次调用，别逐条发


class EmbedResponse(BaseModel):
    vectors: List[List[float]]
    dim: int
    model: str


@app.post("/embed", response_model=EmbedResponse)
def embed(req: EmbedRequest):
    """批量文本向量化（ONNX/CPU，多语言）。

    Agent 模式 ⑤ 用它做术语的「词族合并/近义发现」——注意主链的术语注入走
    内存字符串匹配，**不依赖本接口**；本接口挂掉只是退化为精确匹配。
    模型选 paraphrase-multilingual-MiniLM-L12-v2（384 维）：术语表天然双语
    （源 ja/en、目标 zh），单语模型是错的，bge-m3 又太重。
    """
    texts = [t for t in (req.texts or []) if t and t.strip()]
    if not texts:
        raise HTTPException(status_code=400, detail="texts 不能为空")
    if len(texts) > 512:
        raise HTTPException(status_code=400, detail=f"单次最多 512 条，收到 {len(texts)}")
    model = _load_embedder()
    try:
        # fastembed 返回生成器，且**不做 L2 归一化**（实测范数约 2.86 而非 1）。
        # 必须在这里归一化：Qdrant 侧用 Cosine 距离、⑤ 侧可能直接点积算相似度，
        # 不归一化会让长句因范数大而虚高，近义判定整体失真。
        out = []
        for v in model.embed(texts):
            vec = [float(x) for x in v]
            n = math.sqrt(sum(x * x for x in vec))
            out.append([x / n for x in vec] if n > 0 else vec)
        return EmbedResponse(vectors=out, dim=len(out[0]) if out else EMBED_DIM,
                             model=EMBED_MODEL_NAME)
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"向量化失败: {e}")


class TranscribeRequest(BaseModel):
    audio_path: str
    language: Optional[str] = None      # 源语言（如 "en"），None/"auto" 自动检测
    size: str = "large-v3"              # base/small/medium/large-v3
    progress_path: Optional[str] = None  # 可选：转写进度（0~1）写入该文件，后端轮询给前端进度条


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


def _looks_like_cuda_error(e: Exception) -> bool:
    """CUDA 库缺失/损坏类错误的特征串（cublas/cudnn/cuda）。"""
    s = str(e).lower()
    return "cublas" in s or "cudnn" in s or "cuda" in s


def _run_transcribe(model, req: TranscribeRequest, lang):
    """执行转写并完整消费生成器（faster-whisper 惰性求值，错误在迭代时才抛，必须在这里面接住）。
    优先走 BatchedInferencePipeline（VAD 切块并行解码，GPU 3~4x / CPU ~2x 提速）；
    显存不够（OOM）或批量路径异常时自动回退经典串行路径，真 CUDA 运行库错误则向上抛
    （由 /transcribe 外层降级 CPU）。"""
    if BATCH_SIZE > 0:
        try:
            from faster_whisper import BatchedInferencePipeline
            pipe = BatchedInferencePipeline(model=model)
            # 批量管线按 VAD 切块，天然不做跨窗上下文传染；no_speech/logprob 阈值同串行默认。
            # without_timestamps 必须显式关掉：默认 True 会把整个 VAD 块（最长 30s）合成
            # 一条 segment，字幕会变成 30 秒一大条；False 恢复句级时间戳切分。
            segments, info = pipe.transcribe(
                req.audio_path,
                language=lang,
                word_timestamps=True,
                without_timestamps=False,
                vad_filter=True,
                # threshold=0.3 与 /vad 对轴接口同一把尺子（默认 0.5 会把轻声/气声
                # 判为非语音，整句台词在进模型前就被门卫拦掉——客诉「整句没了」的根因，
                # 实测 29 分钟轻声直播在 0.5 下丢 9 段共 1.4 分钟真实台词）
                vad_parameters={"min_silence_duration_ms": 500, "threshold": 0.3},
                batch_size=BATCH_SIZE,
            )
            return _collect(segments, info, req.progress_path), info
        except Exception as e:
            s = str(e).lower()
            if _looks_like_cuda_error(e) and "out of memory" not in s:
                raise                       # cuBLAS/cuDNN 缺失类错误：交给外层做 CPU 降级
            print(f"[ai-service] 批量转写不可用（{e}），回退串行路径")

    # vad_filter 在静音处直接切断，从源头消除 Whisper 的静音幻觉；
    # condition_on_previous_text=False 切断上下文传染，治复读循环与错误扩散；
    # hallucination_silence_threshold 配合 word 时间戳跳过疑似幻觉的长静音段
    segments, info = model.transcribe(
        req.audio_path,
        language=lang,
        word_timestamps=True,
        vad_filter=True,
        vad_parameters={"min_silence_duration_ms": 500, "threshold": 0.3},
        condition_on_previous_text=False,
        hallucination_silence_threshold=2.0,
    )
    return _collect(segments, info, req.progress_path), info


def _write_progress(progress_path: Optional[str], ratio: float):
    """尽力而为地写进度（0~1）；写失败绝不影响转写。"""
    if not progress_path:
        return
    try:
        with open(progress_path, "w", encoding="ascii") as f:
            f.write(f"{min(0.99, max(0.0, ratio)):.3f}")
    except OSError:
        pass


def _collect(segments, info, progress_path: Optional[str]):
    """消费 segment 生成器：过滤空文本、word 收紧起止、按段回写进度。"""
    total = float(info.duration) if info.duration else 0.0
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
        if total > 0:
            _write_progress(progress_path, end / total)
    return out


@app.post("/transcribe", response_model=TranscribeResponse)
def transcribe(req: TranscribeRequest):
    global _forced_cpu
    if not os.path.isfile(req.audio_path):
        raise HTTPException(status_code=400, detail=f"音频文件不存在: {req.audio_path}")

    model = _load(req.size)
    lang = _norm_lang(req.language)

    try:
        out, info = _run_transcribe(model, req, lang)
    except HTTPException:
        raise
    except Exception as e:
        # GPU 运行库问题可能在转写中途才暴露（模型加载不触发 cuBLAS）→ 降级 CPU 重试一次
        if _looks_like_cuda_error(e) and not _forced_cpu:
            print(f"[ai-service] GPU 转写失败（{e}），自动降级 CPU 重试。"
                  "要启用 GPU 请安装: nvidia-cublas-cu12 nvidia-cudnn-cu12")
            _forced_cpu = True
            with _lock:
                _models.clear()
            model = _load(req.size)
            try:
                out, info = _run_transcribe(model, req, lang)
            except Exception as e2:
                raise HTTPException(status_code=500, detail=f"转写失败: {e2}")
        else:
            raise HTTPException(status_code=500, detail=f"转写失败: {e}")

    return TranscribeResponse(
        language=info.language,
        duration=float(info.duration),
        device=_resolved.get("device", ""),
        compute_type=_resolved.get("compute_type", ""),
        segments=out,
    )
