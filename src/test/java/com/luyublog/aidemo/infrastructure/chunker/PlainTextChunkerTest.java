package com.luyublog.aidemo.infrastructure.chunker;

import com.luyublog.aidemo.domain.document.Chunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlainTextChunkerTest {

    private final PlainTextChunker chunker = new PlainTextChunker();

    @Test
    void singleShortParagraphProducesOneChunk() {
        String txt = "这是一段简短的中文文本，不需要切分。";

        List<Chunk> chunks = chunker.chunk(txt);

        assertEquals(1, chunks.size());
        assertEquals("prose", chunks.get(0).metadata().get("chunkType"));
        assertEquals("paragraph", chunks.get(0).metadata().get("granularity"));
        assertEquals(0, chunks.get(0).metadata().get("chunkIndex"));
    }

    @Test
    void multipleParagraphsAreKeptSeparate() {
        // 中等长度段落（每段 ≈ 80 token），不应被合并（前一段已经超过 MIN_TOKENS=50）
        String para = "这是一段相对完整且具有自包含语义的中文段落用来模拟正常长度自然段，避免被合并到相邻段落。"
                + "这是一段相对完整且具有自包含语义的中文段落用来模拟正常长度自然段，避免被合并到相邻段落。";
        String txt = para + "\n\n" + para + "\n\n" + para;

        List<Chunk> chunks = chunker.chunk(txt);

        assertEquals(3, chunks.size(),
                "well-sized paragraphs should not merge across blank-line boundaries");
    }

    @Test
    void tinyParagraphsGetMerged() {
        // 三段都很短，应该合并
        String txt = "短段一。\n\n短段二。\n\n短段三。";

        List<Chunk> chunks = chunker.chunk(txt);

        assertEquals(1, chunks.size(),
                "tiny consecutive paragraphs should merge to avoid noise chunks");
    }

    @Test
    void longParagraphSplitsBySentence() {
        // 拼一个超过 MAX_TOKENS=500 的长段（无空行）
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            sb.append("这是第").append(i).append("个相对完整的中文句子用来累计字符。");
        }
        String txt = sb.toString();
        int totalTokens = MarkdownChunker.estimateTokens(txt);
        assertTrue(totalTokens > 500, "test setup: paragraph should exceed MAX_TOKENS");

        List<Chunk> chunks = chunker.chunk(txt);

        assertTrue(chunks.size() >= 2,
                "long single paragraph must split by sentences");
        for (Chunk c : chunks) {
            // 每个子 chunk 都应是完整句子组合，token 数不至于超太多
            int t = (int) c.metadata().get("tokenCount");
            assertTrue(t > 0);
        }
    }

    @Test
    void emptyInputProducesNoChunks() {
        assertTrue(chunker.chunk("").isEmpty());
        assertTrue(chunker.chunk("   \n\n   ").isEmpty());
        assertTrue(chunker.chunk(null).isEmpty());
    }

    @Test
    void metadataAlwaysPresent() {
        String txt = "段落一。\n\n段落二，稍微长一些用来产生第二个 chunk。\n\n段落三。";
        List<Chunk> chunks = chunker.chunk(txt);
        for (int i = 0; i < chunks.size(); i++) {
            Chunk c = chunks.get(i);
            assertNotNull(c.metadata().get("chunkIndex"));
            assertNotNull(c.metadata().get("chunkType"));
            assertNotNull(c.metadata().get("granularity"));
            assertNotNull(c.metadata().get("tokenCount"));
        }
    }
}
