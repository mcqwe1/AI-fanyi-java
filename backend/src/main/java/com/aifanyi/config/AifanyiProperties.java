package com.aifanyi.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 应用自定义配置，对应 application.yml 中 aifanyi.* 前缀。
 */
@Data
@Component
@ConfigurationProperties(prefix = "aifanyi")
public class AifanyiProperties {

    private Jwt jwt = new Jwt();
    private Storage storage = new Storage();
    private Ffmpeg ffmpeg = new Ffmpeg();
    private Asr asr = new Asr();
    private Llm llm = new Llm();
    private Tts tts = new Tts();
    private Agent agent = new Agent();

    @Data
    public static class Jwt {
        private String secret;
        private long expireMinutes = 1440;
    }

    @Data
    public static class Storage {
        /** 文件存储根目录 */
        private String root;
    }

    @Data
    public static class Ffmpeg {
        private String path = "ffmpeg";
    }

    @Data
    public static class Asr {
        private Groq groq = new Groq();
        private Local local = new Local();

        @Data
        public static class Groq {
            private String baseUrl;
            private String model;
            /** 单文件上传字节上限，超过则自动分块转写。默认 20MB，安全低于 Groq 免费档 25MB。 */
            private long maxChunkBytes = 20L * 1024 * 1024;
        }

        @Data
        public static class Local {
            /** 本地 ai-service（faster-whisper）地址 */
            private String baseUrl = "http://localhost:8001";
        }
    }

    @Data
    public static class Llm {
        /** 推理模型（如 deepseek-v4-flash）关闭思维链以提速；非推理模型设 false 不影响 */
        private boolean disableThinking = true;
        /** 每批翻译行数（越大请求数越少；index 对齐保证大批次也能精确回填） */
        private int batchSize = 40;
        /** 并发批次数 */
        private int concurrency = 8;
    }

    @Data
    public static class Tts {
        /** 配音逐行合成的并发数（edge-tts / OpenAI 兼容端点都能承受 6 路左右） */
        private int concurrency = 6;
    }

    /** Agent 模式（实验功能）的 harness 约束参数。全部可用环境变量覆盖，便于现场调优。 */
    @Data
    public static class Agent {
        /** 子 Agent 私有池线程数。建议 = maxSubAgents × 2（taskExecutor 允许 2 个任务并发） */
        private int concurrency = 8;
        /** 单任务最多实例化几个子 Agent。档案表无上限，但每个实例=2次LLM+2次搜索，必须封顶 */
        private int maxSubAgents = 4;
        /** 单个子 Agent 墙钟上限（秒） */
        private int subAgentTimeoutSec = 30;
        /** 整个扇出阶段的全局 deadline（秒），优先于单体上限 */
        private int fanoutDeadlineSec = 90;
        /** 场景推测超时（秒） */
        private int sceneTimeoutSec = 20;
        /** 单个子 Agent 最多搜几次（架构要求 ≤2） */
        private int maxSearchPerAgent = 2;
        /** 单个子 Agent 最多几次外部工具调用（架构要求 ≤3，多出的额度留作失败重试） */
        private int maxToolsPerAgent = 3;
        /** 喂给子 Agent 的摘要字符上限（全文出现次数由代码统计，不靠摘要） */
        private int digestChars = 6000;
        /** ⑧ 滚动窗口分组大小：组内串行带完整上下文，组间并发。1=全串行 */
        private int translateGroupSize = 5;
        /** ⑧ 上下文重叠句数（前 N 句原文） */
        private int contextOverlap = 4;
        /** Qdrant 地址（用户设置留空时的默认值） */
        private String qdrantUrl = "http://localhost:6333";
        /** 向量集合名 */
        private String qdrantCollection = "aifanyi_terms";
    }
}
