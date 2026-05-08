package com.luyublog.aidemo.ingest;

import com.luyublog.aidemo.model.Chunk;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 按 H1~H6 标题分段，再按 bullet 切 chunk。每个 chunk 文本前缀注入"父标题路径 - 当前标题：bullet"，
 * 让混合检索两路通道（语义 + BM25）都能命中标题词。
 * <p>
 * 首版限制：代码块 ``` 内部不解析；多行续行 bullet 拍平为单行；嵌套 bullet 视为同级。
 */
@Component
public class MarkdownChunker {

    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");
    private static final Pattern BULLET_PATTERN = Pattern.compile("^\\s*[-*+]\\s+(.+?)\\s*$");
    private static final Pattern CODE_FENCE_PATTERN = Pattern.compile("^\\s*```");
    private static final int MIN_BULLET_CHARS = 8;

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
        String parentHeadings = renderParentHeadings(headingStack);

        if (!bulletBuffer.isEmpty()) {
            List<String> mergedBullets = mergeShortBullets(bulletBuffer);
            for (int i = 0; i < mergedBullets.size(); i++) {
                String bulletText = mergedBullets.get(i);
                String text = composeText(parentHeadings, currentHeading, bulletText);
                chunks.add(new Chunk(text, buildMetadata(currentHeading, parentHeadings, i)));
            }
        } else if (!proseBuffer.isEmpty()) {
            String prose = String.join("\n", proseBuffer).trim();
            if (StringUtils.hasText(prose)) {
                String text = composeText(parentHeadings, currentHeading, prose);
                chunks.add(new Chunk(text, buildMetadata(currentHeading, parentHeadings, -1)));
            }
        }

        bulletBuffer.clear();
        proseBuffer.clear();
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

    private Map<String, Object> buildMetadata(String heading, String parentHeadings, int bulletIndex) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("heading", heading);
        meta.put("parentHeadings", parentHeadings);
        meta.put("bulletIndex", bulletIndex);
        return meta;
    }

    private record Heading(int level, String title) {
    }
}
