package com.luyublog.aidemo.controller;

import org.neo4j.driver.*;
import org.neo4j.driver.Record;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Neo4j 混合召回：关键词检索 + 语义检索 + MMR 去重重排
 */
@Service
public class Neo4jHybridSearchService {

    private static final String ID_PROPERTY = "id";
    private static final String TEXT_PROPERTY = "text";
    private static final String METADATA_PROPERTY = "metadata";

    private final Driver driver;
    private final EmbeddingModel embeddingModel;
    private final String databaseName;
    private final String vectorIndexName;
    private final String keywordIndexName;
    private final String label;
    private final String embeddingProperty;
    private final double keywordWeight;
    private final double minLexicalOverlap;
    private final int minKeywordHits;

    public Neo4jHybridSearchService(Driver driver,
                                    EmbeddingModel embeddingModel,
                                    @Value("${spring.ai.vectorstore.neo4j.database-name:neo4j}") String databaseName,
                                    @Value("${spring.ai.vectorstore.neo4j.index-name:spring-ai-document-index}") String vectorIndexName,
                                    @Value("${spring.ai.vectorstore.neo4j.keyword-index-name:aidemo-neo4j-keyword-index}") String keywordIndexName,
                                    @Value("${spring.ai.vectorstore.neo4j.label:Document}") String label,
                                    @Value("${spring.ai.vectorstore.neo4j.embedding-property:embedding}") String embeddingProperty,
                                    @Value("${app.hybrid.keyword-weight:0.15}") double keywordWeight,
                                    @Value("${app.hybrid.min-lexical-overlap:0.2}") double minLexicalOverlap,
                                    @Value("${app.hybrid.min-keyword-hits:1}") int minKeywordHits) {
        this.driver = driver;
        this.embeddingModel = embeddingModel;
        this.databaseName = databaseName;
        this.vectorIndexName = vectorIndexName;
        this.keywordIndexName = keywordIndexName;
        this.label = label;
        this.embeddingProperty = embeddingProperty;
        this.keywordWeight = keywordWeight;
        this.minLexicalOverlap = minLexicalOverlap;
        this.minKeywordHits = minKeywordHits;
    }

    public SearchResult search(String query, int topK, double similarityThreshold, double mmrLambda) {
        int candidateLimit = Math.max(topK * 4, 10);
        float[] queryEmbedding = this.embeddingModel.embed(query);
        QueryTerms queryTerms = analyzeQuery(query);

        List<CandidateDocument> semanticCandidates = semanticSearch(queryEmbedding, candidateLimit, similarityThreshold);
        List<CandidateDocument> keywordCandidates = keywordSearch(queryTerms, candidateLimit);

        Map<String, CandidateDocument> mergedCandidates = mergeCandidates(queryTerms, semanticCandidates, keywordCandidates, queryEmbedding);
        List<CandidateDocument> rerankedDocuments = mmrSelect(mergedCandidates.values(), queryEmbedding, topK, mmrLambda);

        Optional<String> content = rerankedDocuments.stream()
                .map(candidate -> candidate.document().getText())
                .reduce((left, right) -> left + System.lineSeparator() + right);

        return new SearchResult(
                query,
                rerankedDocuments.size(),
                content.orElse("查询无结果"),
                rerankedDocuments.stream().map(CandidateDocument::document).toList(),
                toDebugPayload(semanticCandidates, keywordCandidates, mergedCandidates.values(), rerankedDocuments)
        );
    }

    private List<CandidateDocument> semanticSearch(float[] queryEmbedding, int candidateLimit, double similarityThreshold) {
        String cypher = """
                CALL db.index.vector.queryNodes($indexName, $candidateLimit, $embedding)
                YIELD node, score
                WHERE score >= $similarityThreshold
                RETURN node.%s AS id,
                       node.%s AS text,
                       node.%s AS metadata,
                       node.%s AS embedding,
                       score AS semanticScore
                ORDER BY semanticScore DESC
                LIMIT $candidateLimit
                """.formatted(ID_PROPERTY, TEXT_PROPERTY, METADATA_PROPERTY, this.embeddingProperty);

        try (Session session = this.driver.session(SessionConfig.forDatabase(this.databaseName))) {
            return session.executeRead(tx -> tx.run(cypher, Values.parameters(
                            "indexName", this.vectorIndexName,
                            "candidateLimit", candidateLimit,
                            "embedding", toDoubleList(queryEmbedding),
                            "similarityThreshold", similarityThreshold
                    ))
                    .list(record -> toCandidateDocument(record, ScoreSource.SEMANTIC)));
        }
    }

    private List<CandidateDocument> keywordSearch(QueryTerms queryTerms, int candidateLimit) {
        String keywordQuery = toKeywordQuery(queryTerms);
        if (!StringUtils.hasText(keywordQuery)) {
            return List.of();
        }

        String cypher = """
                CALL db.index.fulltext.queryNodes($indexName, $query)
                YIELD node, score
                RETURN node.%s AS id,
                       node.%s AS text,
                       node.%s AS metadata,
                       node.%s AS embedding,
                       score AS keywordScore
                ORDER BY keywordScore DESC
                LIMIT $candidateLimit
                """.formatted(ID_PROPERTY, TEXT_PROPERTY, METADATA_PROPERTY, this.embeddingProperty);

        try (Session session = this.driver.session(SessionConfig.forDatabase(this.databaseName))) {
            List<CandidateDocument> rawCandidates = session.executeRead(tx -> tx.run(cypher, Values.parameters(
                            "indexName", this.keywordIndexName,
                            "query", keywordQuery,
                            "candidateLimit", candidateLimit
                    ))
                    .list(record -> toCandidateDocument(record, ScoreSource.KEYWORD)));

            return rawCandidates.stream()
                    .map(candidate -> enrichKeywordCandidate(candidate, queryTerms))
                    .filter(candidate -> candidate.lexicalOverlap() >= this.minLexicalOverlap || candidate.keywordHits() >= this.minKeywordHits)
                    .limit(candidateLimit)
                    .toList();
        }
    }

    private Map<String, CandidateDocument> mergeCandidates(QueryTerms queryTerms,
                                                           List<CandidateDocument> semanticCandidates,
                                                           List<CandidateDocument> keywordCandidates,
                                                           float[] queryEmbedding) {
        Map<String, CandidateDocument> merged = new LinkedHashMap<>();
        semanticCandidates.forEach(candidate -> mergeCandidate(merged, candidate, queryTerms, queryEmbedding));
        keywordCandidates.forEach(candidate -> mergeCandidate(merged, candidate, queryTerms, queryEmbedding));
        return merged;
    }

    private void mergeCandidate(Map<String, CandidateDocument> merged, CandidateDocument incoming, QueryTerms queryTerms, float[] queryEmbedding) {
        String candidateId = incoming.document().getId();
        CandidateDocument existing = merged.get(candidateId);
        if (existing == null) {
            double normalizedSemanticScore = incoming.semanticScore() > 0 ? incoming.semanticScore() : cosineSimilarity(queryEmbedding, incoming.embedding());
            double normalizedKeywordScore = lexicalScore(queryTerms, incoming, incoming.keywordScore());
            double blendedScore = blendedScore(normalizedSemanticScore, normalizedKeywordScore, incoming.containsExactPhrase());
            merged.put(candidateId, incoming.withScores(normalizedSemanticScore, normalizedKeywordScore, blendedScore));
            return;
        }

        double semanticScore = Math.max(existing.semanticScore(), incoming.semanticScore());
        if (semanticScore <= 0) {
            semanticScore = cosineSimilarity(queryEmbedding, existing.embedding());
        }

        double keywordScore = Math.max(existing.keywordScore(), incoming.keywordScore());
        double lexicalOverlap = Math.max(existing.lexicalOverlap(), incoming.lexicalOverlap());
        int keywordHits = Math.max(existing.keywordHits(), incoming.keywordHits());
        boolean containsExactPhrase = existing.containsExactPhrase() || incoming.containsExactPhrase();
        CandidateDocument mergedCandidate = existing.mergeKeywordSignals(keywordScore, lexicalOverlap, keywordHits, containsExactPhrase);
        double normalizedKeywordScore = lexicalScore(queryTerms, mergedCandidate, keywordScore);
        merged.put(candidateId, mergedCandidate.withScores(semanticScore, normalizedKeywordScore, blendedScore(semanticScore, normalizedKeywordScore, containsExactPhrase)));
    }

    private List<CandidateDocument> mmrSelect(Collection<CandidateDocument> candidates, float[] queryEmbedding, int topK, double mmrLambda) {
        List<CandidateDocument> remaining = candidates.stream()
                .filter(candidate -> candidate.embedding() != null && candidate.embedding().length > 0)
                .sorted(Comparator.comparingDouble(CandidateDocument::blendedScore).reversed())
                .collect(Collectors.toCollection(ArrayList::new));

        List<CandidateDocument> selected = new ArrayList<>();
        while (!remaining.isEmpty() && selected.size() < topK) {
            CandidateDocument bestCandidate = null;
            double bestScore = Double.NEGATIVE_INFINITY;

            for (CandidateDocument candidate : remaining) {
                double relevance = candidate.blendedScore() > 0
                        ? candidate.blendedScore()
                        : cosineSimilarity(queryEmbedding, candidate.embedding());
                double diversityPenalty = selected.stream()
                        .mapToDouble(chosen -> cosineSimilarity(candidate.embedding(), chosen.embedding()))
                        .max()
                        .orElse(0.0);

                double mmrScore = mmrLambda * relevance - (1 - mmrLambda) * diversityPenalty;
                if (mmrScore > bestScore) {
                    bestScore = mmrScore;
                    bestCandidate = candidate.withMmrScore(mmrScore);
                }
            }

            if (bestCandidate == null) {
                break;
            }

            selected.add(bestCandidate);
            String selectedId = bestCandidate.document().getId();
            remaining.removeIf(candidate -> Objects.equals(candidate.document().getId(), selectedId));
        }

        return selected;
    }

    private Map<String, Object> toDebugPayload(List<CandidateDocument> semanticCandidates,
                                               List<CandidateDocument> keywordCandidates,
                                               Collection<CandidateDocument> mergedCandidates,
                                               List<CandidateDocument> rerankedDocuments) {
        return Map.of(
                "semanticCandidates", semanticCandidates.stream().map(this::toCandidateMap).toList(),
                "keywordCandidates", keywordCandidates.stream().map(this::toCandidateMap).toList(),
                "mergedCandidates", mergedCandidates.stream().map(this::toCandidateMap).toList(),
                "reranked", rerankedDocuments.stream().map(this::toCandidateMap).toList()
        );
    }

    private Map<String, Object> toCandidateMap(CandidateDocument candidate) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", candidate.document().getId());
        payload.put("text", candidate.document().getText());
        payload.put("metadata", candidate.document().getMetadata());
        payload.put("semanticScore", candidate.semanticScore());
        payload.put("keywordScore", candidate.keywordScore());
        payload.put("lexicalOverlap", candidate.lexicalOverlap());
        payload.put("keywordHits", candidate.keywordHits());
        payload.put("containsExactPhrase", candidate.containsExactPhrase());
        payload.put("blendedScore", candidate.blendedScore());
        payload.put("mmrScore", candidate.mmrScore());
        return payload;
    }

    private CandidateDocument toCandidateDocument(Record record, ScoreSource scoreSource) {
        String id = record.get("id").isNull() ? null : record.get("id").asString();
        String text = record.get("text").isNull() ? "" : record.get("text").asString();
        Map<String, Object> metadata = record.get("metadata").isNull() ? new HashMap<>() : record.get("metadata").asMap();
        float[] embedding = record.get("embedding").isNull() ? new float[0] : toFloatArray(record.get("embedding").asList(value -> ((Number) value.asObject()).floatValue()));

        Document document = new Document(id, text, metadata);
        double semanticScore = scoreSource == ScoreSource.SEMANTIC ? record.get("semanticScore").asDouble() : 0.0;
        double keywordScore = scoreSource == ScoreSource.KEYWORD ? record.get("keywordScore").asDouble() : 0.0;
        double blendedScore = blendedScore(semanticScore, keywordScore, false);
        return new CandidateDocument(document, embedding, semanticScore, keywordScore, 0.0, 0, false, blendedScore, 0.0);
    }

    private String toKeywordQuery(QueryTerms queryTerms) {
        if (!queryTerms.phraseTerms().isEmpty()) {
            return queryTerms.phraseTerms().stream()
                    .map(term -> "\"" + term + "\"")
                    .collect(Collectors.joining(" OR "));
        }

        if (queryTerms.wordTerms().isEmpty()) {
            return queryTerms.rawQuery();
        }

        return queryTerms.wordTerms().stream()
                .map(term -> "\"" + term + "\"^2 OR " + term + "~1")
                .collect(Collectors.joining(" OR "));
    }

    private double blendedScore(double semanticScore, double keywordScore, boolean containsExactPhrase) {
        double exactPhraseBoost = containsExactPhrase ? 0.05 : 0.0;
        return semanticScore * (1 - this.keywordWeight) + normalizeKeywordScore(keywordScore) * this.keywordWeight + exactPhraseBoost;
    }

    private double normalizeKeywordScore(double keywordScore) {
        return keywordScore <= 0 ? 0.0 : keywordScore / (1.0 + keywordScore);
    }

    private CandidateDocument enrichKeywordCandidate(CandidateDocument candidate, QueryTerms queryTerms) {
        String normalizedText = normalizeText(candidate.document().getText());
        int keywordHits = countKeywordHits(queryTerms, normalizedText);
        double lexicalOverlap = lexicalOverlap(queryTerms, normalizedText);
        boolean containsExactPhrase = containsExactPhrase(queryTerms, normalizedText);
        return candidate.withLexicalSignals(lexicalOverlap, keywordHits, containsExactPhrase);
    }

    private double lexicalScore(QueryTerms queryTerms, CandidateDocument candidate, double rawKeywordScore) {
        double normalizedKeywordScore = normalizeKeywordScore(rawKeywordScore);
        double overlapScore = candidate.lexicalOverlap();
        double hitCoverage = queryTerms.termCount() == 0 ? 0.0 : (double) candidate.keywordHits() / queryTerms.termCount();
        double exactMatchBoost = candidate.containsExactPhrase() ? 1.0 : 0.0;
        return normalizedKeywordScore * 0.2 + overlapScore * 0.5 + hitCoverage * 0.2 + exactMatchBoost * 0.1;
    }

    private double lexicalOverlap(QueryTerms queryTerms, String normalizedText) {
        if (queryTerms.allTerms().isEmpty()) {
            return 0.0;
        }

        long overlapCount = queryTerms.allTerms().stream()
                .filter(normalizedText::contains)
                .count();
        return (double) overlapCount / queryTerms.allTerms().size();
    }

    private int countKeywordHits(QueryTerms queryTerms, String normalizedText) {
        return (int) queryTerms.allTerms().stream()
                .filter(normalizedText::contains)
                .count();
    }

    private boolean containsExactPhrase(QueryTerms queryTerms, String normalizedText) {
        if (queryTerms.normalizedRawQuery().isEmpty()) {
            return false;
        }
        return normalizedText.contains(queryTerms.normalizedRawQuery());
    }

    private QueryTerms analyzeQuery(String query) {
        String normalizedRawQuery = normalizeText(query);
        Set<String> wordTerms = Arrays.stream(query.toLowerCase(Locale.ROOT).split("\\s+"))
                .map(this::normalizeText)
                .filter(term -> term.length() > 1)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Set<String> phraseTerms = extractChinesePhrases(normalizedRawQuery);
        Set<String> allTerms = new LinkedHashSet<>();
        allTerms.addAll(wordTerms);
        allTerms.addAll(phraseTerms);
        if (allTerms.isEmpty() && StringUtils.hasText(normalizedRawQuery)) {
            allTerms.add(normalizedRawQuery);
        }

        return new QueryTerms(query, normalizedRawQuery, wordTerms, phraseTerms, allTerms);
    }

    private Set<String> extractChinesePhrases(String normalizedRawQuery) {
        Set<String> terms = new LinkedHashSet<>();
        if (!containsChinese(normalizedRawQuery)) {
            return terms;
        }

        String compact = normalizedRawQuery.replace(" ", "");
        if (compact.length() <= 2) {
            terms.add(compact);
            return terms;
        }

        for (int size = Math.min(4, compact.length()); size >= 2; size--) {
            for (int i = 0; i <= compact.length() - size; i++) {
                terms.add(compact.substring(i, i + size));
            }
        }
        return terms;
    }

    private boolean containsChinese(String value) {
        return value.codePoints().anyMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }

    private String normalizeText(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}\\p{IsPunctuation}]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private List<Double> toDoubleList(float[] embedding) {
        List<Double> values = new ArrayList<>(embedding.length);
        for (float value : embedding) {
            values.add((double) value);
        }
        return values;
    }

    private float[] toFloatArray(List<Float> values) {
        float[] array = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            array[i] = values.get(i);
        }
        return array;
    }

    private double cosineSimilarity(float[] left, float[] right) {
        if (left == null || right == null || left.length == 0 || right.length == 0 || left.length != right.length) {
            return 0.0;
        }

        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }

        if (leftNorm == 0.0 || rightNorm == 0.0) {
            return 0.0;
        }

        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    public record SearchResult(String query,
                               int count,
                               String result,
                               List<Document> documents,
                               Map<String, Object> debug) {
    }

    private record QueryTerms(String rawQuery,
                              String normalizedRawQuery,
                              Set<String> wordTerms,
                              Set<String> phraseTerms,
                              Set<String> allTerms) {

        private int termCount() {
            return allTerms.size();
        }
    }

    private enum ScoreSource {
        SEMANTIC,
        KEYWORD
    }

    private record CandidateDocument(Document document,
                                     float[] embedding,
                                     double semanticScore,
                                     double keywordScore,
                                     double lexicalOverlap,
                                     int keywordHits,
                                     boolean containsExactPhrase,
                                     double blendedScore,
                                     double mmrScore) {

        private CandidateDocument withScores(double semanticScore, double keywordScore, double blendedScore) {
            return new CandidateDocument(this.document, this.embedding, semanticScore, keywordScore, this.lexicalOverlap, this.keywordHits, this.containsExactPhrase, blendedScore, this.mmrScore);
        }

        private CandidateDocument withLexicalSignals(double lexicalOverlap, int keywordHits, boolean containsExactPhrase) {
            return new CandidateDocument(this.document, this.embedding, this.semanticScore, this.keywordScore, lexicalOverlap, keywordHits, containsExactPhrase, this.blendedScore, this.mmrScore);
        }

        private CandidateDocument mergeKeywordSignals(double keywordScore, double lexicalOverlap, int keywordHits, boolean containsExactPhrase) {
            return new CandidateDocument(this.document, this.embedding, this.semanticScore, keywordScore, lexicalOverlap, keywordHits, containsExactPhrase, this.blendedScore, this.mmrScore);
        }

        private CandidateDocument withMmrScore(double mmrScore) {
            return new CandidateDocument(this.document, this.embedding, this.semanticScore, this.keywordScore, this.lexicalOverlap, this.keywordHits, this.containsExactPhrase, this.blendedScore, mmrScore);
        }
    }
}
