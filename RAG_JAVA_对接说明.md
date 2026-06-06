# RAG 检索链路 —— Java / Spring AI 对接交接文档

> 这份文档交给 Claude Code，用于实现 Spring AI 应用中的检索与生成链路。
> 阅读顺序：先看「架构总览」理解全局，再看「待实现清单」逐项落地，
> 「关键约束」一节是最容易踩坑的地方，实现前务必读完。

---

## 1. 架构总览

整个系统由 **3 个独立服务** + **1 个 Spring AI 应用**组成，全部在同一台机器（生产为单张 A100，本地开发为单张 RTX 4090）：

| 服务               | 端口   | 职责                                | 由谁调用                     |
|------------------|------|-----------------------------------|--------------------------|
| vLLM (Qwen2.5)   | 8000 | 大模型生成（chat），OpenAI 兼容接口           | Spring AI 的 `ChatClient` |
| FlagEmbedding 服务 | 8002 | BGE-M3 编码，**同时返回 dense + sparse** | 自写的 `BgeM3Client`（HTTP）  |
| Qdrant           | 6333 | 向量库，存 dense + sparse 双向量，做混合检索    | 自写的 Qdrant Java 客户端      |
| Spring AI 应用     | -    | 业务编排：检索 + 生成                      | -                        |

数据流（查询时）：

```
用户问题
  → BgeM3Client 调 8002 → 拿到 {dense[1024], sparse{tokenId:weight}}
  → Qdrant 客户端调 6333 → dense + sparse 混合检索(RRF) → 拿回 TopK chunk
  → 把 chunk 拼进 prompt
  → ChatClient 调 8000 的 Qwen → 生成回答
```

数据流（灌库时）：

```
文档 → 切片(chunk) → 批量调 8002 /encode_batch → 每个 chunk 拿到 dense + sparse
  → Qdrant 客户端批量 upsert(dense + sparse 一起写入)
```

---

## 2. 已经完成的部分（不需要再做）

这些已经验证可用，Java 侧直接当成现成接口调用即可：

### 2.1 vLLM 生成服务（端口 8000）

- 模型：`Qwen/Qwen2.5-7B-Instruct-AWQ`（本地开发用 7B，生产可换 14B/32B）
- OpenAI 兼容接口，路径 `/v1/chat/completions`
- 无需鉴权（`api-key` 随便填一个占位字符串即可）

### 2.2 FlagEmbedding 编码服务（端口 8002）

已用 FastAPI 实现并验证通过。**这是关键，它解决了 Spring AI 无法输出 sparse 的问题。** 接口如下：

**健康检查**

```
GET http://localhost:8002/health
→ {"status":"ok","model_loaded":true}
```

**单条编码（查询时用）**

```
POST http://localhost:8002/encode
Body: {"text": "对账渠道有哪些"}
→ {
    "dense": [0.01, -0.03, ...],          // 长度 1024 的 float 数组
    "sparse": {"128": 0.21, "9043": 0.18} // {tokenId(int) : weight(float)}
  }
```

**批量编码（灌库时用，效率高）**

```
POST http://localhost:8002/encode_batch
Body: {"texts": ["chunk1", "chunk2"], "batch_size": 12}
→ {"results": [ {dense,sparse}, {dense,sparse} ]}
```

> 注意：JSON 里 sparse 的 key 会被序列化成字符串（如 "128"），
> Java 反序列化后需要转成 long/int 作为 Qdrant 的 sparse 索引。

---

## 3. 待实现清单（Java 侧，这是 Claude Code 的工作）

按顺序实现，每步可独立验证。

### 任务 A：BgeM3Client —— 调用 8002 编码服务

写一个普通的 Spring `@Service`（**不要实现 Spring AI 的 `EmbeddingModel` 接口**，原因见第 4 节）：

- 用 `RestClient`（Spring 6 自带）向 `http://localhost:8002` 发请求
- 定义返回类型：`record EmbedResult(float[] dense, Map<Long, Float> sparse) {}`
- 提供两个方法：
    - `EmbedResult embed(String text)` → 调 `/encode`
    - `List<EmbedResult> embedBatch(List<String> texts)` → 调 `/encode_batch`
- 反序列化时把 sparse 的 String key 转成 Long

### 任务 B：Qdrant 接入 —— 建 collection + 灌数据 + 检索

用 **Qdrant 官方 Java 客户端**（Maven: `io.qdrant:client`），**不要用 Spring AI 的 `QdrantVectorStore`**（原因见第 4 节）。

**B1. 建 collection（双向量结构）**

- 一个 dense 向量字段：维度 **1024**，距离度量 **Cosine**
- 一个 sparse 向量字段：用于 BGE-M3 的 lexical weights
- collection 名例如 `rag_docs`

**B2. upsert（灌库）**

- 每个 point 同时写入 dense 向量 + sparse 向量 + payload（payload 存原文 chunk 文本、来源文件名等元数据，检索后要用）
- 用批量 upsert，配合任务 A 的 `embedBatch`

**B3. 混合检索（hybrid query）**

- 用 Qdrant 的 Query API：dense 一路 + sparse 一路分别 prefetch，再用 **RRF（Reciprocal Rank Fusion）** 融合
- 返回 TopK（建议先取 K=5），从 payload 里取回 chunk 原文

### 任务 C：检索 + 生成编排

写业务 `@Service` 把整条链路串起来：

1. 接收用户问题
2. 调 `BgeM3Client.embed(question)` 得到 dense + sparse
3. 调 Qdrant 混合检索拿回 TopK chunk
4. 把 chunk 文本拼成上下文，构造 prompt（建议模板：「基于以下资料回答问题，资料：{chunks}，问题：{question}」）
5. 调 Spring AI 的 `ChatClient`（指向 8000）生成回答
6. 返回回答（如需流式，用 `ChatClient` 的 stream 能力）

### 任务 D：Spring AI 配置

`application.yml` 里只配 **chat** 这一路指向 vLLM 8000：

```yaml
spring:
  ai:
    openai:
      base-url: http://localhost:8000
      api-key: dummy            # vLLM 不校验
      chat:
        options:
          model: Qwen/Qwen2.5-7B-Instruct-AWQ
          temperature: 0.3
```

> 注意：**不要**在这里配 embedding，embedding 不走 Spring AI（见第 4 节）。

---

## 4. 关键约束（最容易踩坑，实现前必读）

### 4.1 为什么不用 Spring AI 的 EmbeddingModel

Spring AI 的 `EmbeddingModel` 接口契约只能返回 `float[]`（dense 向量），**类型系统里没有放 sparse 的地方**。BGE-M3 的核心价值是
dense + sparse 一体做混合检索，所以 embedding 这一段必须绕过 Spring AI 的抽象，由 `BgeM3Client` 直接对接 8002 服务。

### 4.2 为什么不用 Spring AI 的 QdrantVectorStore

Spring AI 的 `QdrantVectorStore` 内部依赖 `EmbeddingModel`（dense）+ 单向量存储，**不支持 sparse / 混合检索**。要做
hybrid，必须降到 Qdrant 原生 Java 客户端这一层，自己控制 dense + sparse 双向量的写入与查询。

### 4.3 Spring AI 只负责生成，不负责检索

记住这条分界线：

- **生成段**（ChatClient → 8000）：走 Spring AI 标准用法
- **检索段**（编码 + 向量库）：完全自写，不碰 Spring AI 抽象

### 4.4 sparse 向量的格式对齐

- 8002 返回的 sparse 是 `{tokenId: weight}`
- Qdrant 的 sparse 向量也是 `(indices[], values[])` 形式，indices 就是 tokenId
- 所以从 8002 拿到的 sparse 可以直接映射到 Qdrant，**不需要做 tokenizer 配对**（这是用 FlagEmbedding 而非 vLLM
  token_classify 的好处，已在前期决策中确定）

### 4.5 向量维度固定 1024

BGE-M3 的 dense 是 **1024 维**，Qdrant collection 的 dense 字段、所有相关配置都必须是 1024。不要写成 768。

### 4.6 内存极限 1GB —— 建 collection 时必须开 on_disk

Qdrant 进程内存只有 1GB，**dense + sparse + 索引 + payload 必须全部 mmap 到磁盘**，否则一灌库就 OOM。Java
建表时不能只给默认参数，必须显式带：

| 字段                                 | 设置                   | 理由                               |
|------------------------------------|----------------------|----------------------------------|
| dense `VectorParams.on_disk`       | `true`               | 向量本体走 mmap，不占堆                   |
| dense `HnswConfigDiff.on_disk`     | `true`               | HNSW 图结构走 mmap                   |
| dense `HnswConfigDiff.m`           | `16`（默认）；内存极紧可降到 `8` | m 越小索引越省内存，召回率略降；数据量小时 8~16 都能接受 |
| sparse `SparseIndexConfig.on_disk` | `true`               | 稀疏倒排走 mmap                       |

这样内存只留运行时必需的查询缓冲，1G 才扛得住。重建 collection 时务必检查这四项都加上了。

### 4.7 Qdrant Java 客户端走 gRPC（端口 6334）

虽然第 1 节标的是 6333（HTTP REST，给 dashboard 用），但 `io.qdrant:client` 这个 Java 客户端只走 **gRPC，端口默认 6334**
。Qdrant Docker 默认会同时暴露这两个端口（`-p 6333:6333 -p 6334:6334`），如果只映射了 6333 需要补 6334。

---

## 5. 建议的实现与验证顺序

1. **先打通 dense-only 的最小链路**（可选的过渡步骤）：先只用 dense 做检索，验证「编码 → Qdrant → 检索 → 生成」整条流程通，再加
   sparse。这样调试范围小。
2. 任务 A（BgeM3Client）→ 单元测试：传一句话，断言 dense 长度 1024、sparse 非空
3. 任务 B1（建 collection）→ 用 Qdrant 的 REST/dashboard 确认 collection 结构正确
4. 任务 B2（灌几条测试数据）→ 在 Qdrant dashboard 看到 point
5. 任务 B3（混合检索）→ 传一个查询，确认能召回相关 chunk
6. 任务 C + D（编排 + 生成）→ 端到端跑通一个问答
7. 最后加流式输出、TopK 调参、prompt 模板优化

---

## 6. 环境信息备忘

- 操作系统：WSL2 (Ubuntu) on Windows
- 本地 GPU：RTX 4090 (24GB)；生产：A100
- vLLM 版本：0.22.0
- 三个服务都在 localhost，端口 8000 / 8002 / 6333
- Qdrant 由人工安装（Docker 或二进制均可），Claude Code 实现时假设它已在 6333 运行

---

## 7. 给 Claude Code 的第一步建议

读完本文档后，建议从这句话开始：

> 「请先实现任务 A（BgeM3Client），用 RestClient 对接 localhost:8002 的 /encode 和 /encode_batch，
> 返回类型 record EmbedResult(float[] dense, Map<Long,Float> sparse)，
> 并写一个 main 方法或测试，传入 "对账渠道有哪些"，打印 dense.length 和 sparse.size() 验证。」

逐个任务推进，每个任务跑通再做下一个，不要一次写完全部再调试。