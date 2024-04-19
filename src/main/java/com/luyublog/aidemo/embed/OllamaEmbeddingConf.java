package com.luyublog.aidemo.embed;

import org.springframework.ai.ollama.OllamaEmbeddingClient;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ollama向量化配置
 * <p>
 * &#064;author: east
 * <p>
 * &#064;date: 2024/4/19 21:25
 */
@Configuration
public class OllamaEmbeddingConf {

    @Value(value = "${spring.ai.ollama.chat.options.model}")
    String model;

    @Bean
    public OllamaEmbeddingClient ollamaEmbeddingClient() {
        var ollamaApi = new OllamaApi();

        return new OllamaEmbeddingClient(ollamaApi)
                .withDefaultOptions(OllamaOptions.create()
                        .withModel(model));
    }
}
