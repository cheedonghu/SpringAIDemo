package com.luyublog.aidemo.infrastructure.embedding;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyublog.aidemo.domain.embedding.EmbedResult;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * BGE-M3 编码服务的 HTTP 客户端，对接 FastAPI on {@code app.bgem3.base-url}（默认 http://localhost:8002）。
 *
 * <p>HTTP 走 OkHttp：
 * <ul>
 *   <li>{@link OkHttpClient} 单例，自带 {@link ConnectionPool}（默认 5 idle / 5 分钟 keepalive，本类按
 *       配置开放可调，避免每次请求新建 TCP）</li>
 *   <li>请求体直接是序列化好的 JSON 字符串，不经过 Spring MessageConverter 选型，行为可预测</li>
 * </ul>
 */
@Service
public class BgeM3Client {

    private static final Logger log = LoggerFactory.getLogger(BgeM3Client.class);
    private static final MediaType JSON = MediaType.get("application/json");
    private static final int DEFAULT_BATCH_SIZE = 12;

    private final OkHttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    private final int defaultBatchSize;

    public BgeM3Client(@Value("${app.bgem3.base-url:http://localhost:8002}") String baseUrl,
                       @Value("${app.bgem3.batch-size:12}") int defaultBatchSize,
                       @Value("${app.bgem3.pool.max-idle:20}") int maxIdleConnections,
                       @Value("${app.bgem3.pool.keep-alive-seconds:300}") long keepAliveSeconds,
                       @Value("${app.bgem3.timeout-seconds:30}") long timeoutSeconds) {
        this.baseUrl = baseUrl;
        this.defaultBatchSize = defaultBatchSize > 0 ? defaultBatchSize : DEFAULT_BATCH_SIZE;
        this.http = new OkHttpClient.Builder()
                .connectionPool(new ConnectionPool(maxIdleConnections, keepAliveSeconds, TimeUnit.SECONDS))
                .connectTimeout(Duration.ofSeconds(Math.max(5, timeoutSeconds)))
                .readTimeout(Duration.ofSeconds(Math.max(5, timeoutSeconds)))
                .writeTimeout(Duration.ofSeconds(Math.max(5, timeoutSeconds)))
                .callTimeout(Duration.ofSeconds(Math.max(5, timeoutSeconds)))
                .build();
        log.info("BgeM3Client baseUrl={} defaultBatchSize={} pool(maxIdle={}, keepAlive={}s) timeout={}s",
                baseUrl, this.defaultBatchSize, maxIdleConnections, keepAliveSeconds, timeoutSeconds);
    }

    /**
     * 单条编码（查询路径）。返回 dense + sparse。
     */
    public EmbedResult embed(String text) {
        EncodeResponse response = postJson(
                "/encode",
                Map.of("text", text == null ? "" : text),
                EncodeResponse.class);
        return toEmbedResult(response);
    }

    /**
     * 批量编码（灌库路径），效率高于循环调单条。
     */
    public List<EmbedResult> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        EncodeBatchResponse response = postJson(
                "/encode_batch",
                Map.of("texts", texts, "batch_size", this.defaultBatchSize),
                EncodeBatchResponse.class);
        if (response == null || response.results() == null) {
            return List.of();
        }
        List<EmbedResult> out = new ArrayList<>(response.results().size());
        for (EncodeResponse r : response.results()) {
            out.add(toEmbedResult(r));
        }
        return out;
    }

    private <T> T postJson(String path, Object body, Class<T> responseType) {
        String json;
        try {
            json = this.mapper.writeValueAsString(body);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("bge-m3 serialize body failed", ex);
        }

        Request req = new Request.Builder()
                .url(this.baseUrl + path)
                .post(RequestBody.create(json, JSON))
                .build();

        try (Response resp = this.http.newCall(req).execute()) {
            ResponseBody respBody = resp.body();
            String bodyText = respBody != null ? respBody.string() : "";
            if (!resp.isSuccessful()) {
                throw new IllegalStateException("bge-m3 " + path + " failed " + resp.code() + ": " + bodyText);
            }
            return this.mapper.readValue(bodyText, responseType);
        } catch (IOException ex) {
            throw new IllegalStateException("bge-m3 " + path + " io error: " + ex.getMessage(), ex);
        }
    }

    private EmbedResult toEmbedResult(EncodeResponse response) {
        if (response == null) {
            return new EmbedResult(new float[0], Map.of());
        }
        return new EmbedResult(
                response.dense() != null ? response.dense() : new float[0],
                toSparseMap(response.sparse()));
    }

    /**
     * JSON 里 sparse 的 key 是字符串（如 "128"），转成 Long 与 Qdrant 的 indices 对齐。
     */
    private Map<Long, Float> toSparseMap(Map<String, Float> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<Long, Float> sparse = new LinkedHashMap<>(raw.size());
        for (Map.Entry<String, Float> e : raw.entrySet()) {
            try {
                sparse.put(Long.parseLong(e.getKey()), e.getValue());
            } catch (NumberFormatException ex) {
                log.warn("skip non-numeric sparse key: {}", e.getKey());
            }
        }
        return sparse;
    }

    // ---- 响应 DTO ----

    record EncodeResponse(float[] dense, Map<String, Float> sparse) {
    }

    record EncodeBatchResponse(List<EncodeResponse> results) {
    }
}
