## neo4j向量索引

- spring.ai.vectorstore.neo4j.initialize-schema=true spring会自动建

## neo4j全文索引

使用cjk获取更好的分词效果

~~~
CREATE FULLTEXT INDEX `aidemo-neo4j-keyword-index`
FOR (n:Document) ON EACH [n.text]
OPTIONS {
  indexConfig: {
    `fulltext.analyzer`: 'cjk'
  }
}
~~~