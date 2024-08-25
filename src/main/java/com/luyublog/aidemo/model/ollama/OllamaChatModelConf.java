package com.luyublog.aidemo.model.ollama;

import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaModel;
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
public class OllamaChatModelConf {

    @Value(value = "${spring.ai.ollama.chat.options.model}")
    String model;

    @Bean(name = "ollamaChatModel")
    public OllamaChatModel ollamaChatModel() {
        var ollamaApi = new OllamaApi();

        return new OllamaChatModel(ollamaApi,
                OllamaOptions.create()
                        .withModel(OllamaModel.LLAMA3_1.id())
                        .withTemperature(0.9f));

    }
}
