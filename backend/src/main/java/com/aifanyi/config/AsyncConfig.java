package com.aifanyi.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 异步线程池。视频翻译耗时长，提交后异步跑，HTTP 立即返回。
 * 两个池分工：
 *  - taskExecutor  翻译流水线（ASR/翻译，重 GPU/网络，2 并发 + 排队，多任务不报错只排队）
 *  - mediaExecutor 烧录/配音（ffmpeg/TTS 型，独立成池——避免翻译占满核心时，
 *    用户点烧录/配音被默默排在翻译后面无响应）
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("aifanyi-task-");
        executor.initialize();
        return executor;
    }

    @Bean("mediaExecutor")
    public Executor mediaExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("aifanyi-media-");
        executor.initialize();
        return executor;
    }
}
