package com.luyublog.aidemo.domain.retrieval;

import com.luyublog.aidemo.domain.embedding.EmbedResult;

import java.util.Map;

/**
 * 一条要灌入 Qdrant 的混合向量记录。
 *
 * <ul>
 *   <li>{@code text}：原文 chunk，会落到 payload 的 "text" 字段，检索后用于拼 prompt</li>
 *   <li>{@code metadata}：附加 payload 字段（source、heading、chunkIndex 等），扁平写入 payload；
 *       value 仅支持 String / Number / Boolean，复杂结构请先 toString</li>
 *   <li>{@code embedding}：BGE-M3 的 dense + sparse 输出，建议复用 BgeM3Client.embedBatch 一次拿到</li>
 * </ul>
 */
public record HybridPoint(String text, Map<String, Object> metadata, EmbedResult embedding) {
}
