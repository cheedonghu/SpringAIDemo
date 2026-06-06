package com.luyublog.aidemo.infrastructure.persistence.mysql;

import com.luyublog.aidemo.domain.conversation.Conversation;

/**
 * 会话表 MyBatis 映射。本期仅插入；列表/查询留第二阶段。
 */
public interface ConversationMapper {

    int insert(Conversation conversation);
}
