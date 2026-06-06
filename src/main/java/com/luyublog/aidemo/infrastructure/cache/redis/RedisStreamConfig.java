package com.luyublog.aidemo.infrastructure.cache.redis;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamMessageListenerContainerOptions;

import java.time.Duration;

/**
 * Redis Stream 监听容器配置。可续传 SSE 的 token tail 走它：
 * 每个 SSE 连接用 {@code container.receive(offset, listener)} 注册一路独立读取（无 consumer group，
 * 等价 XREAD），既能从指定 offset 回放 backlog，又能持续 tail 新 entry。
 */
@Configuration
public class RedisStreamConfig {

    /**
     * 容器用 String 序列化，回调拿到的就是 {@code MapRecord<String, String, String>}。
     * pollTimeout 控制底层 XREAD BLOCK 时长。
     */
    @Bean(destroyMethod = "stop")
    public StreamMessageListenerContainer<String, MapRecord<String, String, String>> streamMessageListenerContainer(
            RedisConnectionFactory connectionFactory) {
        StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options =
                StreamMessageListenerContainerOptions.builder()
                        .pollTimeout(Duration.ofSeconds(2))
                        .batchSize(64)
                        .build();
        StreamMessageListenerContainer<String, MapRecord<String, String, String>> container =
                StreamMessageListenerContainer.create(connectionFactory, options);
        container.start();
        return container;
    }
}
