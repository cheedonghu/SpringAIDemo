package com.luyublog.aidemo.application.rag;

import com.luyublog.aidemo.domain.embedding.EmbedResult;
import com.luyublog.aidemo.domain.retrieval.RetrievedDoc;
import com.luyublog.aidemo.infrastructure.embedding.BgeM3Client;
import com.luyublog.aidemo.infrastructure.llm.VllmChatClient;
import com.luyublog.aidemo.infrastructure.vectorstore.qdrant.QdrantHybridStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * RAG 编排：BGE-M3 编码 → Qdrant 混合检索 → 拼 prompt → vLLM 生成。
 *
 * <p>chat 不走 Spring AI：之前 Spring AI {@code OpenAiChatModel} 内部用 {@code RestClient}
 * 调 vLLM 会触发 400 {@code body field required}（同 BGE-M3 那次踩的坑）。改用
 * {@link VllmChatClient}，跟 BGE-M3 同一种 OkHttp 范式。
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private static final String SYSTEM_PROMPT = """
            你是一个严谨的问答助手。基于"资料"段落里的内容回答用户问题。
            - 如果资料里没有足够信息回答，请如实说"不知道"，不要编造。
            - 回答尽量简洁，引用资料里的原话或事实即可。
            """;

    private static final String USER_PROMPT_TEMPLATE = """
            资料：
            %s
            
            问题：%s
            """;

    private final BgeM3Client bgeM3Client;
    private final QdrantHybridStore qdrantStore;
    private final VllmChatClient chatClient;

    public RagService(BgeM3Client bgeM3Client,
                      QdrantHybridStore qdrantStore,
                      VllmChatClient chatClient) {
        this.bgeM3Client = bgeM3Client;
        this.qdrantStore = qdrantStore;
        this.chatClient = chatClient;
    }

    /**
     * 同步问答：完整答案 + 检索到的来源 chunk。
     */
    public RagAnswer ask(String question, int topK) {
        List<RetrievedDoc> docs = retrieve(question, topK);
        String userPrompt = renderUserPrompt(question, renderContext(docs));
        String answer = this.chatClient.chat(SYSTEM_PROMPT, userPrompt);
        return new RagAnswer(question, answer, docs);
    }

    /**
     * 流式问答：返回 token 级 {@link Flux}，controller 可直接以 SSE 输出。
     * 注意此变体不一起返回 sources（流式响应里很难塞结构化字段）；
     * 如需引用，可让前端先调 {@link #retrieve} 拿到 sources，再调本方法生成正文。
     */
    public Flux<String> askStream(String question, int topK) {
        return streamAnswer(question, retrieve(question, topK));
    }

    /**
     * 用已检索好的 docs 渲染 prompt 并流式生成。把"检索"与"生成"拆开，方便上层
     * （如可续传 SSE 的 {@code ChatStreamService}）先单独拿到 docs 落库 sources，再触发生成。
     * prompt 拼接逻辑只此一处，避免在别处重复 SYSTEM_PROMPT。
     */
    public Flux<String> streamAnswer(String question, List<RetrievedDoc> docs) {
        String userPrompt = renderUserPrompt(question, renderContext(docs));
        return this.chatClient.chatStream(SYSTEM_PROMPT, userPrompt);
    }

    /**
     * 仅做检索，不调 LLM。给前端预览来源、给 stream 接口先拉 sources 用。
     */
    public List<RetrievedDoc> retrieve(String question, int topK) {
        EmbedResult embedding = this.bgeM3Client.embed(question);
        int prefetch = Math.max(topK * 4, 20);
        List<RetrievedDoc> docs = this.qdrantStore.hybridQuery(embedding, topK, prefetch);
        log.debug("[rag] retrieved {} docs for question='{}' (topK={}, prefetch={})",
                docs.size(), question, topK, prefetch);
        return docs;
    }

    private String renderUserPrompt(String question, String context) {
        return USER_PROMPT_TEMPLATE.formatted(context, question == null ? "" : question);
    }

    /**
     * 把 TopK chunk 拼成模型可读的资料段：编号 + 来源 metadata + 原文。
     */
    private String renderContext(List<RetrievedDoc> docs) {
        if (docs.isEmpty()) {
            return "(无相关资料)";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < docs.size(); i++) {
            RetrievedDoc d = docs.get(i);
            String source = String.valueOf(d.payload().getOrDefault("source", "unknown"));
            sb.append('[').append(i + 1).append("] (source: ").append(source).append(") ")
                    .append(d.text())
                    .append('\n');
        }
        return sb.toString();
    }
}
