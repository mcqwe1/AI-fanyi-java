package com.aifanyi.llm;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 译文对齐守卫的单测。
 *
 * <p>回归数据取自线上真实故障：任务 258（【双语】挑战在车里和土豆聊一小时天.mp4，
 * 61.7 分钟英语视频，Agent 模式，40 行一批）第 300~320 条字幕。当时模型从批中某处开始
 * 整体串位 2 行——第 306 条显示的中文其实是第 308 条英文的译文——并把最后一条译文
 * 重复三遍凑够 40 条。全片 575 条里 130 条（22.6%）因此串行，而代码当时毫无察觉。
 */
class AlignmentGuardTest {

    // ── 线上真实数据：第 300~320 条的英文原文（ASR 产出，含大量从句子中间断开的半截话）──
    private static final List<String> EN_300_320 = List.of(
            "oh okay so we got a little the tip is coming off here too i didn't do that that happened by itself",
            "i'm gonna put it on my head now does it look like i have anything on my head",
            "i'm um i'm a uh potato uh part of the potato religion",
            "I'm a, I'm a, the, uh, sisters of the potato, or I'm, um, you know, our lady of potato, um,",
            "I'm a potato priest, potato, Mr. Potato Head, hey, copyright, I shouldn't do that,",
            "oh, man, um, all right, well, so now we have a potato in three sections, um,",
            "and uh well i was really i was honestly i was kind of hoping the inside of this was",
            "going to be more interesting so that i had something to talk about but it's not really",
            "uh let's see if i can maybe try to peel the potato because i don't have a potato peeler",
            "that's boring never mind i don't feel like doing that hey hey hey whoa that was intense",
            "uh-oh and they're both pulling off of the same parking lot oh geez",
            "maybe we're gonna see a road rage incident if that were me i'd turn right around i'd be like",
            "no way i'll pick a different grocery store beside the one that doesn't have a crazy guy",
            "okay let's get a look at this driver some old guy you know i'm sorry to be ageist here but i",
            "really don't believe this is pushing it this is kind of not related to potatoes but you know i i",
            "old people i think you really should there's nothing against old people i'll be old one day",
            "too i'm old right now now i will say that i'm not as old as people think that i am you know i see a",
            "lot of people who are like you know because i'm 29 and so i see a lot of people who are like 29 i",
            "thought he was like middle-aged you know and these gen z gen alpha youths you know keep saying like",
            "oh this what if i'm middle-aged bald man screaming you know it's like i'm i'm 29 okay",
            "But I just think that you should have to retake your driver's test when you get into a certain age.");

    /**
     * 线上实际写进 SRT 的中文（即出错的那一版）：下标 i 的译文其实属于原文 i+2，
     * 末尾三条是同一句重复填充。
     */
    private static final List<String> ZH_DRIFTED = List.of(
            "我是，我是，呃，土豆，呃，土豆教的一员",
            "我是，我是，那个，呃，土豆姐妹会，或者我是，嗯，你知道，土豆圣母，嗯，",
            "我是土豆神父，土豆，土豆先生，嘿，版权，我不该那么说，",
            "哦，天哪，嗯，好吧，现在我们有一个分成三段的土豆，嗯，",
            "而且，嗯，我其实，说实话，我本来希望这里面",
            "能更有趣一点，这样我就能有话说了，但并没有",
            "呃，让我看看能不能试着削一下土豆皮，因为我没有削皮器",
            "那太无聊了，算了，我不想弄了，嘿，嘿，嘿，哇，那太激烈了",
            "哎呀，他们俩从同一个停车场出来，哦，天哪",
            "也许我们会看到路怒事件，如果是我，我会掉头就走，我会说",
            "不可能，我会选另一家杂货店，而不是那个有疯子的",
            "好吧，让我们看看这个司机，一个老家伙，你知道，对不起，我在这里有点年龄歧视，但我",
            "真的不觉得这过分，这跟土豆有点不相关，但你知道，我",
            "老年人，我觉得你真的应该，我对老年人没有意见，我有一天也会老",
            "我现在就老了，现在我要说，我没有人们想的那么老，你知道，我看到很多",
            "很多人，因为我才29岁，所以我看到很多29岁的人，他们",
            "以为我是中年人，你知道，这些Z世代和阿尔法世代的小年轻，一直说",
            "哦，这个，如果我是中年秃头男人尖叫，你知道，我才29岁，好吧",
            "但我就是觉得，当你到一定年龄的时候，你应该重新考驾照。",
            "但我就是觉得，当你到一定年龄的时候，你应该重新考驾照。",
            "但我就是觉得，当你到一定年龄的时候，你应该重新考驾照。");

    /** 把上面两组数据错位关系还原：原文 302~320 与它们各自<b>正确</b>的译文。 */
    private static List<String> correctSources() {
        return EN_300_320.subList(2, EN_300_320.size());        // 19 行
    }

    private static String[] correctTargets() {
        return ZH_DRIFTED.subList(0, 19).toArray(new String[0]); // 与上面一一对应
    }

    // ──────────────── 锚点校验 ────────────────

    @Test
    void 锚点落在本行原文上_判为匹配() {
        assertTrue(AlignmentGuard.anchorMatches("and the sweet",
                "and the sweet"));
        assertTrue(AlignmentGuard.anchorMatches("uh let's see if",
                "uh let's see if i can maybe try to peel the potato"));
        // 大小写与标点差异不算失配（模型抄原文时常顺手规整）
        assertTrue(AlignmentGuard.anchorMatches("Uh, let's see, if",
                "uh let's see if i can maybe try to peel the potato"));
    }

    @Test
    void 锚点落在别的行上_判为失配() {
        // 线上真实的那次串位：第 306 行拿到的是第 308 行的译文与锚点
        assertFalse(AlignmentGuard.anchorMatches(
                "uh let's see if i can",
                "and uh well i was really i was honestly i was kind of hoping"));
    }

    @Test
    void 只抄了开头两个词属于合法短抄_不算失配() {
        assertTrue(AlignmentGuard.anchorMatches("you know", "you know what i mean"));
        assertTrue(AlignmentGuard.anchorMatches("okay", "okay let's get a look at this driver"));
    }

    @Test
    void 锚点长过它声称的那行原文_判为失配() {
        // 原文整行只有 “you know”，模型却抄出更长的一串 → 它抄的是跨行拼起来的整句，正是错位的成因
        assertFalse(AlignmentGuard.anchorMatches("you know what i mean", "you know"));
        // 只超出一两个字符属于余量内的抖动，不误杀（误杀的代价是白重发一行）
        assertTrue(AlignmentGuard.anchorMatches("okay so", "okay"));
    }

    @Test
    void 开头不同一律失配() {
        assertFalse(AlignmentGuard.anchorMatches("you know", "i mean so"));
        assertFalse(AlignmentGuard.anchorMatches("delta echo", "alpha bravo charlie"));
    }

    @Test
    void 空锚点或空原文一律不算匹配() {
        assertFalse(AlignmentGuard.anchorMatches(null, "hello"));
        assertFalse(AlignmentGuard.anchorMatches("   ", "hello"));
        assertFalse(AlignmentGuard.anchorMatches("hello", ""));
        // 纯标点归一化后为空，同样不作数
        assertFalse(AlignmentGuard.anchorMatches("...", "hello"));
    }

    @Test
    void 锚点命中请求内任意一行_用于分辨串位与不听话() {
        List<String> src = List.of("alpha bravo charlie", "delta echo foxtrot");
        assertTrue(AlignmentGuard.anchorMatchesAny("delta echo", src));
        assertFalse(AlignmentGuard.anchorMatchesAny("完全不相干的中文", src));
    }

    // ──────────────── 启发式兜底 ────────────────

    @Test
    void 真实故障数据_启发式能识别出错位() {
        Set<Integer> bad = AlignmentGuard.suspectDrift(
                EN_300_320, ZH_DRIFTED.toArray(new String[0]));
        // 批尾三条重复填充必须被抓到（下标 18/19/20）
        assertTrue(bad.contains(19), "批尾重复填充应被判可疑，实际: " + AlignmentGuard.sorted(bad));
        assertTrue(bad.contains(20), "批尾重复填充应被判可疑，实际: " + AlignmentGuard.sorted(bad));
        // 整体串位的那一片也应被大面积识别出来
        assertTrue(bad.size() >= 6,
                "21 行里应识别出相当数量的错位行，实际只有 " + bad.size() + " 行: "
                        + AlignmentGuard.sorted(bad));
    }

    @Test
    void 对齐正确的同一批数据_不误伤() {
        Set<Integer> bad = AlignmentGuard.suspectDrift(correctSources(), correctTargets());
        assertTrue(bad.isEmpty(), "对齐正确时不应有任何可疑行，实际: " + AlignmentGuard.sorted(bad));
    }

    @Test
    void 批尾重复填充_原文不同则判可疑() {
        List<String> src = List.of("first line here", "second line there", "third line yonder");
        String[] tgt = {"第一行", "同一句话", "同一句话"};
        Set<Integer> bad = AlignmentGuard.suspectDrift(src, tgt);
        assertTrue(bad.contains(1));
        assertTrue(bad.contains(2));
        assertFalse(bad.contains(0));
    }

    @Test
    void 原文本来就重复时_译文相同不算可疑() {
        List<String> src = List.of("you know", "you know", "different line entirely");
        String[] tgt = {"你懂的", "你懂的", "完全不同的一行"};
        assertTrue(AlignmentGuard.suspectDrift(src, tgt).isEmpty());
    }

    @Test
    void 行数太少时不跑长度统计_避免噪声误伤() {
        // 少于 MIN_LINES_FOR_STATS 行：只保留重复检测，不做相关性判断
        List<String> src = List.of("a very very long english sentence here indeed", "short", "medium length one");
        String[] tgt = {"短", "一句相当相当长的中文译文放在这里", "中等长度"};
        assertTrue(AlignmentGuard.suspectDrift(src, tgt).isEmpty());
    }

    @Test
    void 未返回的行不参与判定() {
        List<String> src = List.of("first line here", "second line there", "third line yonder");
        String[] tgt = {"第一行", null, "第三行"};
        assertTrue(AlignmentGuard.suspectDrift(src, tgt).isEmpty());
    }

    @Test
    void 归一化只保留字母数字与汉字() {
        assertEquals("helloworld123", AlignmentGuard.normalize("Hello, World! 123"));
        assertEquals("你好world", AlignmentGuard.normalize("你好，World。"));
        assertEquals("", AlignmentGuard.normalize("——，。！"));
        assertEquals("", AlignmentGuard.normalize(null));
    }
}
