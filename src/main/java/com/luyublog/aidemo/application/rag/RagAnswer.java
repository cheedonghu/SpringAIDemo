package com.luyublog.aidemo.application.rag;

import com.luyublog.aidemo.domain.retrieval.RetrievedDoc;

import java.util.List;

/**
 * RAG 同步问答的返回体。
 *
 * <ul>
 *   <li>{@code question}：原始用户问题</li>
 *   <li>{@code answer}：LLM 基于检索资料生成的回答</li>
 *   <li>{@code sources}：参与 prompt 拼接的 TopK chunk，方便前端做引用展示 / 调试</li>
 * </ul>
 */
public record RagAnswer(String question, String answer, List<RetrievedDoc> sources) {
}
