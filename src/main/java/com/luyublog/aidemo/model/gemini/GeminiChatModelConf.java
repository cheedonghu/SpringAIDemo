package com.luyublog.aidemo.model.gemini;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * ollama客户端配置
 * <p>
 * &#064;author: east
 * <p>
 * &#064;date: 2024/4/19 20:27
 */
@Configuration
public class GeminiChatModelConf {


    @Value("${spring.ai.model.gemini.key:default-value}")
    private String geminiKey;

}
