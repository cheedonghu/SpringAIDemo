package com.luyublog.aidemo.infrastructure.vectorstore.qdrant;

import com.luyublog.aidemo.domain.embedding.EmbedResult;
import com.luyublog.aidemo.domain.retrieval.HybridPoint;
import com.luyublog.aidemo.domain.retrieval.RetrievedDoc;
import io.qdrant.client.*;
import io.qdrant.client.grpc.Collections.*;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points.*;
import io.qdrant.client.grpc.Points.Vector;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 直接对接 Qdrant 原生 gRPC API（{@link QdrantClient}），实现双向量（dense + sparse）混合检索。
 *
 * <p>刻意不复用 Spring AI 的 {@code QdrantVectorStore}：那个抽象只暴露 dense，sparse 无处可放，
 * 也无法表达 Query API 的 prefetch + RRF fusion 语义。
 *
 * <p>建表参数针对 1GB 内存机器调过：
 * <ul>
 *   <li>dense {@code VectorParams.on_disk=true}（向量 mmap）</li>
 *   <li>dense {@code HnswConfigDiff.on_disk=true}（索引图 mmap）</li>
 *   <li>dense {@code HnswConfigDiff.m=app.qdrant.hnsw-m}（默认 16，紧张时降到 8）</li>
 *   <li>sparse {@code SparseIndexConfig.on_disk=true}（倒排 mmap）</li>
 * </ul>
 * 这样内存只留运行时缓冲，1G 才扛得住。
 *
 * <p>注意命名冲突：Qdrant 的 payload value 类型 {@code JsonWithInt.Value} 不要 import 为 {@code Value}，
 * 否则会和 Spring 的 {@code @Value} 注解撞名。本文件全程用 {@code JsonWithInt.Value} 全限定引用。
 */
@Service
public class QdrantHybridStore {

    private static final Logger log = LoggerFactory.getLogger(QdrantHybridStore.class);

    private final QdrantClient client;
    private final String collectionName;
    private final String denseVectorName;
    private final String sparseVectorName;
    private final int denseDim;
    private final int hnswM;
    private final boolean initializeSchema;
    private final Duration callTimeout;

    public QdrantHybridStore(QdrantClient client,
                             @Value("${app.qdrant.collection:rag_docs}") String collectionName,
                             @Value("${app.qdrant.dense-name:dense}") String denseVectorName,
                             @Value("${app.qdrant.sparse-name:sparse}") String sparseVectorName,
                             @Value("${app.qdrant.dense-dim:1024}") int denseDim,
                             @Value("${app.qdrant.hnsw-m:16}") int hnswM,
                             @Value("${app.qdrant.initialize-schema:false}") boolean initializeSchema,
                             @Value("${app.qdrant.call-timeout-seconds:30}") long callTimeoutSeconds) {
        this.client = client;
        this.collectionName = collectionName;
        this.denseVectorName = denseVectorName;
        this.sparseVectorName = sparseVectorName;
        this.denseDim = denseDim;
        this.hnswM = hnswM;
        this.initializeSchema = initializeSchema;
        this.callTimeout = Duration.ofSeconds(Math.max(5, callTimeoutSeconds));
    }

    @PostConstruct
    public void init() {
        if (!this.initializeSchema) {
            log.info("app.qdrant.initialize-schema=false, skip collection ensure");
            return;
        }
        try {
            ensureCollection();
        } catch (Exception ex) {
            // 不阻塞 Spring 启动：Qdrant 可能尚未就绪；调用 upsert/query 时再失败给出明确错误
            log.warn("ensureCollection failed at startup ({}). Will retry on first use.", ex.getMessage());
        }
    }

    /**
     * 幂等地建 collection：已存在则跳过；不存在则按 1GB 内存约束建表（全部 on_disk）。
     */
    public void ensureCollection() {
        try {
            Boolean exists = this.client.collectionExistsAsync(this.collectionName)
                    .get(this.callTimeout.toSeconds(), TimeUnit.SECONDS);
            if (Boolean.TRUE.equals(exists)) {
                log.info("collection '{}' already exists, skip create", this.collectionName);
                return;
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while checking collection existence", ie);
        } catch (ExecutionException | TimeoutException ex) {
            throw new IllegalStateException("failed to check collection existence: " + ex.getMessage(), ex);
        }

        VectorParams denseParams = VectorParams.newBuilder()
                .setSize(this.denseDim)
                .setDistance(Distance.Cosine)
                // 向量本体 mmap 到磁盘，不占进程堆
                .setOnDisk(true)
                .setHnswConfig(HnswConfigDiff.newBuilder()
                        .setM(this.hnswM)
                        // HNSW 图也走 mmap
                        .setOnDisk(true)
                        .build())
                .build();

        SparseVectorParams sparseParams = SparseVectorParams.newBuilder()
                .setIndex(SparseIndexConfig.newBuilder()
                        // 稀疏倒排同样 mmap
                        .setOnDisk(true)
                        .build())
                .build();

        CreateCollection request = CreateCollection.newBuilder()
                .setCollectionName(this.collectionName)
                .setVectorsConfig(VectorsConfig.newBuilder()
                        .setParamsMap(VectorParamsMap.newBuilder()
                                .putMap(this.denseVectorName, denseParams)
                                .build())
                        .build())
                .setSparseVectorsConfig(SparseVectorConfig.newBuilder()
                        .putMap(this.sparseVectorName, sparseParams)
                        .build())
                .build();

        log.info("creating collection '{}' dense({}D, cosine, on_disk, hnsw.m={}) + sparse(on_disk)",
                this.collectionName, this.denseDim, this.hnswM);
        awaitCall(this.client.createCollectionAsync(request), "createCollection");
    }

    /**
     * 批量 upsert。每个 {@link HybridPoint} 同时写入 dense 与 sparse 命名向量 + payload。
     *
     * @return upsert 写入的 point 数
     */
    public int upsertBatch(List<HybridPoint> points) {
        if (points == null || points.isEmpty()) {
            return 0;
        }
        List<PointStruct> pointStructs = new ArrayList<>(points.size());
        for (HybridPoint hp : points) {
            pointStructs.add(toPointStruct(hp));
        }
        log.info("upserting {} points into '{}'", pointStructs.size(), this.collectionName);
        awaitCall(this.client.upsertAsync(this.collectionName, pointStructs), "upsert");
        return pointStructs.size();
    }

    /**
     * 混合检索：dense + sparse 各跑一路 prefetch，服务端 RRF 融合后返回 TopK。
     *
     * @param query         已经过 BGE-M3 编码的查询向量
     * @param topK          最终返回数量
     * @param prefetchLimit 每路 prefetch 的候选池大小，建议 topK * 4 ~ 10
     */
    public List<RetrievedDoc> hybridQuery(EmbedResult query, int topK, int prefetchLimit) {
        if (query == null || query.dense() == null || query.dense().length == 0) {
            throw new IllegalArgumentException("query embedding is empty");
        }
        int effectivePrefetch = Math.max(prefetchLimit, topK);

        List<Float> denseValues = toFloatList(query.dense());
        SparseValues sparse = toSparseValues(query.sparse());

        PrefetchQuery densePrefetch = PrefetchQuery.newBuilder()
                .setUsing(this.denseVectorName)
                .setQuery(QueryFactory.nearest(denseValues))
                .setLimit(effectivePrefetch)
                .build();

        QueryPoints.Builder request = QueryPoints.newBuilder()
                .setCollectionName(this.collectionName)
                .addPrefetch(densePrefetch)
                .setQuery(QueryFactory.fusion(Fusion.RRF))
                .setLimit(topK)
                .setWithPayload(WithPayloadSelectorFactory.enable(true));

        if (sparse != null) {
            PrefetchQuery sparsePrefetch = PrefetchQuery.newBuilder()
                    .setUsing(this.sparseVectorName)
                    .setQuery(QueryFactory.nearest(sparse.values(), sparse.indices()))
                    .setLimit(effectivePrefetch)
                    .build();
            request.addPrefetch(sparsePrefetch);
        } else {
            log.debug("[hybridQuery] sparse part empty, falling back to dense-only");
        }

        log.debug("[hybridQuery] collection={} topK={} prefetch={} denseDim={} sparseTerms={}",
                this.collectionName, topK, effectivePrefetch,
                denseValues.size(), sparse == null ? 0 : sparse.indices().size());

        List<ScoredPoint> scored = awaitCall(this.client.queryAsync(request.build()), "query");
        List<RetrievedDoc> out = new ArrayList<>(scored.size());
        for (ScoredPoint sp : scored) {
            out.add(toRetrievedDoc(sp));
        }
        return out;
    }

    // ---- 转换辅助 ----

    private PointStruct toPointStruct(HybridPoint hp) {
        Map<String, Vector> vectorMap = new LinkedHashMap<>(2);
        vectorMap.put(this.denseVectorName, VectorFactory.vector(toFloatList(hp.embedding().dense())));

        SparseValues sparse = toSparseValues(hp.embedding().sparse());
        if (sparse != null) {
            vectorMap.put(this.sparseVectorName, VectorFactory.vector(sparse.values(), sparse.indices()));
        }
        Vectors vectors = VectorsFactory.namedVectors(vectorMap);

        PointStruct.Builder builder = PointStruct.newBuilder()
                .setId(PointIdFactory.id(UUID.randomUUID()))
                .setVectors(vectors)
                .putPayload("text", ValueFactory.value(hp.text() == null ? "" : hp.text()));

        if (hp.metadata() != null) {
            for (Map.Entry<String, Object> e : hp.metadata().entrySet()) {
                JsonWithInt.Value v = toPayloadValue(e.getValue());
                if (v != null) {
                    builder.putPayload(e.getKey(), v);
                }
            }
        }
        return builder.build();
    }

    private RetrievedDoc toRetrievedDoc(ScoredPoint sp) {
        Map<String, Object> payload = new LinkedHashMap<>();
        String text = "";
        for (Map.Entry<String, JsonWithInt.Value> e : sp.getPayloadMap().entrySet()) {
            Object converted = fromPayloadValue(e.getValue());
            payload.put(e.getKey(), converted);
            if ("text".equals(e.getKey()) && converted instanceof String s) {
                text = s;
            }
        }
        return new RetrievedDoc(formatId(sp), text, payload, sp.getScore());
    }

    private static String formatId(ScoredPoint sp) {
        var id = sp.getId();
        if (id.hasUuid()) {
            return id.getUuid();
        }
        return Long.toString(id.getNum());
    }

    private static JsonWithInt.Value toPayloadValue(Object o) {
        if (o == null) {
            return ValueFactory.nullValue();
        }
        if (o instanceof String s) {
            return ValueFactory.value(s);
        }
        if (o instanceof Integer i) {
            return ValueFactory.value(i.longValue());
        }
        if (o instanceof Long l) {
            return ValueFactory.value(l);
        }
        if (o instanceof Number n) {
            return ValueFactory.value(n.doubleValue());
        }
        if (o instanceof Boolean b) {
            return ValueFactory.value(b);
        }
        if (o instanceof Date d) {
            return ValueFactory.value(d.toString());
        }
        // 兜底：转字符串，避免 putPayload 抛 NPE
        return ValueFactory.value(o.toString());
    }

    private static Object fromPayloadValue(JsonWithInt.Value v) {
        return switch (v.getKindCase()) {
            case STRING_VALUE -> v.getStringValue();
            case INTEGER_VALUE -> v.getIntegerValue();
            case DOUBLE_VALUE -> v.getDoubleValue();
            case BOOL_VALUE -> v.getBoolValue();
            case NULL_VALUE, KIND_NOT_SET -> null;
            default -> v.toString();
        };
    }

    private static List<Float> toFloatList(float[] arr) {
        List<Float> out = new ArrayList<>(arr.length);
        for (float f : arr) {
            out.add(f);
        }
        return out;
    }

    /**
     * BGE-M3 的 sparse {@code Map<Long, Float>} → Qdrant 需要的 (values[], indices[int]) 配对。
     * Long → int 是安全收窄：BGE-M3 token id 范围远低于 Integer.MAX_VALUE。
     */
    private static SparseValues toSparseValues(Map<Long, Float> sparse) {
        if (sparse == null || sparse.isEmpty()) {
            return null;
        }
        List<Float> values = new ArrayList<>(sparse.size());
        List<Integer> indices = new ArrayList<>(sparse.size());
        for (Map.Entry<Long, Float> e : sparse.entrySet()) {
            indices.add(Math.toIntExact(e.getKey()));
            values.add(e.getValue());
        }
        return new SparseValues(values, indices);
    }

    private <T> T awaitCall(com.google.common.util.concurrent.ListenableFuture<T> fut, String op) {
        try {
            return fut.get(this.callTimeout.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted during " + op, ie);
        } catch (ExecutionException | TimeoutException ex) {
            throw new IllegalStateException("qdrant " + op + " failed: " + ex.getMessage(), ex);
        }
    }

    private record SparseValues(List<Float> values, List<Integer> indices) {
    }
}
