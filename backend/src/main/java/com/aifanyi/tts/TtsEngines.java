package com.aifanyi.tts;

import java.util.List;

/**
 * 内置 TTS 引擎注册表：引擎信息 + 每引擎的音色映射（单一事实源）。
 * 音色 value 直接存该引擎实际可用的完整音色标识（格式差异在此吸收，provider 不做任何猜测改写），
 * label 为界面展示的友好名。
 */
public final class TtsEngines {

    public record VoiceInfo(String value, String label) {
    }

    /**
     * @param id             引擎标识（存 user_setting.tts_provider）
     * @param name           展示名
     * @param desc           一句话介绍（设置页卡片）
     * @param tag            标签：free=免费 / cloud=云端需Key / custom=自定义
     * @param needKey        是否需要 API Key
     * @param defaultBaseUrl 预设 Base URL（custom 引擎为空，用户自填）
     * @param defaultModel   预设模型（custom 引擎为空，用户自填）
     * @param voices         预置音色映射
     */
    public record Engine(String id, String name, String desc, String tag, boolean needKey,
                         String defaultBaseUrl, String defaultModel, List<VoiceInfo> voices) {
    }

    /** 微软 Edge 朗读：免费免 Key（需联网），中文音色多，经本机 ai-service 转发。 */
    public static final Engine EDGE = new Engine(
            "edge", "Edge-TTS（微软语音）",
            "完全免费、无需密钥（需联网）。微软 Edge 朗读引擎，中文音色丰富自然，已内置开箱即用。",
            "free", false, "", "edge-tts",
            List.of(
                    new VoiceInfo("zh-CN-XiaoxiaoNeural", "晓晓 · 女声温柔（普通话）"),
                    new VoiceInfo("zh-CN-XiaoyiNeural", "晓伊 · 女声活泼（普通话）"),
                    new VoiceInfo("zh-CN-YunxiNeural", "云希 · 男声阳光（普通话）"),
                    new VoiceInfo("zh-CN-YunjianNeural", "云健 · 男声浑厚（普通话）"),
                    new VoiceInfo("zh-CN-YunyangNeural", "云扬 · 男声播音（普通话）"),
                    new VoiceInfo("zh-CN-YunxiaNeural", "云夏 · 少年音（普通话）"),
                    new VoiceInfo("zh-CN-liaoning-XiaobeiNeural", "晓北 · 女声（东北口音）"),
                    new VoiceInfo("zh-TW-HsiaoChenNeural", "晓臻 · 女声（台湾腔）"),
                    new VoiceInfo("zh-HK-HiuMaanNeural", "曉曼 · 女声（粤语）"),
                    new VoiceInfo("ja-JP-NanamiNeural", "七海 · 女声（日语）"),
                    new VoiceInfo("en-US-AriaNeural", "Aria · 女声（英语）"),
                    new VoiceInfo("en-US-GuyNeural", "Guy · 男声（英语）")
            ));

    /** 硅基流动 CosyVoice2：国内直连、便宜、中文效果好；音色 value 为「模型:音色」全称。 */
    public static final Engine SILICONFLOW = new Engine(
            "siliconflow", "硅基流动 CosyVoice",
            "国内直连、价格便宜、中文效果好（阿里 CosyVoice2 模型）。到 siliconflow.cn 注册领取 API Key 即用。",
            "cloud", true, "https://api.siliconflow.cn/v1", "FunAudioLLM/CosyVoice2-0.5B",
            List.of(
                    new VoiceInfo("FunAudioLLM/CosyVoice2-0.5B:alex", "Alex · 沉稳男声"),
                    new VoiceInfo("FunAudioLLM/CosyVoice2-0.5B:benjamin", "Benjamin · 低沉男声"),
                    new VoiceInfo("FunAudioLLM/CosyVoice2-0.5B:charles", "Charles · 磁性男声"),
                    new VoiceInfo("FunAudioLLM/CosyVoice2-0.5B:david", "David · 欢快男声"),
                    new VoiceInfo("FunAudioLLM/CosyVoice2-0.5B:anna", "Anna · 沉稳女声"),
                    new VoiceInfo("FunAudioLLM/CosyVoice2-0.5B:bella", "Bella · 激情女声"),
                    new VoiceInfo("FunAudioLLM/CosyVoice2-0.5B:claire", "Claire · 温柔女声"),
                    new VoiceInfo("FunAudioLLM/CosyVoice2-0.5B:diana", "Diana · 欢快女声")
            ));

    /** 自定义 OpenAI 兼容端点：官方 OpenAI TTS 或任意兼容中转，全部字段自填。 */
    public static final Engine OPENAI = new Engine(
            "openai", "自定义 OpenAI 兼容端点",
            "适用于官方 OpenAI TTS（tts-1 系）或任意 OpenAI 兼容中转站，Base URL / Key / 模型自行填写。",
            "custom", true, "", "",
            List.of(
                    new VoiceInfo("alloy", "Alloy · 中性"),
                    new VoiceInfo("echo", "Echo · 男声"),
                    new VoiceInfo("fable", "Fable · 英式"),
                    new VoiceInfo("onyx", "Onyx · 浑厚男声"),
                    new VoiceInfo("nova", "Nova · 女声"),
                    new VoiceInfo("shimmer", "Shimmer · 柔和女声")
            ));

    private static final List<Engine> ALL = List.of(EDGE, SILICONFLOW, OPENAI);

    public static List<Engine> all() {
        return ALL;
    }

    /** 按 id 找引擎；未知/未选择返回 null。 */
    public static Engine byId(String id) {
        if (id == null || id.isBlank()) return null;
        for (Engine e : ALL) {
            if (e.id().equalsIgnoreCase(id.trim())) return e;
        }
        return null;
    }

    private TtsEngines() {
    }
}
