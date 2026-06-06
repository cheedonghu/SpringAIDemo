package com.luyublog.aidemo.application.ingest;

import com.luyublog.aidemo.domain.document.Chunk;
import com.luyublog.aidemo.infrastructure.chunker.MarkdownChunker;
import com.luyublog.aidemo.infrastructure.chunker.PlainTextChunker;
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

@Service
public class FileIngestService {

    private final MarkdownChunker markdownChunker;
    private final PlainTextChunker plainTextChunker;

    public FileIngestService(MarkdownChunker markdownChunker, PlainTextChunker plainTextChunker) {
        this.markdownChunker = markdownChunker;
        this.plainTextChunker = plainTextChunker;
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
