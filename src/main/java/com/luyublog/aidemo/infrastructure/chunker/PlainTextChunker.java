package com.luyublog.aidemo.infrastructure.chunker;

import com.luyublog.aidemo.domain.document.Chunk;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 纯文本切片：段落感知 + 句子感知，不依赖任何 tokenizer 库。
 *
 * <ol>
 *   <li>按 {@code \n\n+}（一个或多个空行）切自然段</li>
 *   <li>每段如果 ≤ {@link #MAX_TOKENS} token 就当一个 chunk；超过则按中英文句末符号（{@code 。！？/.!?}）切句子，
 *       再贪心合并句子到目标区间 [{@link #TARGET_TOKENS}, {@link #MAX_TOKENS}]</li>
 *   <li>跨段不合并 —— 空行是用户的语义信号，保留段落边界</li>
 *   <li>极短段（&lt; {@link #MIN_TOKENS}）和前一 chunk 拼接，避免噪声 chunk</li>
 * </ol>
 *
 * <p>token 数走 {@link MarkdownChunker#estimateTokens} 粗估，不调外部 tokenizer。
 *
 * <p>不像之前那样依赖 Spring AI {@code TokenTextSplitter}：那个按 OpenAI cl100k 编码切，
 * 对中文段落语义无感，会把一句话切两半；这里按"段落 → 句子"层次走，对中文友好。
 */
@Component
public class PlainTextChunker {

    /**
     * 段落分隔：一个或多个空行
     */
    private static final Pattern PARAGRAPH_SPLIT = Pattern.compile("\\R\\s*\\R+");

    private static final int TARGET_TOKENS = 400;
    private static final int MAX_TOKENS = 500;
    private static final int MIN_TOKENS = 50;

    public List<Chunk> chunk(String content) {
        if (!StringUtils.hasText(content)) {
            return List.of();
        }

        String[] paragraphs = PARAGRAPH_SPLIT.split(content.strip());
        List<String> chunkTexts = new ArrayList<>();

        for (String raw : paragraphs) {
            String paragraph = raw.strip();
            if (paragraph.isEmpty()) {
                continue;
            }
            int tokens = MarkdownChunker.estimateTokens(paragraph);

            if (tokens <= MAX_TOKENS) {
                appendOrMerge(chunkTexts, paragraph);
            } else {
                // 段太长：按句子切，再贪心合并到目标区间
                for (String chunk : SentencePacker.packBySentence(paragraph, TARGET_TOKENS, MAX_TOKENS)) {
                    appendOrMerge(chunkTexts, chunk);
                }
            }
        }

        // 包装为 Chunk + metadata
        List<Chunk> chunks = new ArrayList<>(chunkTexts.size());
        for (int i = 0; i < chunkTexts.size(); i++) {
            String text = chunkTexts.get(i);
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("chunkIndex", i);
            meta.put("chunkType", "prose");
            meta.put("granularity", "paragraph");
            meta.put("tokenCount", MarkdownChunker.estimateTokens(text));
            chunks.add(new Chunk(text, meta));
        }
        return chunks;
    }

    /**
     * 把新片段加入 chunk 列表：如果它过短（&lt; MIN_TOKENS）且已有 chunk 可以容纳，就并到末尾；
     * 否则单独成 chunk。
     */
    private void appendOrMerge(List<String> chunks, String piece) {
        if (chunks.isEmpty()) {
            chunks.add(piece);
            return;
        }
        int pieceTokens = MarkdownChunker.estimateTokens(piece);
        String last = chunks.get(chunks.size() - 1);
        int lastTokens = MarkdownChunker.estimateTokens(last);
        if (pieceTokens < MIN_TOKENS && lastTokens + pieceTokens <= MAX_TOKENS) {
            chunks.set(chunks.size() - 1, last + "\n" + piece);
        } else {
            chunks.add(piece);
        }
    }

}
