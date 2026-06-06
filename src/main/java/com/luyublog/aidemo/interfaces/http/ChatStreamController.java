package com.luyublog.aidemo.interfaces.http;

import com.luyublog.aidemo.application.chat.ChatStreamService;
import com.luyublog.aidemo.application.chat.MessageNotFoundException;
import com.luyublog.aidemo.domain.conversation.StreamEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * 可续传 RAG 流式问答入口。
 *
 * <ul>
 *   <li>{@code POST /ai/rag/messages}：{@code {question, topK}} → {@code {conversationId, messageId}}，
 *       后台启动生成</li>
 *   <li>{@code GET /ai/rag/messages/{id}/stream}：SSE 拉流，支持 {@code Last-Event-ID} 断点续传</li>
 * </ul>
 *
 * <p>本类只负责协议适配:把 application 推来的中立 {@link StreamEvent} 翻译成 SSE 帧、管理
 * {@link SseEmitter} 生命周期。offset 决策、订阅、DONE/ERROR 编排、TTL 回落等业务逻辑都在
 * {@link ChatStreamService};Redis 细节不出 infrastructure。
 */
@RestController
public class ChatStreamController {

    private final ChatStreamService chatStreamService;
    private final long emitterTimeoutMs;

    public ChatStreamController(ChatStreamService chatStreamService,
                                @Value("${app.chat.stream.emitter-timeout-ms:300000}") long emitterTimeoutMs) {
        this.chatStreamService = chatStreamService;
        this.emitterTimeoutMs = emitterTimeoutMs;
    }

    @PostMapping("/ai/rag/messages")
    public ChatStreamService.StartResult create(@RequestBody AskRequest request) {
        if (request == null || !StringUtils.hasText(request.question())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question is required");
        }
        int topK = request.topK() != null && request.topK() > 0 ? request.topK() : 5;
        return this.chatStreamService.start(request.question(), topK);
    }

    @GetMapping(value = "/ai/rag/messages/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable("id") String messageId,
                             @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        SseEmitter emitter = new SseEmitter(this.emitterTimeoutMs > 0 ? this.emitterTimeoutMs : Long.MAX_VALUE);

        AutoCloseable handle;
        try {
            handle = this.chatStreamService.openStream(messageId, lastEventId, event -> emit(emitter, event));
        } catch (MessageNotFoundException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        }

        Runnable cleanup = () -> closeQuietly(handle);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(t -> cleanup.run());
        return emitter;
    }

    /**
     * 中立事件 → SSE 帧。send 抛 IOException(客户端已断开)时,抛 {@link UncheckedIOException}
     * 让上游 {@code ChatStreamService} 立即停止订阅;后台生成不受影响,稍后可凭 Last-Event-ID 续连。
     */
    private void emit(SseEmitter emitter, StreamEvent event) {
        try {
            switch (event.type()) {
                case TOKEN -> {
                    if (event.data() != null) {
                        emitter.send(withId(SseEmitter.event().data(event.data()), event.id()));
                    }
                }
                case DONE -> {
                    emitter.send(withId(SseEmitter.event().name("done").data(""), event.id()));
                    emitter.complete();
                }
                case ERROR -> {
                    emitter.send(SseEmitter.event().name("error").data(event.data() == null ? "" : event.data()));
                    emitter.complete();
                }
            }
        } catch (IOException ex) {
            emitter.completeWithError(ex);
            throw new UncheckedIOException(ex);
        }
    }

    private static SseEmitter.SseEventBuilder withId(SseEmitter.SseEventBuilder builder, String id) {
        return id != null ? builder.id(id) : builder;
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

    /**
     * {@code POST /ai/rag/messages} 请求体。
     */
    public record AskRequest(String question, Integer topK) {
    }
}
