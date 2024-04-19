package com.luyublog.aidemo.model.ollama;

import org.springframework.ai.ollama.OllamaChatClient;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ollama客户端配置
 * <p>
 * &#064;author: east
 * <p>
 * &#064;date: 2024/4/19 20:27
 */
@Configuration
public class OllamaChatClientConf {

    @Value(value = "${spring.ai.ollama.chat.options.model}")
    String model;

    @Bean(name = "ollamaChatClient")
    public OllamaChatClient ollamaChatClient() {
        var ollamaApi = new OllamaApi();

        return new OllamaChatClient(ollamaApi).withDefaultOptions(OllamaOptions.create()
                .withModel(model)
                .withTemperature(0.9f));
    }
}
