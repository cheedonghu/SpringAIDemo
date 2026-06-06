package com.luyublog.aidemo.application.ingest;

/**
 * 文件灌库的返回体。
 *
 * <ul>
 *   <li>{@code source}：原始文件名（落到每个 chunk 的 payload.source）</li>
 *   <li>{@code chunks}：分块后得到的 chunk 数</li>
 *   <li>{@code upserted}：实际写入 Qdrant 的 point 数（正常等于 chunks）</li>
 * </ul>
 */
public record IngestResult(String source, int chunks, int upserted) {
}
