package com.luyublog.aidemo.application.chat;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 后台生成线程池。可续传 SSE 的关键：LLM 生成跑在这里，与任何客户端 SSE 连接的生命周期完全解耦——
 * 客户端断开不会取消生成。
 */
@Configuration
public class ChatGenerationConfig {

    @Bean(name = "chatGenerationExecutor")
    public Executor chatGenerationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(64);
        executor.setThreadNamePrefix("chat-gen-");
        executor.initialize();
        return executor;
    }
}
