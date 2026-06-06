# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build / run

Spring Boot 3.5 + Java 17 + Spring AI 1.1.7 (stable). Dependencies resolve from the user's default Maven cache (
`~/.m2`).

```bash
# Compile
./mvnw compile

# Run (port 8081)
./mvnw spring-boot:run

# Single test
./mvnw test -Dtest=ClassName#methodName
```

Active profile is `st` (see `spring.profiles.active=st`
in [application.properties](src/main/resources/application.properties)). All non-trivial config lives
in [application-st.properties](src/main/resources/application-st.properties) — that file is gitignored on purpose
because it holds Qdrant credentials.

The project previously carried a Neo4j + Gemini track alongside Qdrant; that path has been removed. The git history
still has it under `controller/`, `embed/`, `vecstore/`, `model/gemini/`, and `infrastructure/vectorstore/neo4j/` if you
ever need to revive it.

## Architecture: layered (DDD-lite / hexagonal)

The package layout enforces a one-way dependency rule:

```
interfaces  ─►  application  ─►  domain  ◄─  infrastructure
                     │                              ▲
                     └──────────────────────────────┘
```

- **`domain.*`** — pure value objects, no framework imports. Anything you `import` here from Spring is a smell.
- **`application.*`** — use-case orchestration. Holds infrastructure beans via constructor injection (Spring), but
  speaks in terms of domain types.
- **`infrastructure.*`** — outbound adapters: HTTP clients (BGE-M3), vector store clients (Qdrant), chunker
  implementations. These are the only places that touch SDK-specific types.
- **`interfaces.http`** — inbound adapters: REST controllers. They map HTTP → application service calls → HTTP response.
  No business logic.

### Package map (15 files)

```
com.luyublog.aidemo
├── AidemoApplication                                       Spring Boot entrypoint
│
├── domain
│   ├── document.Chunk                                      pre-embedding chunk (text + metadata)
│   ├── embedding.EmbedResult                               dense float[] + sparse Map<Long,Float>
│   └── retrieval.{HybridPoint, RetrievedDoc}               write/read value objects
│
├── application
│   ├── ingest.FileIngestService                            upload → chunk → List<Document>  (currently unwired; see below)
│   └── rag.{RagService, RagAnswer}                         embed query → retrieve → prompt → LLM
│
├── infrastructure
│   ├── chunker.{MarkdownChunker, PlainTextChunker}         text → List<Chunk>
│   ├── embedding.BgeM3Client                               OkHttp → FastAPI 8002 (BGE-M3)
│   └── vectorstore.qdrant.{QdrantClientConfig, QdrantHybridStore}  gRPC + dense/sparse + RRF
│
└── interfaces.http
    ├── QdrantController          /ai/qdrant/{init, upsert, query}    raw Qdrant ops (debug)
    └── RagController             /ai/rag/{ask, stream, retrieve}     end-to-end RAG
```

## Runtime layout

Three external services must be running:

| Service        | Port        | Provides                                                                                                                                               |
|----------------|-------------|--------------------------------------------------------------------------------------------------------------------------------------------------------|
| vLLM (Qwen2.5) | 8000        | OpenAI-compatible chat — called via [VllmChatClient](src/main/java/com/luyublog/aidemo/infrastructure/llm/VllmChatClient.java) (OkHttp), not Spring AI |
| BGE-M3 FastAPI | 8002        | `/encode` and `/encode_batch` returning `{dense[1024], sparse{tokenId:weight}}`                                                                        |
| Qdrant         | 6334 (gRPC) | dense + sparse hybrid storage and Query API with RRF fusion                                                                                            |

Query path:

```
user question
  → BgeM3Client.embed(q)                 →  dense[1024] + sparse{tokenId:weight}
  → QdrantHybridStore.hybridQuery(...)   →  dense + sparse prefetch → server-side RRF → TopK
  → RagService renders context           →  prompt template with {context}, {question}
  → ChatClient(OpenAiChatModel) call     →  vLLM generates answer
```

## QdrantHybridStore — the only file that touches the Qdrant SDK

[infrastructure/vectorstore/qdrant/QdrantHybridStore.java](src/main/java/com/luyublog/aidemo/infrastructure/vectorstore/qdrant/QdrantHybridStore.java).
Constraints baked in:

- **Memory-constrained collection config (1GB host)**: `ensureCollection()` sets `on_disk=true` on **four** spots, or
  the host OOMs:
    - dense `VectorParams.on_disk`
    - dense `HnswConfigDiff.on_disk` (the HNSW graph)
    - dense `HnswConfigDiff.m` (16 default; drop to 8 if RAM tight)
    - sparse `SparseIndexConfig.on_disk`
- **gRPC port 6334**, not 6333 (REST/dashboard). Docker container must expose both.
- **Fusion happens in engine** via `QueryFactory.fusion(Fusion.RRF)`; no in-process RRF math.
- **`JsonWithInt.Value` name clashes** with Spring's `@Value` — always reference it fully qualified in this file.
- Sparse `Map<Long, Float>` from BGE-M3 narrows to `Integer` for Qdrant — safe because token IDs fit in int.

## gRPC keepalive — 防"半开连接"

[QdrantClientConfig.java](src/main/java/com/luyublog/aidemo/infrastructure/vectorstore/qdrant/QdrantClientConfig.java)
不走 `QdrantGrpcClient.newBuilder(host, port, useTls)` 的便捷路径，而是显式构建 `ManagedChannel` 并配上 keepalive +
idleTimeout：

- `keepAliveTime=30s` / `keepAliveTimeout=10s` / `keepAliveWithoutCalls=true` —— 每 30s ping 一次，10s 没 ACK 就重建
- `idleTimeout=300s` —— 5 分钟无流量则主动转 IDLE，给 keepalive 兜底

**不开会怎样**：NAT / 防火墙 / 云 LB 会静默丢空闲 TCP，gRPC channel 仍以为活着；下次请求卡 19 秒左右才报
`UNAVAILABLE: Network closed for unknown reason`，看似"插入超时"。这是远程 Qdrant 部署最常见的 footgun。本地 localhost
一般不复现。

## Qdrant client transitive deps gotcha

`io.qdrant:client:1.13.0` declares its transitive deps (`grpc-protobuf`, `grpc-stub`, `grpc-netty-shaded`,
`protobuf-java`, `guava`) as **runtime scope**. Our source directly references `ListenableFuture`, `ManagedChannel`,
`GeneratedMessageV3`, etc., so they must appear on the **compile** classpath. [pom.xml](pom.xml) promotes two of them to
`compile` explicitly:

```xml
<dependency>
    <groupId>io.grpc</groupId><artifactId>grpc-protobuf</artifactId><version>1.65.1</version>
</dependency>
<dependency>
    <groupId>com.google.guava</groupId><artifactId>guava</artifactId><version>32.1.3-android</version>
</dependency>
```

If you bump `io.qdrant:client`, double-check these versions still align with the new client's expectations.

## BGE-M3 client

[infrastructure/embedding/BgeM3Client.java](src/main/java/com/luyublog/aidemo/infrastructure/embedding/BgeM3Client.java)
uses **OkHttp** (with `ConnectionPool`) and a manual `ObjectMapper.writeValueAsBytes` → `RequestBody` flow. We tried
`RestClient` and Hutool earlier; both hit body-serialization quirks that broke against the FastAPI endpoint. OkHttp's
plain `RequestBody.create(json, MediaType("application/json"))` matched Postman's bytes exactly. **Don't refactor back
to `RestClient` without verifying upsert still works.**

## RAG orchestration

[application/rag/RagService.java](src/main/java/com/luyublog/aidemo/application/rag/RagService.java) injects
`VllmChatClient` (no Spring AI in this path). System prompt + user prompt template are embedded in the service — change
there, not in a config file. The system prompt tells the model to say "不知道" when retrieved context lacks the answer.

The service exposes three entry points:

- `ask(question, topK)` — sync: returns `RagAnswer(question, answer, sources)`
- `askStream(question, topK)` — `Flux<String>` token stream (no `sources` because SSE has no place for structured
  fields; clients should call `retrieve` first)
- `retrieve(question, topK)` — embedding + Qdrant only, no LLM call (debugging / cite-only flows)

### Why VllmChatClient instead of Spring AI's OpenAiChatModel

Spring AI's `OpenAiChatModel` uses `RestClient` internally, which produces a request shape that vLLM/FastAPI rejects
with `400 "body field required, input: None"` (Pydantic strict validation; the *same* issue we hit with BGE-M3 over
`RestClient`). Real OpenAI tolerates the same request, which is why you can swap
`app.vllm.base-url=https://api.openai.com/v1` and it works. We don't want to be hostage to "works with hosted, breaks
with self-hosted", so [VllmChatClient](src/main/java/com/luyublog/aidemo/infrastructure/llm/VllmChatClient.java) sends
the JSON via OkHttp directly (same pattern as `BgeM3Client`). The cost: lose Spring AI's `ChatClient` fluent API and
built-in streaming — streaming is re-implemented with OkHttp `EventSource` (`okhttp-sse` artifact).

`spring-ai-commons` is kept as an explicit dep purely for `TokenTextSplitter` and `Document` in the chunker; that jar
has no auto-config and no HTTP code, so the chat-side issue doesn't apply.

## Ingest pipeline (currently unwired)

[application/ingest/FileIngestService.java](src/main/java/com/luyublog/aidemo/application/ingest/FileIngestService.java)
dispatches by suffix and returns `List<org.springframework.ai.document.Document>`:

- `.md` /
  `.markdown` → [MarkdownChunker](src/main/java/com/luyublog/aidemo/infrastructure/chunker/MarkdownChunker.java):
  line-scan state machine over headings (`#{1,6}`) and bullets (`-/*/+`). **Granularity defaults to section-level** — a
  whole bullet group becomes one chunk — and falls back to per-bullet chunks only when `isWeaklyDependent(...)` returns
  true (group total ≥ 400 chars AND no strong-order markers like "第N步", "首先/然后/最后", `\d+[.、)]`). chunk text is *
  *prefixed** by `"parentHeadings - heading：内容"`. Short bullets (< 8 chars) in bullet-level mode merge via `；`. Code
  fences `` ``` `` are passed through as prose.
- `.txt` → [PlainTextChunker](src/main/java/com/luyublog/aidemo/infrastructure/chunker/PlainTextChunker.java):
  paragraph-aware. Splits by blank lines (`\n\n+`); paragraphs ≤ 500 tokens become single chunks; longer paragraphs
  split by Chinese/English sentence boundaries (`。！？/.!?`) and greedily pack to 300~500 tokens. Tiny consecutive
  paragraphs merge into the previous chunk (avoids noise). **No Spring AI `TokenTextSplitter` anymore** — it sliced
  mid-Chinese-sentence.
- Both chunkers attach metadata: `chunkType` (`bullet`/`section`/`prose`/`code`), `granularity` (`bullet`/`section`/
  `paragraph`), `tokenCount` (rough estimate via `MarkdownChunker.estimateTokens`: `汉字 * 1.4 + 英文字符 * 0.3`),
  `headingLevel` (md only). The shared [Chunk](src/main/java/com/luyublog/aidemo/domain/document/Chunk.java) record
  lives in `domain.document`.

**Why default to section-level**: bullet siblings often have strong logical/sequence dependencies (steps, recipes,
derivations). Splitting them and retrieving one without the others gives the LLM useless context. There's no native
mechanism in Qdrant to "fetch sibling chunks" — implementing parent-doc retrieval would be a big change. Better to keep
bullets together unless they're clearly independent (`isWeaklyDependent`).

**No controller currently calls `FileIngestService`** — the previous Neo4j upload endpoint was removed along with the
Neo4j track. The fastest way to wire file upload → Qdrant is:

1. Add a `POST /ai/qdrant/upload`
   to [QdrantController](src/main/java/com/luyublog/aidemo/interfaces/http/QdrantController.java)
2. In that handler: `ingestService.ingest(file)` → for each `Document`, embed the text via `BgeM3Client.embedBatch`,
   build `HybridPoint`, call `QdrantHybridStore.upsertBatch`

## Configuration knobs

In [application-st.properties](src/main/resources/application-st.properties):

- `app.bgem3.{base-url, batch-size, pool.max-idle, pool.keep-alive-seconds, timeout-seconds}` — BGE-M3 client and OkHttp
  pool
-
`app.qdrant.{host, grpc-port, api-key, collection, dense-name, sparse-name, dense-dim, hnsw-m, initialize-schema, call-timeout-seconds, keepalive-seconds, keepalive-timeout-seconds, idle-timeout-seconds}` —
Qdrant client, collection, and gRPC channel keepalive
- `app.vllm.{base-url, api-key, model, temperature, timeout-seconds, pool.max-idle, pool.keep-alive-seconds}` — vLLM
  endpoint (api-key is a dummy; vLLM doesn't check). Switch `base-url` to real OpenAI / DeepSeek / etc. to use a hosted
  endpoint
- `spring.servlet.multipart.max-file-size=20MB` — upload limit (legacy, kept for future ingestion endpoint)
