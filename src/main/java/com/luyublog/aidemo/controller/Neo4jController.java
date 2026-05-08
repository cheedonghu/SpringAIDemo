package com.luyublog.aidemo.controller;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.neo4j.Neo4jVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Neo4j 向量库测试
 *
 * <p>
 * &#064;author: east
 * <p>
 * &#064;date: 2026/4/11
 */
@RestController
public class Neo4jController {

    private final Neo4jVectorStore neo4jVectorStore;
    private final Neo4jHybridSearchService hybridSearchService;

    @Autowired
    public Neo4jController(Neo4jVectorStore neo4jVectorStore,
                           Neo4jHybridSearchService hybridSearchService) {
        this.neo4jVectorStore = neo4jVectorStore;
        this.hybridSearchService = hybridSearchService;
    }

    @GetMapping("/ai/neo4j/add")
    public Map<String, Object> add(@RequestParam(value = "message", defaultValue = "Spring AI integrates with Neo4j vector search.") String message) {
        List<Document> documents = List.of(
                new Document(message, Map.of("source", "manual", "database", "neo4j")),
                new Document("Neo4j stores data as nodes, relationships, labels and properties.", Map.of("source", "manual", "topic", "neo4j")),
                new Document("Spring AI can use Neo4j as a vector store for retrieval augmented generation.", Map.of("source", "manual", "topic", "rag"))
        );

        this.neo4jVectorStore.add(documents);
        return Map.of("message", "ok", "count", documents.size());
    }

    @GetMapping("/ai/neo4j/query")
    public Map<String, Object> query(@RequestParam(value = "message", defaultValue = "Spring AI") String message,
                                     @RequestParam(value = "topK", defaultValue = "5") int topK,
                                     @RequestParam(value = "similarityThreshold", defaultValue = "0.0") double similarityThreshold,
                                     @RequestParam(value = "mmrLambda", defaultValue = "0.7") double mmrLambda) {
        Neo4jHybridSearchService.SearchResult searchResult = this.hybridSearchService.search(
                message,
                topK,
                similarityThreshold,
                mmrLambda
        );

        return Map.of(
                "query", searchResult.query(),
                "count", searchResult.count(),
                "result", searchResult.result(),
                "documents", searchResult.documents(),
                "debug", searchResult.debug()
        );
    }
}
