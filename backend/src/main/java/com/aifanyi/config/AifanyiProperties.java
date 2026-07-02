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
}
