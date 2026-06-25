package com.luyublog.aidemo.interfaces.http;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    /**
     * SSE 线格式(每个 token 一个精简 JSON chunk,只含 {@code content} 与 {@code finish_reason};流以裸 sentinel 收尾):
     * <pre>
     *   id: 1782381155-0
     *   data: {"content":"今天","finish_reason":null}
     *
     *   id: 1782381157-0
     *   data: {"content":"","finish_reason":"stop"}
     *
     *   data: [DONE]          ← 正常结束(裸 sentinel,与 OpenAI 一致)
     * </pre>
     * 异常用 {@code data: [ERROR]} 收尾,客户端把 {@code [DONE]}/{@code [ERROR]} 都当流终止标记。
     * {@code id:} 保留是为浏览器 {@code Last-Event-ID} 断点续传;它与 {@code data:} 并存是合法 SSE。
     * 完成前先发一个 {@code finish_reason=stop} 的 chunk,再发 {@code [DONE]}。
     */
    private static final String DONE_SENTINEL = "[DONE]";
    private static final String ERROR_SENTINEL = "[ERROR]";

    private final ChatStreamService chatStreamService;
    private final long emitterTimeoutMs;
    private final ObjectMapper objectMapper = new ObjectMapper();

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

    @GetMapping(value = "/ai/rag/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam("id") String messageId,
                             @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        SseEmitter emitter = new SseEmitter(this.emitterTimeoutMs > 0 ? this.emitterTimeoutMs : Long.MAX_VALUE);

        AutoCloseable handle;
        try {
            handle = this.chatStreamService.openStream(messageId, lastEventId, event -> emit(emitter, event));
        } catch (MessageNotFoundException ex) {
            throw ex;
        }

        emitter.onCompletion(() -> closeQuietly(handle));
        emitter.onError(t -> closeQuietly(handle));
        // 超时:生产者可能已崩溃、永远不会写 done(流卡在最后一条 token)。补发一条 terminal error 让前端
        // 明确收尾、能区分"正常结束"与"异常";可续传重连让较短的 emitter-timeout 无损。
        emitter.onTimeout(() -> {
            try {
                emitter.send(frame(null, ERROR_SENTINEL));
            } catch (Exception ignored) {
                // 客户端已断开,忽略
            }
            emitter.complete();
            closeQuietly(handle);
        });
        return emitter;
    }

    /**
     * 中立事件 → SSE 帧(OpenAI chat.completion.chunk)。send 抛 IOException(客户端已断开)时,抛
     * {@link UncheckedIOException} 让上游 {@code ChatStreamService} 立即停止订阅;后台生成不受影响,稍后可续连。
     */
    private void emit(SseEmitter emitter, StreamEvent event) {
        try {
            switch (event.type()) {
                case TOKEN -> {
                    if (event.data() != null) {
                        emitter.send(frame(event.id(), chunkJson(event.data(), null)));
                    }
                }
                case DONE -> {
                    // 先发 finish_reason=stop 的 chunk,再发 [DONE] sentinel(OpenAI 收尾两步)
                    emitter.send(frame(event.id(), chunkJson("", "stop")));
                    emitter.send(frame(null, DONE_SENTINEL));
                    emitter.complete();
                }
                case ERROR -> {
                    emitter.send(frame(event.id(), ERROR_SENTINEL));
                    emitter.complete();
                }
            }
        } catch (IOException ex) {
            emitter.completeWithError(ex);
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * 拼一个精简 JSON chunk:{@code {"content":..., "finish_reason":...}}。token 时 content 为片段、
     * finishReason 为 null;收尾时 content 为空串、finishReason 为 {@code "stop"}。两字段始终保留。
     */
    private String chunkJson(String content, String finishReason) {
        try {
            return this.objectMapper.writeValueAsString(new Chunk(content, finishReason));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("serialize chunk failed", ex);
        }
    }

    /**
     * 拼一帧标准 SSE:{@code id:} 在前、{@code data:} 在后,冒号后各留一个空格(贴合 OpenAI 风格)。
     * Spring 的 {@code SseEventBuilder} 默认输出 {@code data:今天}(无空格、且按调用顺序);SSE 规范规定
     * 解析器会去掉冒号后的第一个空格,故这里在值前补一个空格 → 线上呈现 {@code data: 今天},客户端解析到的值不变。
     */
    private static SseEmitter.SseEventBuilder frame(String id, String data) {
        SseEmitter.SseEventBuilder builder = SseEmitter.event();
        if (id != null) {
            builder.id(" " + id);
        }
        return builder.data(" " + data);
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

    /**
     * 精简流式 chunk 线格式:{@code {"content":"今天","finish_reason":null}}。
     */
    private record Chunk(String content, @JsonProperty("finish_reason") String finishReason) {
    }
}
