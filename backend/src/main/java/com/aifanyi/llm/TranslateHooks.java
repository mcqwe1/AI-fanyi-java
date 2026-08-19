package com.aifanyi.llm;

import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

/**
 * 翻译过程中的两个旁路钩子：进度上报与提前中止。
 *
 * <p>做成一个对象而不是往 translate() 上继续加参数，是因为这两件事都属于
 * 「调用方想知道 / 想干预进度」，以后再有同类需求（比如逐批回写译文）也往这里加。
 *
 * @param onLineDone 每完成一批回调一次，参数是<b>累计已完成行数</b>。
 *                   实现方保证：这个回调抛异常不会影响翻译本身。
 * @param keepGoing  每批开跑前问一次「还要继续吗」。返回 false 的批次直接原文返回、不发请求。
 *                   给「用户中途删了任务」用——否则删完还要把整篇烧完才停。
 */
public record TranslateHooks(IntConsumer onLineDone, BooleanSupplier keepGoing) {

    private static final TranslateHooks NONE = new TranslateHooks(null, null);

    /** 不需要任何钩子。 */
    public static TranslateHooks none() {
        return NONE;
    }

    /** 只要进度。 */
    public static TranslateHooks progress(IntConsumer onLineDone) {
        return new TranslateHooks(onLineDone, null);
    }

    /** 进度 + 中止判定。 */
    public static TranslateHooks of(IntConsumer onLineDone, BooleanSupplier keepGoing) {
        return new TranslateHooks(onLineDone, keepGoing);
    }

    /** 调用方没给判定就一路跑到底。 */
    public boolean shouldContinue() {
        return keepGoing == null || keepGoing.getAsBoolean();
    }
}
