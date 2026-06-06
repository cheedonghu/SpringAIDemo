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

### Package map

```
com.luyublog.aidemo
├── AidemoApplication                                       Spring Boot entrypoint (@MapperScan persistence.mysql)
│
├── domain
│   ├── document.Chunk                                      pre-embedding chunk (text + metadata)
│   ├── embedding.EmbedResult                               dense float[] + sparse Map<Long,Float>
│   ├── retrieval.{HybridPoint, RetrievedDoc}               write/read value objects
│   └── conversation.{Conversation, Message, MessageStatus, StreamEvent}  chat value objects + neutral stream event (pure)
│
├── application
│   ├── ingest.{FileIngestService, IngestResult}            upload → chunk → embed → upsert  (wired via POST /ai/qdrant/upload)
│   ├── rag.{RagService, RagAnswer}                         embed query → retrieve → prompt → LLM
│   └── chat.{ChatStreamService, ChatGenerationConfig, MessageNotFoundException}  resumable SSE orchestration (bg generate → Redis Stream + persist)
│
├── infrastructure
│   ├── chunker.{MarkdownChunker, PlainTextChunker}         text → List<Chunk>
│   ├── embedding.BgeM3Client                               OkHttp → FastAPI 8002 (BGE-M3)
│   ├── vectorstore.qdrant.{QdrantClientConfig, QdrantHybridStore}  gRPC + dense/sparse + RRF
│   ├── persistence.mysql.{ConversationMapper, MessageMapper}       MyBatis (XML in resources/mapper)
│   └── cache.redis.{RedisStreamConfig, RedisTokenStreamStore}      Redis Stream token buffer (resume)
│
└── interfaces.http
    ├── QdrantController          /ai/qdrant/{init, upsert, query, upload}   raw Qdrant ops (debug) + file ingest
    ├── RagController             /ai/rag/{ask, stream, retrieve}            end-to-end RAG (stream = simple, non-resumable)
    └── ChatStreamController      /ai/rag/messages, /ai/rag/messages/{id}/stream   resumable SSE (Last-Event-ID)
```

## Runtime layout

External services. vLLM / BGE-M3 / Qdrant are required for RAG; MySQL + Redis are required for the resumable-SSE /
persistence path (`ChatStreamController`):

| Service        | Port        | Provides                                                                                                                                               |
|----------------|-------------|--------------------------------------------------------------------------------------------------------------------------------------------------------|
| vLLM (Qwen2.5) | 8000        | OpenAI-compatible chat — called via [VllmChatClient](src/main/java/com/luyublog/aidemo/infrastructure/llm/VllmChatClient.java) (OkHttp), not Spring AI |
| BGE-M3 FastAPI | 8002        | `/encode` and `/encode_batch` returning `{dense[1024], sparse{tokenId:weight}}`                                                                        |
| Qdrant         | 6334 (gRPC) | dense + sparse hybrid storage and Query API with RRF fusion                                                                                            |
| MySQL          | 3306        | conversation/message persistence via MyBatis (schema in `resources/db/schema.sql`)                                                                     |
| Redis          | 6379        | resumable-SSE token buffer (Redis Stream); generation survives client disconnect                                                                       |

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

The service exposes four entry points:

- `ask(question, topK)` — sync: returns `RagAnswer(question, answer, sources)`
- `askStream(question, topK)` — `Flux<String>` token stream (no `sources` because SSE has no place for structured
  fields; clients should call `retrieve` first)
- `streamAnswer(question, docs)` — same as `askStream` but takes **pre-retrieved** docs, so callers can grab `sources`
  first then trigger generation. Prompt assembly (SYSTEM_PROMPT + template) lives only here; `askStream` delegates to
  it.
  Used by `ChatStreamService` (resumable SSE) to persist sources separately from generation.
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

## Resumable SSE (断点续传) — ChatStreamService + Redis Stream

The plain `GET /ai/rag/stream` (RagController) is fire-and-forget: it returns a raw `Flux<String>` bound to the client
connection, and `VllmChatClient.chatStream` does `sink.onDispose(source::cancel)` — **disconnect kills generation**. No
event ids, nothing to resume from.

The resumable path **decouples generation from delivery**, and the three layers stay strictly separated — the key being
that **the neutral `StreamEvent` carries data across layers, so Redis/SSE SDK types never leak upward**:

- **Two endpoints**
  on [ChatStreamController](src/main/java/com/luyublog/aidemo/interfaces/http/ChatStreamController.java):
  - `POST /ai/rag/messages` `{question, topK}` → creates `conversation` + a user `message` + an assistant `message`
    (status `GENERATING`), kicks off background generation, returns `{conversationId, messageId}` immediately.
  - `GET /ai/rag/messages/{id}/stream` → `SseEmitter`, reads `Last-Event-ID` header for resume.
- **Generation runs on a background executor** (`chatGenerationExecutor` in `ChatGenerationConfig`), **not** on the SSE
  subscription — this is what makes resume possible.
  [ChatStreamService](src/main/java/com/luyublog/aidemo/application/chat/ChatStreamService.java)`.generate()` consumes
  `ragService.streamAnswer(...)` via `toIterable()` (blocking on the bg thread), and for each token does
  `RedisTokenStreamStore.append(messageId, token)` → `XADD rag:stream:{id} * c <token>`. On completion: `markDone`
  (`XADD ... event=done` + `EXPIRE ttl`) and `MessageMapper.updateOutcome(DONE, fullText, sourcesJson, tokenCount)`. On
  error: `markError` + status `FAILED`.
- **Layer split for the read/tail path** — this was deliberately refactored so no SDK type crosses a boundary:
  - **infrastructure
    ** [RedisTokenStreamStore](src/main/java/com/luyublog/aidemo/infrastructure/cache/redis/RedisTokenStreamStore.java):
    the *only* place that touches Redis SDK types. `subscribe(messageId, lastEventId, Consumer<StreamEvent>)` wires a
    `StreamMessageListenerContainer` ([RedisStreamConfig](src/main/java/com/luyublog/aidemo/infrastructure/cache/redis/RedisStreamConfig.java))
    — no consumer group, equivalent to `XREAD` from an offset — translates each `MapRecord` into a neutral
    [StreamEvent](src/main/java/com/luyublog/aidemo/domain/conversation/StreamEvent.java) (`TOKEN`/`DONE`/`ERROR`, with
    the entry id as `StreamEvent.id`), and returns the `Subscription` wrapped as a plain `AutoCloseable`. Field
    decoding (`c` / `event=done|error`) lives here only. `lastEventId` blank → offset `"0"` (first connect, replay all);
    non-blank → offset = that id (reconnect, replay only the gap), then tail.
  - **application** `ChatStreamService.openStream(...)`: orchestration in neutral terms only. Decides Redis-tail vs
    MySQL-fallback (`streamStore.exists`), stops the subscription on `DONE`/`ERROR` or when the sink throws, throws
    `MessageNotFoundException` when neither Redis nor DB has it. Imports **no** Redis/SSE types.
  - **interface** `ChatStreamController.emit(...)`: turns a `StreamEvent` into an `SseEmitter` frame
    (`event().id().data()`, `name("done")`/`name("error")`), maps `MessageNotFoundException` → 404, and on every
    terminal path (onCompletion/onTimeout/onError) closes the `AutoCloseable` so the subscription can't leak. A failed
    `send` (client gone) is rethrown as `UncheckedIOException` so `ChatStreamService` stops the subscription at once.
- **TTL fallback to MySQL.** If the client reconnects after the stream's TTL expired (`exists()` false), `openStream`
  reads the persisted message and replays the full content as one `StreamEvent` (status `DONE`) — generation was never
  lost because it was persisted on completion.

Why this split: an earlier version did the subscribe + record-translation + lifecycle all inside the controller, which
made `interfaces` import `MapRecord`/`Subscription`/`RedisTokenStreamStore` — violating "interfaces have no business
logic" and "infrastructure is the only place that touches SDK types". Pushing translation down to infrastructure and
orchestration into application, with `StreamEvent` (a `domain` type) as the lingua franca, restores the one-way
dependency rule. `SseEmitter` stays in the controller because it *is* the outbound protocol.

Why `SseEmitter` + `StreamMessageListenerContainer` instead of a reactive `Flux<ServerSentEvent>`: the container is
push/callback-based and feeds the emitter naturally, and it handles both backlog replay and live tail from a single
offset. `VllmChatClient` is **unchanged** — its one-shot stream is fine because the *background* subscriber, not the
client, drives it.

## Ingest pipeline

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

**Wired via `POST /ai/qdrant/upload`**
on [QdrantController](src/main/java/com/luyublog/aidemo/interfaces/http/QdrantController.java) (multipart form field
name
`file`). Per the layering rule, the controller holds **no business logic** — it just delegates to
`FileIngestService.ingestAndStore(file)` and returns the `IngestResult`. The orchestration lives in the **application**
layer (same pattern as `RagService`): `FileIngestService` injects `BgeM3Client` + `QdrantHybridStore` and does
`ingest(file)` (chunk) → `embedBatch` over all chunk texts → build one `HybridPoint` per chunk (carrying
`Document.getMetadata()` as payload) → `QdrantHybridStore.upsertBatch`. It returns
[IngestResult](src/main/java/com/luyublog/aidemo/application/ingest/IngestResult.java) `(source, chunks, upserted)`.

`ingest(file)` stays a side-effect-free chunking step (reusable / debuggable); `ingestAndStore` composes it with embed +
store. Type validation lives in `ingest` (415 for unsupported suffix, 400 for empty file); `ingestAndStore` guards
against a chunk-count vs embedding-count mismatch. Collection must exist first (`POST /ai/qdrant/init` or
`app.qdrant.initialize-schema=true`).

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
- `spring.datasource.{url, username, password, driver-class-name}` +
  `mybatis.{mapper-locations, configuration.map-underscore-to-camel-case}` —
  MySQL + MyBatis for conversation/message persistence
- `spring.data.redis.{host, port, password, database}` — Redis (resumable-SSE token buffer)
- `app.chat.stream.{key-prefix, ttl-seconds, emitter-timeout-ms}` — Redis Stream key prefix, post-completion TTL, and
  SSE emitter timeout
- `spring.servlet.multipart.max-file-size=20MB` — upload limit for `POST /ai/qdrant/upload`

A secret-free template lives
at [application-st.properties.example](src/main/resources/application-st.properties.example)
(the real `application-st.properties` is gitignored). DDL is in
[db/schema.sql](src/main/resources/db/schema.sql) — run it before first use (or wire `spring.sql.init`).
