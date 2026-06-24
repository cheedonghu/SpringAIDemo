package com.luyublog.aidemo.infrastructure.chunker;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 句子级贪心打包：把一段长文本按中英文句末符号（{@code 。！？/.!?}）切句，再贪心合并到
 * {@code [targetTokens, maxTokens]} 区间。两个 chunker 共用：
 * <ul>
 *   <li>{@link PlainTextChunker}：超过 MAX 的自然段做段内二次切</li>
 *   <li>{@link MarkdownChunker}：超长 prose section 的尺寸兜底</li>
 * </ul>
 *
 * <p>单句超过 {@code maxTokens} 时不再强切（语义优先），整句作为一片。
 * token 数走 {@link MarkdownChunker#estimateTokens} 粗估，不调外部 tokenizer。
 */
final class SentencePacker {

    /**
     * 中英文句末符号，作为段内二次切分的优先切点
     */
    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("(?<=[。！？!?\\.])\\s*");

    private SentencePacker() {
    }

    static List<String> packBySentence(String paragraph, int targetTokens, int maxTokens) {
        String[] sentences = SENTENCE_BOUNDARY.split(paragraph);
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int currentTokens = 0;

        for (String sentence : sentences) {
            String s = sentence.strip();
            if (s.isEmpty()) {
                continue;
            }
            int sTokens = MarkdownChunker.estimateTokens(s);
            if (currentTokens + sTokens > maxTokens && currentTokens > 0) {
                result.add(current.toString().strip());
                current.setLength(0);
                currentTokens = 0;
            }
            if (current.length() > 0) {
                current.append(' ');
            }
            current.append(s);
            currentTokens += sTokens;
            if (currentTokens >= targetTokens) {
                result.add(current.toString().strip());
                current.setLength(0);
                currentTokens = 0;
            }
        }
        if (current.length() > 0) {
            result.add(current.toString().strip());
        }
        return result;
    }
}
