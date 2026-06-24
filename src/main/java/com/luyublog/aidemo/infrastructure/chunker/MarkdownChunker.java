package com.luyublog.aidemo.infrastructure.chunker;

import com.luyublog.aidemo.domain.document.Chunk;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 按 H1~H6 标题分段切片，每个 section 默认作为一个 chunk（**section-level**），
 * 只有在判定为"弱依赖列表"时才降级到"每条 bullet 一个 chunk"（**bullet-level**）。
 *
 * <p>每个 chunk 文本前缀注入"父标题路径 - 当前标题：内容"，让混合检索两路通道（dense + sparse）都能命中标题词。
 *
 * <p>粒度决策见 {@link #isWeaklyDependent(List)}：
 * <ul>
 *   <li>整组 bullets 总长 &lt; 400 字符 → section-level（短就整组）</li>
 *   <li>任一 bullet 含强顺序标记（"第N步" / "1." / "首先/然后/最后" 等）→ section-level（步骤不能拆）</li>
 *   <li>否则 → bullet-level（弱依赖列表，每条独立召回有意义）</li>
 * </ul>
 *
 * <p>切散 bullet 后没有"兄弟一起召回"的机制——所以这里**只在 bullet 本身确实独立时**才走 bullet-level。
 * 长 bullet 不做"二次切"：bullet 是语义凝聚体，切了就废，要切就得配套 parent-doc retrieval（暂不上）。
 *
 * <p>首版限制：代码块 ``` 内部不解析；多行续行 bullet 拍平为单行；嵌套 bullet 视为同级。
 */
@Component
public class MarkdownChunker {

    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");
    private static final Pattern BULLET_PATTERN = Pattern.compile("^\\s*[-*+]\\s+(.+?)\\s*$");
    private static final Pattern CODE_FENCE_PATTERN = Pattern.compile("^\\s*```");
    /**
     * 强顺序标记：匹配上则视为步骤型，整组保留不拆。
     */
    private static final Pattern STRONG_ORDER_PATTERN = Pattern.compile(
            "^(第[一二三四五六七八九十0-9]+[步部分章节]|\\d+[.、)]\\s|(首先|然后|接着|其次|再次|之后|最后|第一|第二|第三))");

    /**
     * 短于此长度的 bullet 在 bullet-level 模式下会和下一条合并，避免噪声 chunk。
     */
    private static final int MIN_BULLET_CHARS = 8;
    /**
     * 整组 bullets 总长低于此阈值 → 直接 section-level（不值得拆）。
     */
    private static final int SECTION_LEVEL_TOTAL_THRESHOLD = 400;
    /**
     * prose section 超过 {@link #PROSE_MAX_TOKENS} token 时,按句子贪心打包到
     * {@code [PROSE_TARGET_TOKENS, PROSE_MAX_TOKENS]} 区间兜底,避免单个超大 chunk
     * 稀释 dense 向量、撑爆 LLM 上下文。
     */
    private static final int PROSE_TARGET_TOKENS = 400;
    private static final int PROSE_MAX_TOKENS = 500;

    public List<Chunk> chunk(String content) {
        if (!StringUtils.hasText(content)) {
            return List.of();
        }

        List<Chunk> chunks = new ArrayList<>();
        Deque<Heading> headingStack = new ArrayDeque<>();
        List<String> bulletBuffer = new ArrayList<>();
        List<String> proseBuffer = new ArrayList<>();
        boolean inCodeFence = false;

        String[] lines = content.split("\\R", -1);
        for (String rawLine : lines) {
            if (CODE_FENCE_PATTERN.matcher(rawLine).find()) {
                inCodeFence = !inCodeFence;
                proseBuffer.add(rawLine);
                continue;
            }
            if (inCodeFence) {
                proseBuffer.add(rawLine);
                continue;
            }

            Matcher headingMatcher = HEADING_PATTERN.matcher(rawLine);
            if (headingMatcher.matches()) {
                flushSection(chunks, headingStack, bulletBuffer, proseBuffer);
                int level = headingMatcher.group(1).length();
                String title = headingMatcher.group(2).trim();
                while (!headingStack.isEmpty() && headingStack.peek().level() >= level) {
                    headingStack.pop();
                }
                headingStack.push(new Heading(level, title));
                continue;
            }

            Matcher bulletMatcher = BULLET_PATTERN.matcher(rawLine);
            if (bulletMatcher.matches()) {
                bulletBuffer.add(bulletMatcher.group(1).trim());
                continue;
            }

            if (StringUtils.hasText(rawLine)) {
                proseBuffer.add(rawLine.trim());
            }
        }

        flushSection(chunks, headingStack, bulletBuffer, proseBuffer);
        return chunks;
    }

    private void flushSection(List<Chunk> chunks,
                              Deque<Heading> headingStack,
                              List<String> bulletBuffer,
                              List<String> proseBuffer) {
        String currentHeading = headingStack.isEmpty() ? "" : headingStack.peek().title();
        int headingLevel = headingStack.isEmpty() ? 0 : headingStack.peek().level();
        String parentHeadings = renderParentHeadings(headingStack);

        if (!bulletBuffer.isEmpty()) {
            if (isWeaklyDependent(bulletBuffer)) {
                // bullet-level：每条独立成 chunk（沿用旧逻辑 + 短 bullet 合并）
                List<String> mergedBullets = mergeShortBullets(bulletBuffer);
                for (int i = 0; i < mergedBullets.size(); i++) {
                    String bulletText = mergedBullets.get(i);
                    String text = composeText(parentHeadings, currentHeading, bulletText);
                    chunks.add(new Chunk(text, buildMetadata(
                            currentHeading, parentHeadings, headingLevel,
                            "bullet", "bullet", i, text)));
                }
            } else {
                // section-level：整组 bullets 用换行连成一个 chunk
                String joined = String.join("\n- ", bulletBuffer);
                String body = "- " + joined; // 重新加上首项前缀
                String text = composeText(parentHeadings, currentHeading, body);
                chunks.add(new Chunk(text, buildMetadata(
                        currentHeading, parentHeadings, headingLevel,
                        "section", "section", -1, text)));
            }
        } else if (!proseBuffer.isEmpty()) {
            String prose = String.join("\n", proseBuffer).trim();
            if (StringUtils.hasText(prose)) {
                if (estimateTokens(prose) <= PROSE_MAX_TOKENS) {
                    String text = composeText(parentHeadings, currentHeading, prose);
                    chunks.add(new Chunk(text, buildMetadata(
                            currentHeading, parentHeadings, headingLevel,
                            "prose", "section", -1, text)));
                } else {
                    // 超长 prose section：按句子贪心打包兜底，每片仍带标题前缀
                    for (String piece : SentencePacker.packBySentence(prose, PROSE_TARGET_TOKENS, PROSE_MAX_TOKENS)) {
                        String text = composeText(parentHeadings, currentHeading, piece);
                        chunks.add(new Chunk(text, buildMetadata(
                                currentHeading, parentHeadings, headingLevel,
                                "prose", "paragraph", -1, text)));
                    }
                }
            }
        }

        bulletBuffer.clear();
        proseBuffer.clear();
    }

    /**
     * 判定一组 bullets 是否"弱依赖"（每条独立、拆开召回仍有意义）：
     * <ul>
     *   <li>总长 &lt; {@link #SECTION_LEVEL_TOTAL_THRESHOLD} 字符 → false（短就整组，不值得拆）</li>
     *   <li>任一 bullet 含 {@link #STRONG_ORDER_PATTERN} 顺序标记 → false（步骤序列不能拆）</li>
     *   <li>否则 → true（默认认为弱依赖）</li>
     * </ul>
     */
    private boolean isWeaklyDependent(List<String> bullets) {
        int totalChars = bullets.stream().mapToInt(String::length).sum();
        if (totalChars < SECTION_LEVEL_TOTAL_THRESHOLD) {
            return false;
        }
        for (String bullet : bullets) {
            if (STRONG_ORDER_PATTERN.matcher(bullet).find()) {
                return false;
            }
        }
        return true;
    }

    private List<String> mergeShortBullets(List<String> bullets) {
        List<String> merged = new ArrayList<>();
        StringBuilder pending = new StringBuilder();
        for (String bullet : bullets) {
            String trimmed = bullet.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (pending.length() > 0) {
                pending.append("；").append(trimmed);
                if (pending.length() >= MIN_BULLET_CHARS) {
                    merged.add(pending.toString());
                    pending.setLength(0);
                }
                continue;
            }
            if (trimmed.length() < MIN_BULLET_CHARS) {
                pending.append(trimmed);
            } else {
                merged.add(trimmed);
            }
        }
        if (pending.length() > 0) {
            if (!merged.isEmpty()) {
                merged.set(merged.size() - 1, merged.get(merged.size() - 1) + "；" + pending);
            } else {
                merged.add(pending.toString());
            }
        }
        return merged;
    }

    private String composeText(String parentHeadings, String currentHeading, String body) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(parentHeadings)) {
            sb.append(parentHeadings).append(" - ");
        }
        if (StringUtils.hasText(currentHeading)) {
            sb.append(currentHeading).append("：");
        }
        sb.append(body);
        return sb.toString();
    }

    private String renderParentHeadings(Deque<Heading> headingStack) {
        if (headingStack.size() <= 1) {
            return "";
        }
        List<String> parents = new ArrayList<>();
        boolean skippedCurrent = false;
        for (Heading heading : headingStack) {
            if (!skippedCurrent) {
                skippedCurrent = true;
                continue;
            }
            parents.add(0, heading.title());
        }
        return String.join(" > ", parents);
    }

    private Map<String, Object> buildMetadata(String heading,
                                              String parentHeadings,
                                              int headingLevel,
                                              String chunkType,
                                              String granularity,
                                              int bulletIndex,
                                              String fullText) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("heading", heading);
        meta.put("parentHeadings", parentHeadings);
        meta.put("headingLevel", headingLevel);
        meta.put("chunkType", chunkType);
        meta.put("granularity", granularity);
        meta.put("bulletIndex", bulletIndex);
        meta.put("tokenCount", estimateTokens(fullText));
        return meta;
    }

    /**
     * 粗估 token 数：汉字 ≈ 1.4 token，英文/数字字符 ≈ 0.3 token。
     * 不精确但够调试用，避免拖入完整 tokenizer 依赖。
     */
    static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                cjk++;
            } else if (!Character.isWhitespace(c)) {
                other++;
            }
        }
        return (int) Math.round(cjk * 1.4 + other * 0.3);
    }

    private record Heading(int level, String title) {
    }
}
