package com.luyublog.aidemo.infrastructure.cache.redis;

import com.luyublog.aidemo.domain.conversation.StreamEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.Subscription;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 可续传 SSE 的 token 缓冲，基于 Redis Stream。Redis SDK 的细节（{@link MapRecord}、{@link Subscription}、
 * entry 字段编码）只在本类出现；对上层只暴露中立的 {@link StreamEvent} 与 {@link AutoCloseable}。
 *
 * <p>生成端（{@code ChatStreamService} 后台任务）把每个 token {@link #append} 进去，结束时
 * {@link #markDone}/{@link #markError}；消费端用 {@link #subscribe} 从指定 offset 订阅，既回放 backlog
 * 又 tail 新 entry。entry id 单调，直接当 SSE {@code id:}，配合浏览器 {@code Last-Event-ID} 实现断点续传。
 *
 * <p>entry 字段约定：token entry 带字段 {@code c}（内容）；控制 entry 带字段 {@code event}
 * （值 {@code done}/{@code error}）。
 */
@Component
public class RedisTokenStreamStore {

    private static final String FIELD_TOKEN = "c";
    private static final String FIELD_EVENT = "event";
    private static final String EVENT_DONE = "done";
    private static final String EVENT_ERROR = "error";

    /**
     * 从头读：覆盖 POST 与 GET 之间已缓冲的 token（首连无 Last-Event-ID 时用）。
     */
    private static final String OFFSET_BEGINNING = "0";

    private final StringRedisTemplate redis;
    private final StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;
    private final String keyPrefix;
    private final Duration ttl;

    public RedisTokenStreamStore(
            StringRedisTemplate redis,
            StreamMessageListenerContainer<String, MapRecord<String, String, String>> container,
            @Value("${app.chat.stream.key-prefix:rag:stream:}") String keyPrefix,
            @Value("${app.chat.stream.ttl-seconds:300}") long ttlSeconds) {
        this.redis = redis;
        this.container = container;
        this.keyPrefix = keyPrefix;
        this.ttl = Duration.ofSeconds(Math.max(60, ttlSeconds));
    }

    public String key(String messageId) {
        return this.keyPrefix + messageId;
    }

    /**
     * 追加一个 token entry，返回其 stream entry id（可作 SSE id）。
     */
    public String append(String messageId, String token) {
        var id = this.redis.opsForStream().add(key(messageId), Map.of(FIELD_TOKEN, token));
        return id == null ? null : id.getValue();
    }

    /**
     * 写入完成标记并设过期。
     */
    public void markDone(String messageId) {
        this.redis.opsForStream().add(key(messageId), Map.of(FIELD_EVENT, EVENT_DONE));
        this.redis.expire(key(messageId), this.ttl);
    }

    /**
     * 写入错误标记并设过期。
     */
    public void markError(String messageId) {
        this.redis.opsForStream().add(key(messageId), Map.of(FIELD_EVENT, EVENT_ERROR));
        this.redis.expire(key(messageId), this.ttl);
    }

    public boolean exists(String messageId) {
        return Boolean.TRUE.equals(this.redis.hasKey(key(messageId)));
    }

    /**
     * 订阅该消息的 stream，每条 entry 翻译成 {@link StreamEvent} 推给 {@code handler}。
     * {@code lastEventId} 为空表示首连（从头回放）；非空表示从该断点之后续读。
     * 返回 {@link AutoCloseable}，连接结束时 {@code close()} 即注销订阅，务必调用以免泄漏。
     */
    public AutoCloseable subscribe(String messageId, String lastEventId, Consumer<StreamEvent> handler) {
        ReadOffset readOffset = ReadOffset.from(StringUtils.hasText(lastEventId) ? lastEventId : OFFSET_BEGINNING);
        StreamOffset<String> offset = StreamOffset.create(key(messageId), readOffset);
        Subscription subscription = this.container.receive(offset, record -> handler.accept(toEvent(record)));
        return () -> this.container.remove(subscription);
    }

    /**
     * Redis entry → 中立事件。Redis 字段编码只此一处解读。
     */
    private static StreamEvent toEvent(MapRecord<String, String, String> record) {
        String id = record.getId().getValue();
        Map<String, String> value = record.getValue();
        String event = value.get(FIELD_EVENT);
        if (EVENT_DONE.equals(event)) {
            return StreamEvent.done(id);
        }
        if (EVENT_ERROR.equals(event)) {
            return StreamEvent.error(id, "generation failed");
        }
        return StreamEvent.token(id, value.get(FIELD_TOKEN));
    }
}
