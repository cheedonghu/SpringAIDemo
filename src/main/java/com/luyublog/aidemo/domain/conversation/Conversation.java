package com.luyublog.aidemo.domain.conversation;

import java.time.LocalDateTime;

/**
 * 一次会话（一组消息的容器）。本期仅在首次提问时创建；列表/历史查询留第二阶段。
 * 单用户场景，暂不带 user_id。
 */
public record Conversation(
        String id,
        String title,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
