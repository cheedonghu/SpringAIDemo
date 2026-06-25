package com.luyublog.aidemo.infrastructure.persistence.mysql;

import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 在飞中生成的看门狗表映射。只装尚未结束的 assistant 生成,结束即删。
 */
public interface WatchdogMapper {

    int insert(@Param("messageId") String messageId,
               @Param("conversationId") String conversationId,
               @Param("createdAt") LocalDateTime createdAt);

    int delete(@Param("messageId") String messageId);

    /**
     * 取 {@code createdAt} 早于 {@code before} 的在飞生成的 messageId（最多 {@code limit} 个）。
     * before = now - 宽限期,避免把刚启动、还没出首 token 的生成纳入巡检。
     */
    List<String> findScannable(@Param("before") LocalDateTime before, @Param("limit") int limit);
}
