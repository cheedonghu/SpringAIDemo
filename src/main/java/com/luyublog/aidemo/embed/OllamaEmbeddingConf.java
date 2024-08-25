package com.luyublog.aidemo.embed;

import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaModel;
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
    public OllamaEmbeddingModel ollamaEmbeddingModel() {
        var ollamaApi = new OllamaApi();

        return new OllamaEmbeddingModel(ollamaApi,
                OllamaOptions.builder()
                        .withModel(OllamaModel.LLAMA3_1.id())
                        .build());
    }
}
