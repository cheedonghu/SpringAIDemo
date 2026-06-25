package com.luyublog.aidemo.infrastructure.persistence.mysql;

import com.luyublog.aidemo.domain.conversation.Message;
import com.luyublog.aidemo.domain.conversation.MessageStatus;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * 消息表 MyBatis 映射。
 */
public interface MessageMapper {

    int insert(Message message);

    /**
     * 生成完成/失败时回写：状态 + 全文 + 来源 + token 数。
     */
    int updateOutcome(@Param("id") String id,
                      @Param("status") MessageStatus status,
                      @Param("content") String content,
                      @Param("sourcesJson") String sourcesJson,
                      @Param("tokenCount") Integer tokenCount,
                      @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 看门狗收尾用：仅当消息仍处 {@code GENERATING} 时才改状态。{@code WHERE ... AND status='GENERATING'}
     * 保证多节点并发只一个成功，且不会误改已正常完成(DONE)的消息。返回受影响行数(0 或 1)。
     */
    int finalizeIfGenerating(@Param("id") String id,
                             @Param("status") MessageStatus status,
                             @Param("updatedAt") LocalDateTime updatedAt);

    Message findById(@Param("id") String id);
}
