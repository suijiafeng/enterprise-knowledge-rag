-- 混合检索：chunk 带上下文（文档标题 › 标题路径），并以字符二元组建词法索引；向量按常见维度建 HNSW 部分索引。
-- zhiku_bigrams 的归一化规则与 dev.zhiku.retrieval.Bigrams 一致；不依赖数据库 locale 的字符类。
CREATE FUNCTION zhiku_bigrams(t text) RETURNS text[]
LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE AS $$
  SELECT coalesce(array_agg(DISTINCT substr(x.s, g.i, 2)), '{}')
  FROM (SELECT regexp_replace(lower(t), '[\s!-/:-@\[-`{-~，。！？；：、（）《》“”‘’【】—…·「」『』〈〉・－～　]', '', 'g') AS s) x,
       generate_series(1, char_length(x.s) - 1) AS g(i)
$$;

ALTER TABLE chunk ADD COLUMN context text;
UPDATE chunk c SET context = d.title || coalesce(' › ' || c.heading, '')
FROM content_version v JOIN document d ON d.id = v.document_id
WHERE v.id = c.version_id;

ALTER TABLE chunk ADD COLUMN lexical tsvector
  GENERATED ALWAYS AS (array_to_tsvector(zhiku_bigrams(coalesce(context, '') || ' ' || text))) STORED;
CREATE INDEX chunk_lexical_idx ON chunk USING gin (lexical);

-- 向量列不定维，HNSW 需要定维表达式；查询在 chunk 数达到阈值时才走这些索引，否则保持精确检索。
CREATE INDEX chunk_embedding_hnsw_256 ON chunk_embedding USING hnsw ((embedding::vector(256)) vector_cosine_ops) WHERE dimension = 256;
CREATE INDEX chunk_embedding_hnsw_768 ON chunk_embedding USING hnsw ((embedding::vector(768)) vector_cosine_ops) WHERE dimension = 768;
CREATE INDEX chunk_embedding_hnsw_1024 ON chunk_embedding USING hnsw ((embedding::vector(1024)) vector_cosine_ops) WHERE dimension = 1024;
CREATE INDEX chunk_embedding_hnsw_1536 ON chunk_embedding USING hnsw ((embedding::vector(1536)) vector_cosine_ops) WHERE dimension = 1536;
