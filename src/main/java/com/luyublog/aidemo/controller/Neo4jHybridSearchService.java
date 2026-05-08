package com.luyublog.aidemo.controller;

import org.neo4j.driver.*;
import org.neo4j.driver.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Neo4j 混合召回：关键词检索 + 语义检索 + MMR 去重重排
 *
 * 整体流程：
 *   1) 语义检索：基于向量索引（db.index.vector.queryNodes）按余弦相似度召回候选；
 *   2) 关键词检索：基于全文索引（db.index.fulltext.queryNodes）按 BM25 召回候选，并按词面重叠/命中数过滤；
 *   3) 合并打分：对两路候选按 id 合并，融合语义分与关键词分得到 blendedScore；
 *   4) MMR 重排：在相关度与多样性之间做权衡，避免返回内容高度相似的结果。
 */
@Service
public class Neo4jHybridSearchService {

    private static final Logger log = LoggerFactory.getLogger(Neo4jHybridSearchService.class);

    // 与 Spring AI Neo4j 向量库默认存储约定一致的节点属性名
    private static final String ID_PROPERTY = "id";
    private static final String TEXT_PROPERTY = "text";
    private static final String METADATA_PROPERTY = "metadata";

    private final Driver driver;
    private final EmbeddingModel embeddingModel;
    private final String databaseName;
    private final String vectorIndexName;       // 向量索引名（语义检索用）
    private final String keywordIndexName;      // 全文索引名（关键词检索用）
    private final String label;
    private final String embeddingProperty;     // 节点上存放向量的属性名
    private final double keywordWeight;         // 关键词得分在 blendedScore 中的权重
    private final double minLexicalOverlap;     // 关键词候选最小词面重叠率，过滤纯 BM25 噪声
    private final int minKeywordHits;           // 关键词候选最小命中词数，过滤纯 BM25 噪声

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

    /**
     * 混合检索入口。
     *
     * @param query               用户原始查询
     * @param topK                最终返回的文档数量
     * @param similarityThreshold 语义检索阶段的最小相似度阈值
     * @param mmrLambda           MMR 重排中相关度 vs 多样性的权衡系数，越大越偏相关度
     */
    public SearchResult search(String query, int topK, double similarityThreshold, double mmrLambda) {
        // 取 topK 的若干倍作为候选池上限，给后续合并与重排留出选择空间
        int candidateLimit = Math.max(topK * 4, 10);
        // 计算查询向量（语义检索用）以及解析查询词项（关键词检索 + 词面打分用）
        float[] queryEmbedding = this.embeddingModel.embed(query);
        QueryTerms queryTerms = analyzeQuery(query);

        // 两路独立召回
        List<CandidateDocument> semanticCandidates = semanticSearch(queryEmbedding, candidateLimit, similarityThreshold);
        List<CandidateDocument> keywordCandidates = keywordSearch(queryTerms, candidateLimit);

        // 按文档 id 合并并融合分数
        Map<String, CandidateDocument> mergedCandidates = mergeCandidates(queryTerms, semanticCandidates, keywordCandidates, queryEmbedding);
        // 用 MMR 在合并候选中挑选最终 topK，兼顾相关性与多样性
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

    /**
     * 语义检索：调用 Neo4j 向量索引，返回相似度 >= 阈值的 Top-N 候选。
     * 同时把节点上的 embedding 一并取出，方便后续 MMR 阶段计算文档间相似度。
     */
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

        log.debug("[semantic] cypher={} params={{indexName={}, candidateLimit={}, similarityThreshold={}, embeddingDim={}}}",
                compactCypher(cypher), this.vectorIndexName, candidateLimit, similarityThreshold, queryEmbedding.length);

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

    /**
     * 关键词检索：使用 Neo4j 全文索引（Lucene 语法）召回，再用词面信号做二次过滤。
     * 全文索引的 BM25 分数对短查询/罕见词容易误命中，这里通过 lexicalOverlap / keywordHits
     * 做一层硬过滤，剔除"明明没几个词命中却 BM25 偏高"的噪声候选。
     */
    private List<CandidateDocument> keywordSearch(QueryTerms queryTerms, int candidateLimit) {
        String keywordQuery = toKeywordQuery(queryTerms);
        if (!StringUtils.hasText(keywordQuery)) {
            // 查询为空（例如全是停用词/单字符）则跳过关键词通道
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

        log.debug("[keyword] cypher={} params={{indexName={}, query={}, candidateLimit={}}}",
                compactCypher(cypher), this.keywordIndexName, keywordQuery, candidateLimit);

        try (Session session = this.driver.session(SessionConfig.forDatabase(this.databaseName))) {
            List<CandidateDocument> rawCandidates = session.executeRead(tx -> tx.run(cypher, Values.parameters(
                            "indexName", this.keywordIndexName,
                            "query", keywordQuery,
                            "candidateLimit", candidateLimit
                    ))
                    .list(record -> toCandidateDocument(record, ScoreSource.KEYWORD)));

            return rawCandidates.stream()
                    // 计算词面重叠率、命中词数、是否包含完整短语等 lexical 信号
                    .map(candidate -> enrichKeywordCandidate(candidate, queryTerms))
                    // 至少满足"词面重叠达标"或"命中词数达标"二者之一才保留
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

    /**
     * 将一条候选合入结果 Map。同一 id 的两路候选要做信号合并：
     * - 语义分取两路最大值；如仍为 0（关键词通道带来的候选没有语义分），则补算余弦相似度；
     * - 关键词分及词面信号取两路最大值；
     * - 重新计算 blendedScore 以反映合并后的完整信号。
     */
    private void mergeCandidate(Map<String, CandidateDocument> merged, CandidateDocument incoming, QueryTerms queryTerms, float[] queryEmbedding) {
        String candidateId = incoming.document().getId();
        CandidateDocument existing = merged.get(candidateId);
        if (existing == null) {
            // 首次入池：若来自关键词通道则补算余弦相似度，保证语义分始终可用
            double normalizedSemanticScore = incoming.semanticScore() > 0 ? incoming.semanticScore() : cosineSimilarity(queryEmbedding, incoming.embedding());
            double normalizedKeywordScore = lexicalScore(queryTerms, incoming, incoming.keywordScore());
            double blendedScore = blendedScore(normalizedSemanticScore, normalizedKeywordScore, incoming.containsExactPhrase());
            merged.put(candidateId, incoming.withScores(normalizedSemanticScore, normalizedKeywordScore, blendedScore));
            return;
        }

        // 已存在：两路命中同一文档，取各信号的最大值进行融合
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

    /**
     * MMR（Maximal Marginal Relevance）重排：
     *   mmrScore = λ * 相关度 - (1 - λ) * 与已选集合的最大相似度
     * 每轮挑出 mmrScore 最高的候选加入结果，直到达到 topK 或候选耗尽。
     * 目的是避免 topK 全是几乎相同表述的"近重复"文档。
     */
    private List<CandidateDocument> mmrSelect(Collection<CandidateDocument> candidates, float[] queryEmbedding, int topK, double mmrLambda) {
        // 仅保留有 embedding 的候选（无向量则无法计算多样性惩罚），并按 blendedScore 预排序
        List<CandidateDocument> remaining = candidates.stream()
                .filter(candidate -> candidate.embedding() != null && candidate.embedding().length > 0)
                .sorted(Comparator.comparingDouble(CandidateDocument::blendedScore).reversed())
                .collect(Collectors.toCollection(ArrayList::new));

        List<CandidateDocument> selected = new ArrayList<>();
        while (!remaining.isEmpty() && selected.size() < topK) {
            CandidateDocument bestCandidate = null;
            double bestScore = Double.NEGATIVE_INFINITY;

            for (CandidateDocument candidate : remaining) {
                // 相关度：优先用融合后的 blendedScore，缺失时退化为与 query 的余弦相似度
                double relevance = candidate.blendedScore() > 0
                        ? candidate.blendedScore()
                        : cosineSimilarity(queryEmbedding, candidate.embedding());
                // 多样性惩罚：与已选结果中最相似那一篇的相似度，越大越"重复"
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

            // 选中后从候选池移除，避免重复选入
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

    /**
     * 把解析得到的查询词项拼成 Lucene 全文索引可识别的查询串：
     *   - 若提取到中文 n-gram 短语，则用 "短语" OR "短语" 形式精确匹配；
     *   - 否则对英文词使用 "词"^2 加权 + 词~1 模糊匹配，提高拼写容错。
     */
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

    /**
     * 融合公式：
     *   blended = semantic * (1 - w) + normalize(keyword) * w + 命中完整短语的奖励
     * 关键词分先做 x/(1+x) 归一化，把 BM25 不定上界压到 [0, 1)，使其与余弦相似度量纲可比。
     */
    private double blendedScore(double semanticScore, double keywordScore, boolean containsExactPhrase) {
        double exactPhraseBoost = containsExactPhrase ? 0.05 : 0.0;
        return semanticScore * (1 - this.keywordWeight) + normalizeKeywordScore(keywordScore) * this.keywordWeight + exactPhraseBoost;
    }

    /** BM25 分数没有上界，这里用 x/(1+x) 压到 [0,1)，便于与语义分加权融合。 */
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

    /**
     * 计算"关键词维度"的综合得分（用于替代原始 BM25 进入 blendedScore）：
     *   归一化 BM25(0.2) + 词面重叠率(0.5) + 命中词覆盖率(0.2) + 精确短语命中(0.1)
     * 词面重叠权重最高，因为它对"是否真的命中查询里的词"判断最稳定。
     */
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

    /**
     * 解析查询：
     *   - wordTerms：按空格切分得到的英文/数字词，过滤单字符；
     *   - phraseTerms：中文 n-gram（2~4 字滑窗）短语，弥补缺少分词器时的中文召回；
     *   - allTerms：上述两者的并集，给词面重叠/命中数计算使用。
     */
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
            // 兜底：极短查询直接整串作为一个 term
            allTerms.add(normalizedRawQuery);
        }

        return new QueryTerms(query, normalizedRawQuery, wordTerms, phraseTerms, allTerms);
    }

    /**
     * 中文 n-gram 短语提取：在没有专业中文分词器的前提下，用 2~4 字滑窗近似得到候选短语，
     * 既覆盖"模型/向量/索引"这类双字词，也能捕获"向量数据库"这类长词组合。
     */
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

        // 从 4-gram 到 2-gram 依次切，长短语优先入集合
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

    private String compactCypher(String cypher) {
        return cypher.replace('\n', ' ').replaceAll("\\s+", " ").trim();
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
