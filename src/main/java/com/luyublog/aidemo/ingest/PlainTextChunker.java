package com.luyublog.aidemo.ingest;

import com.luyublog.aidemo.model.Chunk;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 纯文本切片：包装 TokenTextSplitter。
 * 中文场景下 chunkSize=400 token ≈ 600 字，比默认 800 更利于 BM25 通道命中精度。
 */
@Component
public class PlainTextChunker {

    private final TokenTextSplitter splitter = new TokenTextSplitter(400, 100, 5, 10000, true);

    public List<Chunk> chunk(String content) {
        if (!StringUtils.hasText(content)) {
            return List.of();
        }

        List<Document> input = List.of(new Document(content));
        List<Document> split = this.splitter.apply(input);

        List<Chunk> chunks = new ArrayList<>(split.size());
        for (int i = 0; i < split.size(); i++) {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("chunkIndex", i);
            chunks.add(new Chunk(split.get(i).getText(), meta));
        }
        return chunks;
    }
}
