package com.luyublog.aidemo.domain.embedding;

import java.util.Map;

/**
 * BGE-M3 编码服务（FastAPI on :8002）的单次输出。
 *
 * <ul>
 *   <li>{@code dense}：句向量，固定 1024 维 float（BGE-M3 spec）</li>
 *   <li>{@code sparse}：词权重，{@code tokenId → weight}，可直接喂给 Qdrant 的 sparse 向量
 *       （Qdrant 内部用 indices[] / values[] 表达稀疏向量，二者一一对应）</li>
 * </ul>
 * <p>
 * 故意不实现 Spring AI 的 EmbeddingModel 接口：那个契约只能返回 float[]，没有 sparse 的位置。
 */
public record EmbedResult(float[] dense, Map<Long, Float> sparse) {
}
