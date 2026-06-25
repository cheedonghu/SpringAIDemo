package com.luyublog.aidemo.application.chat;

import com.luyublog.aidemo.domain.conversation.MessageStatus;
import com.luyublog.aidemo.infrastructure.cache.redis.RedisTokenStreamStore;
import com.luyublog.aidemo.infrastructure.persistence.mysql.MessageMapper;
import com.luyublog.aidemo.infrastructure.persistence.mysql.WatchdogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 看门狗巡检:发现"Redis 流已消失但消息仍 GENERATING"的孤儿(生产者进程被硬杀、跑不到正常收尾),
 * 把它判为 FAILED 并清掉 watchdog 行,使后续重连的客户端经 DB 回落得到干净的失败结果、不再卡死。
 *
 * <p>判活信号是 Redis 流 key 的存在性:生成期间每 token 续期(inflight-ttl)→ 流在;生产者一崩、停止续期 →
 * 流在最后一个 token 后 inflight-ttl 秒过期。巡检只看 {@code created_at} 早于宽限期的在飞生成,避免误杀
 * 刚启动、还没写出首 token(流尚未创建)的正常生成。
 *
 * <p>多实例安全:{@code finalizeIfGenerating} 带 {@code WHERE status='GENERATING'},并发只一个赢;
 * 某实例正在产则流仍在,所有实例的巡检都跳过。<b>正在 tail 的活客户端</b>的即时收尾不靠这里(受流 TTL 限制太慢),
 * 而由 {@code ChatStreamController} 的 emitter 超时补发 terminal error 负责。
 */
@Component
public class StuckGenerationReaper {

    private static final Logger log = LoggerFactory.getLogger(StuckGenerationReaper.class);
    private static final int SCAN_BATCH = 200;

    private final WatchdogMapper watchdogMapper;
    private final MessageMapper messageMapper;
    private final RedisTokenStreamStore streamStore;
    private final long graceSeconds;

    public StuckGenerationReaper(WatchdogMapper watchdogMapper,
                                 MessageMapper messageMapper,
                                 RedisTokenStreamStore streamStore,
                                 @Value("${app.chat.stream.watchdog-grace-seconds:60}") long graceSeconds) {
        this.watchdogMapper = watchdogMapper;
        this.messageMapper = messageMapper;
        this.streamStore = streamStore;
        this.graceSeconds = graceSeconds;
    }

    @Scheduled(fixedDelayString = "${app.chat.stream.watchdog-scan-ms:15000}")
    public void reap() {
        LocalDateTime before = LocalDateTime.now().minusSeconds(this.graceSeconds);
        List<String> candidates = this.watchdogMapper.findScannable(before, SCAN_BATCH);
        if (candidates.isEmpty()) {
            return;
        }
        int finalized = 0;
        for (String messageId : candidates) {
            try {
                if (this.streamStore.exists(messageId)) {
                    continue; // 流还在 → 仍在产 → 跳过
                }
                int updated = this.messageMapper.finalizeIfGenerating(
                        messageId, MessageStatus.FAILED, LocalDateTime.now());
                if (updated == 1) {
                    finalized++;
                    log.warn("[reaper] orphaned generation finalized as FAILED messageId={}", messageId);
                }
                // 无论本实例是否赢(可能已被别的实例收尾,或消息已 DONE),都清掉该行
                this.watchdogMapper.delete(messageId);
            } catch (Exception ex) {
                log.warn("[reaper] reap messageId={} failed: {}", messageId, ex.getMessage());
            }
        }
        if (finalized > 0) {
            log.info("[reaper] scanned {} candidates, finalized {} orphaned", candidates.size(), finalized);
        }
    }
}
