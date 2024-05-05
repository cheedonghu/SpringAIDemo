package com.luyublog.aidemo.vecstore.pinecone;

import org.springframework.ai.embedding.EmbeddingClient;
import org.springframework.ai.vectorstore.PineconeVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * pinecone配置
 * <p>
 * &#064;author: east
 * <p>
 * &#064;date: 2024/4/19 21:54
 */
@Configuration
public class PineconeVectorStoreConf {

    @Value(value = "${spring.ai.vectorstore.pinecone.apiKey}")
    String apikey;

    @Value(value = "${spring.ai.vectorstore.pinecone.environment}")
    String environment;

    @Value(value = "${spring.ai.vectorstore.pinecone.projectId}")
    String projectId;

    @Value(value = "${spring.ai.vectorstore.pinecone.index-name}")
    String indexName;

    @Bean
    public PineconeVectorStore.PineconeVectorStoreConfig pineconeVectorStoreConfig() {

        return PineconeVectorStore.PineconeVectorStoreConfig.builder()
                .withApiKey(apikey)
                .withEnvironment(environment)
//                .withProjectId("89309e6")
//                .withIndexName("spring-ai-test-index")
                .withProjectId(projectId)
                .withIndexName(indexName)
                .withNamespace("") // the free tier doesn't support namespaces.
                .build();
    }

    @Bean
    public PineconeVectorStore vectorStore(PineconeVectorStore.PineconeVectorStoreConfig config, EmbeddingClient ollamaEmbeddingClient) {
        return new PineconeVectorStore(config, ollamaEmbeddingClient);
    }
}
