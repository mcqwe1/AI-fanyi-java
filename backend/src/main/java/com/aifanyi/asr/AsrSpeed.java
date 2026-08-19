package com.aifanyi.asr;

import java.util.List;

/**
 * 各语音识别档位的实测速度，供前端在提交前算「预计耗时」。
 *
 * <p><b>倍速 = 媒体时长 ÷ 转写耗时</b>。数字来自 2026-08-14 的真机实测：
 * 同一段 1008.6 秒英文演讲，同一块 NVIDIA 显卡（cuda / int8_float16），只换档位——
 * <pre>
 *   Groq 云 large-v3   ≈10.6s   95×      248 段
 *   本地 base           25.5s   40×      186 段
 *   本地 small          41.4s   24×      254 段
 *   本地 medium         74.2s   14×      240 段
 *   本地 large-v3      136.6s  7.4×      253 段
 * </pre>
 *
 * <p>为什么要有这张表：这五档之间差 <b>5 倍以上</b>，而下拉框里原先只有名字，
 * 用户是完全盲选的——选了 large-v3 就得干等两分钟，且不知道为什么。
 *
 * <p><b>这是估算不是承诺</b>：显卡型号、CPU 核数、音频难度都会影响实际耗时，
 * 所以前端措辞一律用「预计」。CPU 机器按 {@link #CPU_PENALTY} 整体放慢
 * （本地档才受影响，Groq 在云端跑，与本机无关）。
 */
public final class AsrSpeed {

    private AsrSpeed() {
    }

    /** 没有显卡时本地档的放慢倍数（CPU int8 相对 GPU int8_float16 的经验值）。 */
    private static final double CPU_PENALTY = 4.0;

    /**
     * 一个可选档位。
     *
     * @param value       与任务表单 asrProvider 取值一致
     * @param speedFactor GPU（或云端）下的实测倍速
     * @param local       是否本机跑（决定要不要吃 CPU 惩罚）
     */
    public record Option(String value, String label, String tag, double speedFactor, boolean local) {
    }

    private static final List<Option> OPTIONS = List.of(
            new Option("groq", "Groq（large-v3，需要魔法）", "cloud", 95.0, false),
            new Option("groq-turbo", "Groq Turbo（更快，精度略低）", "cloud", 130.0, false),
            new Option("local-base", "本地 base", "free", 40.0, true),
            new Option("local-small", "本地 small", "free", 24.0, true),
            new Option("local-medium", "本地 medium", "free", 14.0, true),
            new Option("local-large-v3", "本地 large-v3（最准）", "free", 7.4, true));

    /**
     * 按本机实际算力给出各档倍速。
     *
     * @param device ai-service 报的 device（cuda/cpu），null = 还没加载过模型、未知
     */
    public static List<Option> options(String device) {
        boolean cpu = "cpu".equalsIgnoreCase(device);
        if (!cpu) {
            // 未知时按 GPU 给（乐观值），前端已标「预计」；真跑过一次后 device 就确定了
            return OPTIONS;
        }
        return OPTIONS.stream()
                .map(o -> o.local()
                        ? new Option(o.value(), o.label(), o.tag(),
                        round1(o.speedFactor() / CPU_PENALTY), true)
                        : o)
                .toList();
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
