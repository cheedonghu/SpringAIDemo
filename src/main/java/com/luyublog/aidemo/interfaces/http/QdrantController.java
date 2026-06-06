package com.luyublog.aidemo.interfaces.http;

import com.luyublog.aidemo.domain.embedding.EmbedResult;
import com.luyublog.aidemo.domain.retrieval.HybridPoint;
import com.luyublog.aidemo.domain.retrieval.RetrievedDoc;
import com.luyublog.aidemo.infrastructure.embedding.BgeM3Client;
import com.luyublog.aidemo.infrastructure.vectorstore.qdrant.QdrantHybridStore;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Task B 的最小验证入口。还没接到 Task C 的检索+生成编排上，纯调试用。
 *
 * <ul>
 *   <li>{@code POST /ai/qdrant/init}：手动触发 ensureCollection（也可关掉自动 initialize-schema 后用这个）</li>
 *   <li>{@code POST /ai/qdrant/upsert}：传 List&lt;String&gt; 文本，自动 BGE-M3 编码后批量 upsert</li>
 *   <li>{@code GET  /ai/qdrant/query?text=...&topK=5}：编码后跑 dense+sparse RRF 混检</li>
 * </ul>
 */
@RestController
public class QdrantController {

    private final BgeM3Client bgeM3Client;
    private final QdrantHybridStore qdrantStore;

    public QdrantController(BgeM3Client bgeM3Client, QdrantHybridStore qdrantStore) {
        this.bgeM3Client = bgeM3Client;
        this.qdrantStore = qdrantStore;
    }

    @PostMapping("/ai/qdrant/init")
    public Map<String, Object> init() {
        this.qdrantStore.ensureCollection();
        return Map.of("ok", true);
    }

    /**
     * Body 示例：{@code ["文本1", "文本2"]} 或 {@code {"texts":[...]}}（这里取 List 体）。
     */
    @PostMapping("/ai/qdrant/upsert")
    public Map<String, Object> upsert(@RequestBody List<String> texts,
                                      @RequestParam(value = "source", defaultValue = "ad-hoc") String source) {
        List<EmbedResult> embeddings = this.bgeM3Client.embedBatch(texts);
        List<HybridPoint> points = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("source", source);
            meta.put("chunkIndex", i);
            points.add(new HybridPoint(texts.get(i), meta, embeddings.get(i)));
        }
        int count = this.qdrantStore.upsertBatch(points);
        return Map.of("upserted", count, "source", source);
    }

    @GetMapping("/ai/qdrant/query")
    public Map<String, Object> query(@RequestParam("text") String text,
                                     @RequestParam(value = "topK", defaultValue = "5") int topK,
                                     @RequestParam(value = "prefetchLimit", defaultValue = "20") int prefetchLimit) {
        EmbedResult embedding = this.bgeM3Client.embed(text);
        List<RetrievedDoc> docs = this.qdrantStore.hybridQuery(embedding, topK, prefetchLimit);
        return Map.of(
                "query", text,
                "count", docs.size(),
                "docs", docs
        );
    }
}
