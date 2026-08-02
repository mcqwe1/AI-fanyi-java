package com.aifanyi.media;

import com.aifanyi.asr.Segment;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 文本层反幻觉过滤，ASR 之后统一套用（对所有 provider 生效）：
 * ① 黑名单——Whisper 训练集（视频字幕）残留的“致谢/订阅/字幕组水印”类套话；
 * ② 复读折叠——解码卡循环时同一句连续大量重复，只保留前几条；
 * ③ 纯数字连跑——对敲击/按摩等节奏声常幻觉出连续数数。
 *
 * 已知取舍（有意为之，非缺陷）：
 *  - CONTAINS 里的片尾口播（チャンネル登録 / 点赞订阅 / 订阅频道）真人也会说，但 Whisper
 *    在静音处正是高频幻觉这些串；本项目（节奏声/静音偏多）按“宁可误杀片尾口播”处理。
 *  - 连续数数整段丢弃（不像复读只留前几条），故真人逐个报数也会被清掉；靠“时间相接”收窄误伤。
 * 复读/连跑只在“时间相接”的相邻段间判定，隔着长静音/间奏的真实重复不会被折叠。
 */
@Slf4j
public final class HallucinationFilter {

    private HallucinationFilter() {
    }

    /** 归一化后整句等于这些条目 → 判为幻觉（均为“节目收尾套话”，正文不会整句单独出现）。 */
    private static final String[] EXACT = {
            "ご視聴ありがとうございました", "ご清聴ありがとうございました",
            "ご視聴ありがとうございます", "最後までご視聴いただきありがとうございました",
            "谢谢观看", "感谢观看", "谢谢大家观看", "谢谢收看",
            "thanks for watching", "thank you for watching", "thank you so much for watching",
            "please subscribe",
            // “晚安”家族：Whisper 对呼吸声/静音的高频幻觉（日语源尤甚，おやすみ→译成“晚安”反复出现）。
            // 真人整句只说“晚安”的收尾口播也会被误杀——按本项目“宁可误杀收尾套话”的既定取舍处理。
            "おやすみなさい", "おやすみ", "晚安", "good night",
    };

    /** 包含即判为幻觉的强特征子串（字幕组水印/频道推广）。 */
    private static final String[] CONTAINS = {
            "チャンネル登録", "amara.org", "明镜与点点", "点赞订阅", "订阅频道",
            "subtitles by", "subs by", "字幕by", "字幕製作", "字幕制作",
    };

    /** 同一句（归一化后）连续出现超过该次数视为复读循环，超出部分丢弃。 */
    private static final int MAX_CONSECUTIVE_REPEATS = 2;

    /** 连跑/复读判定的最大相邻间隔（毫秒）：相邻两段间隔超过此值视为“不相接”，不算解码循环/连数。 */
    private static final long ADJACENT_MAX_GAP_MS = 5000;

    /**
     * 去空白/标点/符号做鲁棒匹配，用 Unicode 类而非 ASCII 专属的 \\s\\p{Punct}：
     * 覆盖全角空格 U+3000、波浪号 U+301C/U+FF5E、《》【】〈〉｡､｢｣、♪ 等 Whisper 常见残留。
     */
    private static final Pattern STRIP = Pattern.compile("[\\p{IsWhite_Space}\\p{P}\\p{S}]+");
    /** 纯数字（含全角 ０-９ 及其它十进制数字脚本），用于识别“连续数数”幻觉。 */
    private static final Pattern PURE_DIGITS = Pattern.compile("\\p{Nd}+");

    private static final List<String> EXACT_NORM = new ArrayList<>();
    /** 无空格串（CJK 水印或连写）：按子串匹配。 */
    private static final List<String> CONTAINS_SUB = new ArrayList<>();
    /** 含空格的拉丁短语：按整词匹配（前后须为词界），避免 "amara org" 命中 "amara organized"。 */
    private static final List<String> CONTAINS_PHRASE = new ArrayList<>();

    static {
        for (String e : EXACT) {
            EXACT_NORM.add(normalize(e));
        }
        for (String c : CONTAINS) {
            String spaced = normalizeSpaced(c);
            if (spaced.indexOf(' ') >= 0) {
                CONTAINS_PHRASE.add(spaced);
            } else {
                CONTAINS_SUB.add(spaced);   // 无空格时 spaced 即等于 norm
            }
        }
    }

    public static List<Segment> filter(List<Segment> in) {
        if (in == null || in.isEmpty()) {
            return in;
        }
        // 第一步：剔除黑名单套话与空段，得到“幸存流”。连跑/复读都在幸存流上判定，
        // 避免“水印夹在数字幻觉之间”被当成孤立数字漏过，或跨删除误判复读。
        List<Segment> kept = new ArrayList<>(in.size());
        List<String> keptNorm = new ArrayList<>(in.size());
        List<String> blackText = new ArrayList<>();
        int blacklisted = 0;
        for (Segment s : in) {
            String spaced = normalizeSpaced(s.text());
            String norm = spaced.replace(" ", "");
            if (norm.isEmpty() || isBlacklisted(norm, spaced)) {
                blacklisted++;
                blackText.add(String.format("%s 「%s」",
                        SubtitleTimingFixer.ts(s.startMs()), s.text()));
                continue;
            }
            kept.add(s);
            keptNorm.add(norm);
        }

        boolean[] digit = new boolean[kept.size()];
        for (int i = 0; i < kept.size(); i++) {
            digit[i] = isPureDigits(keptNorm.get(i));
        }

        // 第二步：纯数字连跑 + 复读折叠（均以“时间相接”为前提）
        List<Segment> out = new ArrayList<>(kept.size());
        List<String> collapsedText = new ArrayList<>();
        List<String> digitText = new ArrayList<>();
        int collapsed = 0;
        int digitRuns = 0;
        String prevNorm = null;
        long prevEnd = Long.MIN_VALUE;
        int run = 0;
        for (int i = 0; i < kept.size(); i++) {
            Segment s = kept.get(i);
            // 连跑：本段纯数字且时间相接的相邻幸存段也是纯数字 → 数数幻觉；孤立/远隔数字（真实报数）保留
            if (digit[i]
                    && (neighborDigit(kept, digit, i - 1, s)
                        || neighborDigit(kept, digit, i + 1, s))) {
                digitRuns++;
                digitText.add(String.format("%s 「%s」",
                        SubtitleTimingFixer.ts(s.startMs()), s.text()));
                prevNorm = null;             // 丢弃后重置，避免跨删除把前后同句误判为连续复读
                run = 0;
                prevEnd = s.endMs();
                continue;
            }
            String norm = keptNorm.get(i);
            boolean adjacent = s.startMs() - prevEnd <= ADJACENT_MAX_GAP_MS;
            if (norm.equals(prevNorm) && adjacent) {
                run++;
                if (run > MAX_CONSECUTIVE_REPEATS) {
                    collapsed++;
                    collapsedText.add(String.format("%s 「%s」",
                            SubtitleTimingFixer.ts(s.startMs()), s.text()));
                    prevEnd = s.endMs();
                    continue;
                }
            } else {
                prevNorm = norm;
                run = 1;
            }
            prevEnd = s.endMs();
            out.add(s);
        }
        if (blacklisted + collapsed + digitRuns > 0) {
            log.info("反幻觉过滤：黑名单 {} 条，复读折叠 {} 条，数字连跑 {} 条（{} → {} 条）",
                    blacklisted, collapsed, digitRuns, in.size(), out.size());
            // 明细全量打印：漏翻排查时要能看出删掉的到底是幻觉还是真台词
            for (String b : blackText) {
                log.info("  [丢弃-黑名单] {}", b);
            }
            for (String c : collapsedText) {
                log.info("  [丢弃-复读] {}", c);
            }
            for (String d : digitText) {
                log.info("  [丢弃-数字连跑] {}", d);
            }
        }
        return out;
    }

    /** 索引 idx 处是否为“与 cur 时间相接的纯数字段”。 */
    private static boolean neighborDigit(List<Segment> segs, boolean[] digit, int idx, Segment cur) {
        if (idx < 0 || idx >= segs.size() || !digit[idx]) {
            return false;
        }
        Segment other = segs.get(idx);
        long gap = other.startMs() <= cur.startMs()
                ? cur.startMs() - other.endMs()      // other 在前
                : other.startMs() - cur.endMs();      // other 在后
        return gap <= ADJACENT_MAX_GAP_MS;
    }

    private static boolean isPureDigits(String norm) {
        return !norm.isEmpty() && PURE_DIGITS.matcher(norm).matches();
    }

    private static boolean isBlacklisted(String norm, String spaced) {
        for (String e : EXACT_NORM) {
            if (norm.equals(e)) {
                return true;
            }
        }
        for (String e : CONTAINS_SUB) {
            if (norm.contains(e)) {
                return true;
            }
        }
        if (!CONTAINS_PHRASE.isEmpty()) {
            String padded = " " + spaced + " ";        // 首尾补空格，使整词短语可按词界匹配
            for (String e : CONTAINS_PHRASE) {
                if (padded.contains(" " + e + " ")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 小写化并去掉空白/标点/符号（Unicode 级），做鲁棒匹配。 */
    private static String normalize(String t) {
        return normalizeSpaced(t).replace(" ", "");
    }

    /** 同 normalize，但把分隔符折叠为单个空格并去首尾——保留词界，供整词短语匹配用。 */
    private static String normalizeSpaced(String t) {
        if (t == null) {
            return "";
        }
        return STRIP.matcher(t.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
    }
}
