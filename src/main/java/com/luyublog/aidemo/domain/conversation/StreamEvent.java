package com.luyublog.aidemo.domain.conversation;

/**
 * 流式回答的一个中立事件，跨层传递时不携带任何 Redis / SSE 的 SDK 类型。
 *
 * <ul>
 *   <li>{@code id}：事件序号（来自 Redis Stream entry id，可空——DB 回放时无序号）</li>
 *   <li>{@code type}：{@link Type#TOKEN} 内容片段 / {@link Type#DONE} 正常结束 / {@link Type#ERROR} 异常</li>
 *   <li>{@code data}：TOKEN 为内容；ERROR 为错误说明；DONE 为空串</li>
 * </ul>
 *
 * <p>infrastructure 负责把存储编码翻译成本类型，interface 负责把本类型翻译成具体协议帧（如 SSE）。
 */
public record StreamEvent(String id, Type type, String data) {

    public enum Type {TOKEN, DONE, ERROR}

    public static StreamEvent token(String id, String text) {
        return new StreamEvent(id, Type.TOKEN, text);
    }

    public static StreamEvent done(String id) {
        return new StreamEvent(id, Type.DONE, "");
    }

    public static StreamEvent error(String id, String message) {
        return new StreamEvent(id, Type.ERROR, message);
    }
}
