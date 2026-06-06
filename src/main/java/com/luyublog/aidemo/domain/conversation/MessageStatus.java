package com.luyublog.aidemo.domain.conversation;

/**
 * 消息生成状态。
 *
 * <ul>
 *   <li>{@code GENERATING}：后台正在生成，token 持续写入 Redis Stream</li>
 *   <li>{@code DONE}：生成完成，全文已落库</li>
 *   <li>{@code FAILED}：生成异常中断</li>
 * </ul>
 */
public enum MessageStatus {
    GENERATING,
    DONE,
    FAILED
}
