package com.aifanyi.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BabelDOC 路径的语言判定测试（纯静态方法，不依赖 Spring 容器与外部服务）。
 * 这两个值直接决定 babeldoc 的断词规则与译文语种，判错会让整篇排版/译文跑偏。
 */
class DocTranslateLangTest {

    @Test
    void 源语言按CJK占比判定() {
        assertEquals("en", DocTranslateService.guessLangIn(
                List.of("The quick brown fox jumps over the lazy dog.")));
        assertEquals("zh", DocTranslateService.guessLangIn(
                List.of("这是一篇中文文档，讲的是机器翻译。")));
        // 中英混排的技术文档：CJK 过三成即按中文（英文术语不该把它判成英文原文）
        assertEquals("zh", DocTranslateService.guessLangIn(
                List.of("本文介绍 Transformer 架构与 self-attention 机制的实现细节。")));
        // 少量中文注释的英文文档仍按英文
        assertEquals("en", DocTranslateService.guessLangIn(
                List.of("Attention Is All You Need. We propose a new simple network "
                        + "architecture, the Transformer.（摘要）")));
    }

    @Test
    void 源语言判定不被空输入击穿() {
        assertEquals("en", DocTranslateService.guessLangIn(List.of()));
        assertEquals("en", DocTranslateService.guessLangIn(List.of("", "   ")));
    }

    @Test
    void 目标语言映射到babeldoc代码() {
        assertEquals("zh", DocTranslateService.babelLang("中文"));
        assertEquals("zh", DocTranslateService.babelLang("简体中文"));
        assertEquals("zh", DocTranslateService.babelLang("Chinese"));
        assertEquals("zh-TW", DocTranslateService.babelLang("繁体中文"));
        assertEquals("en", DocTranslateService.babelLang("英文"));
        assertEquals("ja", DocTranslateService.babelLang("日语"));
        assertEquals("ko", DocTranslateService.babelLang("韩语"));
        assertEquals("zh", DocTranslateService.babelLang(null));
        assertEquals("zh", DocTranslateService.babelLang("  "));
    }

    @Test
    void 繁体优先于简体匹配() {
        // "繁体中文" 同时含「繁」和「中」，必须先判繁体，否则会退化成简体
        assertEquals("zh-TW", DocTranslateService.babelLang("繁體中文"));
        assertEquals("zh-TW", DocTranslateService.babelLang("Traditional Chinese"));
    }

    @Test
    void 未知语种原样透传() {
        // 不认识的语种直接进 babeldoc 提示词，模型看得懂，好过硬映射成错误代码
        assertEquals("泰语", DocTranslateService.babelLang("泰语"));
    }
}
