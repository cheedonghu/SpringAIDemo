package com.luyublog.aidemo.infrastructure.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 直接对接 OpenAI 兼容 chat completions 接口（vLLM 在 :8000，或真实 OpenAI）。
 *
 * <p>不走 Spring AI {@code OpenAiChatModel}：Spring AI 内部用 {@code RestClient} 发请求，
 * 在 vLLM/FastAPI 这种 Pydantic 严格服务上会触发 {@code 400 "body field required, input: None"}（同 BGE-M3 那次踩的坑）。
 * 跟 {@code BgeM3Client} 用同一种 OkHttp + Jackson 范式，{@code Postman} 验证通过的字节这里也一定能通。
 *
 * <p>两个对外接口：
 * <ul>
 *   <li>{@link #chat}：同步，POST 一次拿完整回答</li>
 *   <li>{@link #chatStream}：SSE 流式，返回 {@code Flux<String>}，每条是一个 token chunk</li>
 * </ul>
 */
@Service
public class VllmChatClient {

    private static final Logger log = LoggerFactory.getLogger(VllmChatClient.class);
    private static final MediaType JSON = MediaType.get("application/json");
    private static final String SSE_DONE = "[DONE]";

    private final OkHttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final double temperature;

    public VllmChatClient(@Value("${app.vllm.base-url:http://localhost:8000}") String baseUrl,
                          @Value("${app.vllm.api-key:dummy}") String apiKey,
                          @Value("${app.vllm.model:Qwen/Qwen2.5-7B-Instruct-AWQ}") String model,
                          @Value("${app.vllm.temperature:0.3}") double temperature,
                          @Value("${app.vllm.timeout-seconds:120}") long timeoutSeconds,
                          @Value("${app.vllm.pool.max-idle:10}") int maxIdleConnections,
                          @Value("${app.vllm.pool.keep-alive-seconds:300}") long keepAliveSeconds) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.temperature = temperature;
        long effective = Math.max(5, timeoutSeconds);
        this.http = new OkHttpClient.Builder()
                .connectionPool(new ConnectionPool(maxIdleConnections, keepAliveSeconds, TimeUnit.SECONDS))
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(effective))
                .writeTimeout(Duration.ofSeconds(effective))
                // 流式接口不能有 callTimeout 限制，否则长生成会被砍掉
                .callTimeout(Duration.ofSeconds(effective))
                .build();
        log.info("VllmChatClient baseUrl={} model={} temperature={} timeout={}s",
                baseUrl, model, temperature, effective);
    }

    /**
     * 同步问答，返回完整回答。
     */
    public String chat(String systemPrompt, String userPrompt) {
        String json = writeJson(buildRequestBody(systemPrompt, userPrompt, false));
        Request req = newRequest(json);
        try (Response resp = this.http.newCall(req).execute()) {
            ResponseBody body = resp.body();
            String text = body != null ? body.string() : "";
            if (!resp.isSuccessful()) {
                throw new IllegalStateException("vllm chat failed " + resp.code() + ": " + text);
            }
            return extractContent(text);
        } catch (IOException ex) {
            throw new IllegalStateException("vllm chat io error: " + ex.getMessage(), ex);
        }
    }

    /**
     * 流式问答，逐 token 推送。
     */
    public Flux<String> chatStream(String systemPrompt, String userPrompt) {
        String json = writeJson(buildRequestBody(systemPrompt, userPrompt, true));
        Request req = newRequest(json);

        return Flux.create(sink -> {
            EventSource.Factory factory = EventSources.createFactory(this.http);
            EventSource source = factory.newEventSource(req, new EventSourceListener() {
                @Override
                public void onEvent(EventSource es, String id, String type, String data) {
                    if (SSE_DONE.equals(data)) {
                        sink.complete();
                        return;
                    }
                    try {
                        JsonNode root = VllmChatClient.this.mapper.readTree(data);
                        JsonNode choices = root.path("choices");
                        if (choices.isArray() && !choices.isEmpty()) {
                            String token = choices.get(0).path("delta").path("content").asText("");
                            if (!token.isEmpty()) {
                                sink.next(token);
                            }
                        }
                    } catch (Exception ex) {
                        log.warn("failed to parse SSE chunk: {}", data, ex);
                    }
                }

                @Override
                public void onClosed(EventSource es) {
                    sink.complete();
                }

                @Override
                public void onFailure(EventSource es, Throwable t, Response response) {
                    String detail = response != null ? "HTTP " + response.code() : "(no response)";
                    Throwable cause = t != null ? t : new IllegalStateException("vllm stream failed: " + detail);
                    sink.error(cause);
                }
            });
            sink.onDispose(source::cancel);
        });
    }

    private Map<String, Object> buildRequestBody(String systemPrompt, String userPrompt, boolean stream) {
        List<Map<String, String>> messages = new ArrayList<>();
        if (StringUtils.hasText(systemPrompt)) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }
        messages.add(Map.of("role", "user", "content", userPrompt == null ? "" : userPrompt));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", this.model);
        body.put("temperature", this.temperature);
        body.put("messages", messages);
        body.put("stream", stream);
        return body;
    }

    private Request newRequest(String json) {
        Request.Builder builder = new Request.Builder()
                .url(this.baseUrl + "/v1/chat/completions")
                .post(RequestBody.create(json, JSON));
        if (StringUtils.hasText(this.apiKey)) {
            builder.header("Authorization", "Bearer " + this.apiKey);
        }
        return builder.build();
    }

    private String writeJson(Object value) {
        try {
            return this.mapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to serialize vllm chat request", ex);
        }
    }

    /**
     * 解 {@code {"choices":[{"message":{"content":"..."}}]}} 拿到生成内容。
     */
    private String extractContent(String json) {
        try {
            JsonNode root = this.mapper.readTree(json);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new IllegalStateException("vllm response has no choices: " + json);
            }
            return choices.get(0).path("message").path("content").asText("");
        } catch (IOException ex) {
            throw new IllegalStateException("failed to parse vllm response: " + json, ex);
        }
    }
}
