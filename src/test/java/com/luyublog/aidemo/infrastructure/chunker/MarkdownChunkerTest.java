package com.luyublog.aidemo.infrastructure.chunker;

import com.luyublog.aidemo.domain.document.Chunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MarkdownChunkerTest {

    private final MarkdownChunker chunker = new MarkdownChunker();

    @Test
    void shortSectionGoesSectionLevel() {
        // 总长 < 400 字符，应整组作为一个 chunk
        String md = """
                ## 注意点
                - 偏僻的地方人最危险
                - 衣食住自己的
                - 有同伴比独自一人安全
                """;

        List<Chunk> chunks = chunker.chunk(md);

        assertEquals(1, chunks.size(), "short bullet group should be one section-level chunk");
        Chunk c = chunks.get(0);
        assertEquals("section", c.metadata().get("granularity"));
        assertEquals("section", c.metadata().get("chunkType"));
        assertEquals(2, c.metadata().get("headingLevel"));
        assertEquals("注意点", c.metadata().get("heading"));
        assertTrue(c.text().contains("注意点："), "chunk text should carry heading prefix");
        assertTrue(c.text().contains("偏僻") && c.text().contains("同伴"),
                "all bullets should be in one chunk");
    }

    @Test
    void strongOrderedStepsStaySectionLevel() {
        // 含"第N步"顺序标记，即使总长 > 400 也应整组保留
        String md = """
                ## 红烧肉做法
                - 第一步：把五花肉切成 2cm 见方的块，冷水下锅焯水 3 分钟，捞出后用温水冲掉浮沫。
                - 第二步：锅里少油，放冰糖 30g 小火熬到琥珀色冒小泡，倒入肉块快速翻炒上色。
                - 第三步：加生抽、老抽各 1 勺，料酒 2 勺，姜片葱段，加开水没过肉面。
                - 第四步：大火烧开转小火炖 50 分钟，转大火收汁到浓稠裹肉即可出锅。
                """;

        List<Chunk> chunks = chunker.chunk(md);

        assertEquals(1, chunks.size(),
                "ordered steps must not be split — strong dependency");
        assertEquals("section", chunks.get(0).metadata().get("granularity"));
    }

    @Test
    void longWeakDependentListGoesBulletLevel() {
        // 总长 > 400 且无顺序标记，应每条 bullet 一个 chunk
        StringBuilder sb = new StringBuilder("## 旅行装备清单\n");
        for (int i = 0; i < 10; i++) {
            sb.append("- 装备编号").append(i)
                    .append("：这是一项必备装备，描述加长到足够字符让整组总字符数稳稳超过四百字符的判定阈值，避免被误判为短整组。\n");
        }
        String md = sb.toString();

        // 自检：去掉 markdown 标记后的总长确实超过阈值
        int bulletTotal = 0;
        for (String line : md.split("\\R")) {
            if (line.startsWith("- ")) {
                bulletTotal += line.substring(2).length();
            }
        }
        assertTrue(bulletTotal >= 400, "test setup precondition: total chars=" + bulletTotal);

        List<Chunk> chunks = chunker.chunk(md);

        assertEquals(10, chunks.size(),
                "weak-dependent long list should split into individual bullets");
        for (Chunk c : chunks) {
            assertEquals("bullet", c.metadata().get("granularity"));
            assertEquals("bullet", c.metadata().get("chunkType"));
            assertNotNull(c.metadata().get("bulletIndex"));
        }
    }

    @Test
    void allChunksHaveEnrichedMetadata() {
        String md = """
                # 顶级标题
                ## 二级标题
                这是一段散文 prose。
                """;

        List<Chunk> chunks = chunker.chunk(md);
        assertTrue(chunks.size() >= 1);
        for (Chunk c : chunks) {
            assertNotNull(c.metadata().get("heading"));
            assertNotNull(c.metadata().get("parentHeadings"));
            assertNotNull(c.metadata().get("headingLevel"));
            assertNotNull(c.metadata().get("chunkType"));
            assertNotNull(c.metadata().get("granularity"));
            assertNotNull(c.metadata().get("tokenCount"));
            assertTrue((int) c.metadata().get("tokenCount") > 0);
        }
    }

    @Test
    void estimateTokensReasonableForChineseAndEnglish() {
        // 10 个汉字 ≈ 14 token
        assertEquals(14, MarkdownChunker.estimateTokens("中文中文中文中文中文"));
        // 10 个英文字符 ≈ 3 token
        assertEquals(3, MarkdownChunker.estimateTokens("abcdefghij"));
    }
}
