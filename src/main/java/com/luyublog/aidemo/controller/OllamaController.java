package com.luyublog.aidemo.controller;

import org.springframework.ai.chat.ChatResponse;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingClient;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.ollama.OllamaChatClient;
import org.springframework.ai.vectorstore.Neo4jVectorStore;
import org.springframework.ai.vectorstore.PineconeVectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ollama测试
 *
 * <p>
 * &#064;author: east
 * <p>
 * &#064;date: 2024/4/19 20:41
 */
@RestController
public class OllamaController {
    private final OllamaChatClient ollamaChatClient;
    private final EmbeddingClient embeddingClient;
    private final PineconeVectorStore pineconeVectorStore;
    private final Neo4jVectorStore neo4jVectorStore;

    @Autowired
    public OllamaController(OllamaChatClient ollamaChatClient,
                            EmbeddingClient embeddingClient,
                            PineconeVectorStore pineconeVectorStore,
                            Neo4jVectorStore neo4jVectorStore) {
        this.ollamaChatClient = ollamaChatClient;
        this.embeddingClient = embeddingClient;
        this.pineconeVectorStore = pineconeVectorStore;
        this.neo4jVectorStore = neo4jVectorStore;
    }

    @GetMapping("/ai/generate")
    public Map generate(@RequestParam(value = "message", defaultValue = "Tell me a joke") String message) {
        return Map.of("generation", ollamaChatClient.call(message));
    }

    @GetMapping("/ai/generateStream")
    public Flux<ChatResponse> generateStream(@RequestParam(value = "message", defaultValue = "Tell me a joke") String message) {
        Prompt prompt = new Prompt(new UserMessage(message));
        return ollamaChatClient.stream(prompt);
    }

    @GetMapping("/ai/embedding")
    public Map embed(@RequestParam(value = "message", defaultValue = "Tell me a joke") String message) {
        EmbeddingResponse embeddingResponse = this.embeddingClient.embedForResponse(List.of(message));
        return Map.of("embedding", embeddingResponse);
    }

    @GetMapping("/ai/embedding/pinecone/add")
    public String pineconeAdd(@RequestParam(value = "message", defaultValue = "Tell me a joke") String message) {
        List<Document> documents = List.of(
                new Document("Spring AI rocks!! Spring AI rocks!! Spring AI rocks!! Spring AI rocks!! Spring AI rocks!!", Map.of("author", "john")),
                new Document("The World is Big and Salvation Lurks Around the Corner"),
                new Document("You walk forward facing the past and you turn back toward the future.", Map.of("author", "jill")));

        // Add the documents
        pineconeVectorStore.add(documents);
        return "ok";
    }

    @GetMapping("/ai/embedding/neo4j/add")
    public String neo4jAdd(@RequestParam(value = "message", defaultValue = "Tell me a joke") String message) {
        List<Document> documents = List.of(
                new Document("Spring AI rocks!! Spring AI rocks!! Spring AI rocks!! Spring AI rocks!! Spring AI rocks!!", Map.of("meta1", "meta1")),
                new Document("The World is Big and Salvation Lurks Around the Corner"),
                new Document("You walk forward facing the past and you turn back toward the future.", Map.of("meta2", "meta2")));

        // Add the documents
        neo4jVectorStore.add(documents);
        return "ok";
    }

    @GetMapping("/ai/embedding/pinecone/query")
    public Map pineconeQuery(@RequestParam(value = "message", defaultValue = "Tell me a joke") String message) {

        // Retrieve documents similar to a query
        List<Document> results = pineconeVectorStore.similaritySearch(SearchRequest.query("Spring")
                .withTopK(5).withSimilarityThreshold(0.3));

        Optional<String> reduce = results.stream().map(Document::getContent).reduce(String::concat);

        return Map.of("result", reduce.orElse("查询无结果"));
    }

    @GetMapping("/ai/embedding/neo4j/query")
    public Map neo4jQuery(@RequestParam(value = "message", defaultValue = "Tell me a joke") String message) {

        // Retrieve documents similar to a query
        List<Document> results = neo4jVectorStore.similaritySearch(SearchRequest.query("Spring")
                .withTopK(5).withSimilarityThreshold(0.3));

        Optional<String> reduce = results.stream().map(Document::getContent).reduce(String::concat);

        return Map.of("result", reduce.orElse("查询无结果"));
    }

    @GetMapping("/ai/embedding/neo4j/query2")
    public Map neo4jQuery2(@RequestParam(value = "message", defaultValue = "Tell me a joke") String message) {

        // Retrieve documents similar to a query
        List<Document> results = neo4jVectorStore.similaritySearch(
                SearchRequest.defaults()
                        .withQuery("The World")
//                        .withTopK(5)
//                        .withSimilarityThreshold(0.3)
                        .withFilterExpression("author in ['john', 'jill'] || 'article_type' == 'blog'"));

        Optional<String> reduce = results.stream().map(Document::getContent).reduce(String::concat);

        return Map.of("result", reduce.orElse("查询无结果"));


    }
}
