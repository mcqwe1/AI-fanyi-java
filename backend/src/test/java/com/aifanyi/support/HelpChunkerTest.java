package com.aifanyi.support;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 客服 RAG 的切块与关键词兜底检索：纯函数，锁死语义边界与确定性。
 */
class HelpChunkerTest {

    @Test
    void 按标题切块_标题路径正确() {
        String md = """
                # 手册
                开头介绍。

                ## 快速上手
                第一步第二步。

                ## 设置
                ### API 密钥
                填钥匙。
                """;
        List<HelpChunker.Chunk> out = HelpChunker.chunk(md);
        assertThat(out).hasSize(3);
        assertThat(out.get(0).title()).isEqualTo("总览");
        assertThat(out.get(1).title()).isEqualTo("快速上手");
        assertThat(out.get(2).title()).isEqualTo("设置 · API 密钥");
        assertThat(out.get(2).body()).contains("填钥匙");
        assertThat(out.get(2).text()).startsWith("【设置 · API 密钥】");
    }

    @Test
    void 超长节按段落再切_不硬切句子() {
        String para = "字".repeat(400);
        String md = "## 长节\n" + para + "\n\n" + para + "\n\n" + para + "\n";
        List<HelpChunker.Chunk> out = HelpChunker.chunk(md);
        assertThat(out.size()).isGreaterThanOrEqualTo(2);
        for (HelpChunker.Chunk c : out) {
            assertThat(c.title()).isEqualTo("长节");
            assertThat(c.body().replace("\n", "")).hasSizeLessThanOrEqualTo(810);
        }
    }

    @Test
    void 无空行的长列表按行再切_条目不被切开() {
        String item = "- **条目**：" + "说".repeat(120) + "\n";
        List<HelpChunker.Chunk> out = HelpChunker.chunk("## 用法\n" + item.repeat(8));
        assertThat(out.size()).isGreaterThanOrEqualTo(2);
        for (HelpChunker.Chunk c : out) {
            assertThat(c.title()).isEqualTo("用法");
            assertThat(c.body().length()).isLessThanOrEqualTo(900);
            for (String line : c.body().split("\n")) {
                // 行是切分边界：每行都还是一条完整条目，没有从中间断开
                assertThat(line.strip()).startsWith("- **条目**：");
            }
        }
    }

    @Test
    void 关键词打分_标题命中权重更高() {
        HelpChunker.Chunk titleHit = new HelpChunker.Chunk("配音怎么用", "内容与问题无关。");
        HelpChunker.Chunk bodyHit = new HelpChunker.Chunk("别的", "这里提到配音一次。");
        int ts = HelpChunker.keywordScore("配音在哪里", titleHit);
        int bs = HelpChunker.keywordScore("配音在哪里", bodyHit);
        assertThat(ts).isGreaterThan(bs);
        assertThat(bs).isGreaterThan(0);
    }

    @Test
    void 分词_中日韩双字加拉丁词() {
        List<String> tokens = HelpChunker.tokenize("Groq 密钥怎么配");
        assertThat(tokens).contains("groq", "密钥", "怎么");
    }

    @Test
    void 真实手册资源能加载且切出足量知识块() throws Exception {
        String md = new String(new org.springframework.core.io.ClassPathResource("help/manual.md")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        List<HelpChunker.Chunk> out = HelpChunker.chunk(md);
        // 手册结构护栏：块数量与关键章节存在（改坏资源路径或删章节时在此炸出）
        assertThat(out.size()).isGreaterThanOrEqualTo(15);
        assertThat(out).anyMatch(c -> c.title().contains("快速上手"));
        assertThat(out).anyMatch(c -> c.title().contains("全能AI翻译"));
        assertThat(out).anyMatch(c -> c.title().contains("常见问题"));
        // 每块都不超过嵌入友好长度太多
        for (HelpChunker.Chunk c : out) {
            assertThat(c.body().length()).isLessThanOrEqualTo(900);
        }
        // 关键词兜底对典型问题能命中对的章节
        HelpChunker.Chunk best = out.stream()
                .max(java.util.Comparator.comparingInt(c -> HelpChunker.keywordScore("怎么获取 Groq 的 API Key", c)))
                .orElseThrow();
        assertThat(best.text()).contains("Groq");
    }
}
