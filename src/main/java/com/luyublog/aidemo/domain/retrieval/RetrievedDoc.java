package com.luyublog.aidemo.domain.retrieval;

import java.util.Map;

/**
 * 混合检索的单条返回。
 *
 * <ul>
 *   <li>{@code id}：Qdrant point id（UUID 字符串或数字字符串）</li>
 *   <li>{@code text}：从 payload.text 取出的原文 chunk</li>
 *   <li>{@code payload}：完整 payload（已展开为 Java 类型），含 source 等元数据</li>
 *   <li>{@code score}：Qdrant 返回的最终分（RRF 融合后的得分，量纲约 [0, 0.033]）</li>
 * </ul>
 */
public record RetrievedDoc(String id, String text, Map<String, Object> payload, float score) {
}
