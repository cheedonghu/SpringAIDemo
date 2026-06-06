package com.luyublog.aidemo.domain.conversation;

import java.time.LocalDateTime;

/**
 * 一条对话消息（user 提问或 assistant 回答）。纯领域值对象，无框架依赖。
 *
 * <ul>
 *   <li>{@code role}：{@code "user"} 或 {@code "assistant"}</li>
 *   <li>{@code content}：user 为问题原文；assistant 生成中为空、完成后为全文</li>
 *   <li>{@code status}：仅 assistant 消息有意义；user 消息固定 {@link MessageStatus#DONE}</li>
 *   <li>{@code sourcesJson}：assistant 消息检索到的来源 chunk 序列化 JSON（user 消息为 null）</li>
 * </ul>
 */
public record Message(
        String id,
        String conversationId,
        String role,
        String content,
        MessageStatus status,
        String sourcesJson,
        Integer tokenCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
