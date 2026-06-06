package com.luyublog.aidemo.interfaces.http;

import com.luyublog.aidemo.application.rag.RagAnswer;
import com.luyublog.aidemo.application.rag.RagService;
import com.luyublog.aidemo.domain.retrieval.RetrievedDoc;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * RAG 端到端入口。
 *
 * <ul>
 *   <li>{@code GET /ai/rag/ask?question=...&topK=5}：同步问答，返回回答 + 引用来源</li>
 *   <li>{@code GET /ai/rag/stream?question=...&topK=5}：SSE 流式问答，仅返回 token</li>
 *   <li>{@code GET /ai/rag/retrieve?question=...&topK=5}：只跑检索，不调 LLM（调参/调试用）</li>
 * </ul>
 */
@RestController
public class RagController {

    private final RagService ragService;

    public RagController(RagService ragService) {
        this.ragService = ragService;
    }

    @GetMapping("/ai/rag/ask")
    public RagAnswer ask(@RequestParam("question") String question,
                         @RequestParam(value = "topK", defaultValue = "5") int topK) {
        return this.ragService.ask(question, topK);
    }

    @GetMapping(value = "/ai/rag/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@RequestParam("question") String question,
                               @RequestParam(value = "topK", defaultValue = "5") int topK) {
        return this.ragService.askStream(question, topK);
    }

    @GetMapping("/ai/rag/retrieve")
    public Map<String, Object> retrieve(@RequestParam("question") String question,
                                        @RequestParam(value = "topK", defaultValue = "5") int topK) {
        List<RetrievedDoc> docs = this.ragService.retrieve(question, topK);
        return Map.of("question", question, "count", docs.size(), "docs", docs);
    }
}
