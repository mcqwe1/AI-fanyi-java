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

        @Data
        public static class Groq {
            private String baseUrl;
            private String apiKey;
            private String model;
        }
    }

    @Data
    public static class Llm {
        private String baseUrl;
        private String apiKey;
        private String model;
        /** 推理模型（如 deepseek-v4-flash）关闭思维链以提速；非推理模型设 false 不影响 */
        private boolean disableThinking = true;
        /** 每批翻译行数 */
        private int batchSize = 20;
        /** 并发批次数 */
        private int concurrency = 4;
    }
}
