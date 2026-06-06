package com.luyublog.aidemo.application.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyublog.aidemo.application.rag.RagService;
import com.luyublog.aidemo.domain.conversation.Conversation;
import com.luyublog.aidemo.domain.conversation.Message;
import com.luyublog.aidemo.domain.conversation.MessageStatus;
import com.luyublog.aidemo.domain.conversation.StreamEvent;
import com.luyublog.aidemo.domain.retrieval.RetrievedDoc;
import com.luyublog.aidemo.infrastructure.cache.redis.RedisTokenStreamStore;
import com.luyublog.aidemo.infrastructure.persistence.mysql.ConversationMapper;
import com.luyublog.aidemo.infrastructure.persistence.mysql.MessageMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 可续传 RAG 流式问答的编排。
 *
 * <p>核心是把"生成"与"客户端传输"解耦：{@link #start} 落库消息后，把 LLM 生成提交到
 * {@code chatGenerationExecutor} 后台跑，token 逐个写入 {@link RedisTokenStreamStore}（Redis Stream）。
 * SSE 控制器只是这条 Redis Stream 的消费者，断开/重连都不影响后台生成。
 */
@Service
public class ChatStreamService {

    private static final Logger log = LoggerFactory.getLogger(ChatStreamService.class);
    private static final int TITLE_MAX = 50;

    private final RagService ragService;
    private final RedisTokenStreamStore streamStore;
    private final MessageMapper messageMapper;
    private final ConversationMapper conversationMapper;
    private final Executor executor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ChatStreamService(RagService ragService,
                             RedisTokenStreamStore streamStore,
                             MessageMapper messageMapper,
                             ConversationMapper conversationMapper,
                             @Qualifier("chatGenerationExecutor") Executor executor) {
        this.ragService = ragService;
        this.streamStore = streamStore;
        this.messageMapper = messageMapper;
        this.conversationMapper = conversationMapper;
        this.executor = executor;
    }

    /**
     * 创建会话与消息行，后台启动生成，立即返回标识。客户端随后用
     * {@code GET /ai/rag/messages/{messageId}/stream} 拉流（可断点续传）。
     */
    public StartResult start(String question, int topK) {
        LocalDateTime now = LocalDateTime.now();
        String conversationId = UUID.randomUUID().toString();
        String userMessageId = UUID.randomUUID().toString();
        String assistantMessageId = UUID.randomUUID().toString();

        this.conversationMapper.insert(new Conversation(conversationId, title(question), now, now));
        this.messageMapper.insert(new Message(
                userMessageId, conversationId, "user", question, MessageStatus.DONE, null, null, now, now));
        this.messageMapper.insert(new Message(
                assistantMessageId, conversationId, "assistant", "", MessageStatus.GENERATING, null, null, now, now));

        this.executor.execute(() -> generate(assistantMessageId, question, topK));
        return new StartResult(conversationId, assistantMessageId);
    }

    /**
     * 后台生成：检索 → 流式生成 → 每 token 写 Redis Stream；完成/失败回写消息行。
     * 在后台线程上同步消费 Flux（{@code toIterable} 阻塞），与客户端连接无关。
     */
    private void generate(String messageId, String question, int topK) {
        StringBuilder full = new StringBuilder();
        int tokenCount = 0;
        String sourcesJson = null;
        try {
            List<RetrievedDoc> docs = this.ragService.retrieve(question, topK);
            sourcesJson = writeSources(docs);

            for (String token : this.ragService.streamAnswer(question, docs).toIterable()) {
                full.append(token);
                tokenCount++;
                this.streamStore.append(messageId, token);
            }

            this.streamStore.markDone(messageId);
            this.messageMapper.updateOutcome(
                    messageId, MessageStatus.DONE, full.toString(), sourcesJson, tokenCount, LocalDateTime.now());
            log.debug("[chat] generation done messageId={} tokens={}", messageId, tokenCount);
        } catch (Exception ex) {
            log.warn("[chat] generation failed messageId={}: {}", messageId, ex.getMessage(), ex);
            this.streamStore.markError(messageId);
            this.messageMapper.updateOutcome(
                    messageId, MessageStatus.FAILED, full.toString(), sourcesJson, tokenCount, LocalDateTime.now());
        }
    }

    /**
     * 打开一路可续传的事件流，把中立 {@link StreamEvent} 推给 {@code sink}。
     *
     * <p>Redis 流还在 → 从 offset 订阅(首连从头、重连从 {@code lastEventId} 之后)，DONE/ERROR 即停；
     * Redis 流已过期 → 从 MySQL 取落库结果一次性回放。返回 {@link AutoCloseable}，调用方(SSE 控制器)
     * 在连接结束的所有路径上 {@code close()} 以注销底层订阅。
     *
     * @throws MessageNotFoundException Redis 与 DB 都查无此消息
     */
    public AutoCloseable openStream(String messageId, String lastEventId, Consumer<StreamEvent> sink) {
        if (this.streamStore.exists(messageId)) {
            return tail(messageId, lastEventId, sink);
        }
        replayFromDb(messageId, sink);
        return () -> {
        };
    }

    /**
     * 为什么要用AtomicBoolean和AtomicReference这种Atomic类型？
     * 这里有两个线程:
     * Redis 监听容器的轮询线程、SSE 的 MVC 异步线程(Tomcat)
     * finished 和 handleRef 都被这两个线程同时读写,所以才需要 Atomic
     *
     * event -> {
     *     if (finished.get()) return;        // 轮询线程读
     *     ...
     *     finished.set(true);                // 轮询线程写(DONE/失败时)
     * }
     * return () -> { finished.set(true); ... };  // MVC 线程写(连接结束时)
     *
     * finished 是"是否还要继续投递"的开关。一个线程把它置 true,另一个线程必须立刻看见,否则:连接已断、MVC 线程已 set(true),但轮询线程因为没有内存可见性保证,还在 get() 到旧的 false,继续往死掉的 emitter 投递。
     * AtomicBoolean 提供了类似 volatile 的可见性(happens-before),保证一个线程的写对另一个线程立即可见。普通 boolean 局部变量没有这个保证。
     *
     * 这里其实只用了 get/set、没用 compareAndSet,所以语义上一个 volatile boolean 就够。但局部变量不能加 volatile(只有字段能),而 lambda 又只能捕获局部变量 —— 见下一点。AtomicBoolean 正好既是"可被捕获的持有者",又自带 volatile 级可见性。
     *
     */
    /**
     * Redis 流在:订阅并转推;遇 DONE/ERROR 或 sink 失败(客户端断开)即停止订阅,后台生成不受影响。
     */
    private AutoCloseable tail(String messageId, String lastEventId, Consumer<StreamEvent> sink) {
        AtomicBoolean finished = new AtomicBoolean(false);
        AtomicReference<AutoCloseable> handleRef = new AtomicReference<>();

        AutoCloseable handle = this.streamStore.subscribe(messageId, lastEventId, event -> {
            if (finished.get()) {
                return;
            }
            try {
                sink.accept(event);
            } catch (RuntimeException ex) {
                finished.set(true);
                closeQuietly(handleRef.get());
                return;
            }
            if (event.type() != StreamEvent.Type.TOKEN) {
                finished.set(true);
                closeQuietly(handleRef.get());
            }
        });
        handleRef.set(handle);
        return () -> {
            finished.set(true);
            closeQuietly(handle);
        };
    }

    /**
     * Redis 流已过期(超 TTL)的兜底:从 MySQL 取落库全文一次性回放。
     */
    private void replayFromDb(String messageId, Consumer<StreamEvent> sink) {
        Message msg = this.messageMapper.findById(messageId);
        if (msg == null) {
            throw new MessageNotFoundException(messageId);
        }
        switch (msg.status()) {
            case DONE -> {
                if (StringUtils.hasText(msg.content())) {
                    sink.accept(StreamEvent.token(null, msg.content()));
                }
                sink.accept(StreamEvent.done(null));
            }
            case FAILED -> sink.accept(StreamEvent.error(null, "generation failed"));
            // GENERATING 但 Redis 流不在(TTL 过短/Redis 重启):无法续读,提示重试
            case GENERATING -> sink.accept(StreamEvent.error(null, "stream expired, please retry"));
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // 注销订阅失败无需上抛
        }
    }

    private String writeSources(List<RetrievedDoc> docs) {
        try {
            return this.objectMapper.writeValueAsString(docs);
        } catch (Exception ex) {
            log.warn("[chat] serialize sources failed: {}", ex.getMessage());
            return null;
        }
    }

    private static String title(String question) {
        if (question == null || question.isBlank()) {
            return "新会话";
        }
        String trimmed = question.strip();
        return trimmed.length() <= TITLE_MAX ? trimmed : trimmed.substring(0, TITLE_MAX);
    }

    /**
     * {@link #start} 的返回:会话 id + 待拉流的 assistant 消息 id。
     */
    public record StartResult(String conversationId, String messageId) {
    }
}
