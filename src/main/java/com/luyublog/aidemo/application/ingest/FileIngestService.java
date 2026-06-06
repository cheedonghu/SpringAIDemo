package com.luyublog.aidemo.application.ingest;

import com.luyublog.aidemo.domain.document.Chunk;
import com.luyublog.aidemo.domain.embedding.EmbedResult;
import com.luyublog.aidemo.domain.retrieval.HybridPoint;
import com.luyublog.aidemo.infrastructure.chunker.MarkdownChunker;
import com.luyublog.aidemo.infrastructure.chunker.PlainTextChunker;
import com.luyublog.aidemo.infrastructure.embedding.BgeM3Client;
import com.luyublog.aidemo.infrastructure.vectorstore.qdrant.QdrantHybridStore;
import org.springframework.ai.document.Document;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * 文件灌库用例编排：上传文件 → 按后缀分块 → BGE-M3 批量编码 → Qdrant 批量 upsert。
 *
 * <p>编排（{@link #ingestAndStore}）放在 application 层，与 {@code RagService} 同样的范式：
 * 注入 infra 客户端，对外只暴露 application/domain 类型。controller 不做业务逻辑。
 * 纯分块步骤（{@link #ingest}）保持无副作用，便于调试与复用。
 */
@Service
public class FileIngestService {

    private final MarkdownChunker markdownChunker;
    private final PlainTextChunker plainTextChunker;
    private final BgeM3Client bgeM3Client;
    private final QdrantHybridStore qdrantStore;

    public FileIngestService(MarkdownChunker markdownChunker,
                             PlainTextChunker plainTextChunker,
                             BgeM3Client bgeM3Client,
                             QdrantHybridStore qdrantStore) {
        this.markdownChunker = markdownChunker;
        this.plainTextChunker = plainTextChunker;
        this.bgeM3Client = bgeM3Client;
        this.qdrantStore = qdrantStore;
    }

    /**
     * 完整灌库链路：分块 → 编码 → 写 Qdrant。controller 直接调本方法即可。
     */
    public IngestResult ingestAndStore(MultipartFile file) {
        List<Document> documents = ingest(file);
        String source = StringUtils.hasText(file.getOriginalFilename()) ? file.getOriginalFilename() : "unnamed";
        if (documents.isEmpty()) {
            return new IngestResult(source, 0, 0);
        }

        List<String> texts = new ArrayList<>(documents.size());
        for (Document doc : documents) {
            texts.add(doc.getText());
        }
        List<EmbedResult> embeddings = this.bgeM3Client.embedBatch(texts);
        if (embeddings.size() != texts.size()) {
            throw new IllegalStateException(
                    "bge-m3 returned " + embeddings.size() + " embeddings for " + texts.size() + " chunks");
        }

        List<HybridPoint> points = new ArrayList<>(documents.size());
        for (int i = 0; i < documents.size(); i++) {
            points.add(new HybridPoint(texts.get(i), documents.get(i).getMetadata(), embeddings.get(i)));
        }
        int upserted = this.qdrantStore.upsertBatch(points);
        return new IngestResult(source, documents.size(), upserted);
    }

    public List<Document> ingest(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "file is empty");
        }

        String filename = StringUtils.hasText(file.getOriginalFilename()) ? file.getOriginalFilename() : "unnamed";
        String type = resolveType(filename);
        String content = readUtf8(file);

        List<Chunk> chunks = switch (type) {
            case "md" -> {
                List<Chunk> result = this.markdownChunker.chunk(content);
                yield result.isEmpty() ? this.plainTextChunker.chunk(content) : result;
            }
            case "txt" -> this.plainTextChunker.chunk(content);
            default -> throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "only .md and .txt are supported, got: " + filename);
        };

        String uploadedAt = Instant.now().toString();
        List<Document> documents = new ArrayList<>(chunks.size());
        for (Chunk chunk : chunks) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("source", filename);
            metadata.put("type", type);
            metadata.put("uploadedAt", uploadedAt);
            metadata.putAll(chunk.metadata());
            documents.add(new Document(chunk.text(), metadata));
        }
        return documents;
    }

    private String resolveType(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
            return "md";
        }
        if (lower.endsWith(".txt")) {
            return "txt";
        }
        return "unknown";
    }

    private String readUtf8(MultipartFile file) {
        try {
            return new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "failed to read uploaded file", ex);
        }
    }
}
