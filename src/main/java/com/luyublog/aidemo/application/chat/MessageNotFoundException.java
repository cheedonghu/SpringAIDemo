package com.luyublog.aidemo.application.chat;

/**
 * 请求的消息既不在 Redis 流里也不在库里。由 interface 层映射成 HTTP 404，
 * 这样 application 不必依赖 web 框架的异常类型。
 */
public class MessageNotFoundException extends RuntimeException {

    public MessageNotFoundException(String messageId) {
        super("message not found: " + messageId);
    }
}
