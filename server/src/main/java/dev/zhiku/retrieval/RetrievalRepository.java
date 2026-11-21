package dev.zhiku.retrieval;

import dev.zhiku.embedding.EmbeddingVectors;
import dev.zhiku.retrieval.Retrieval.Candidate;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
class RetrievalRepository {
  /** 迁移 V27 为这些维度建了 HNSW 部分索引。 */
  static final Set<Integer> ANN_DIMENSIONS = Set.of(256, 768, 1024, 1536);

  private static final String COLUMNS = """
      SELECT c.id, d.id AS document_id, d.title, v.id AS version_id, v.version_label, c.seq, c.heading, c.start_offset, c.end_offset, c.text,
             c.context, %s AS score
      FROM chunk_embedding e
      JOIN chunk c ON c.id = e.chunk_id
      JOIN content_version v ON v.id = c.version_id
      JOIN document d ON d.id = v.document_id
      WHERE c.version_id = ANY(?) AND e.model = ? AND e.dimension = ? AND d.deleted_at IS NULL AND v.withdrawn_at IS NULL
      """;

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;

  RetrievalRepository(JdbcTemplate jdbc, TransactionTemplate tx) {
    this.jdbc = jdbc;
    this.tx = tx;
  }

  /** 精确余弦检索，限定在已授权的有效版本和单一 embedding 空间内。 */
  List<Candidate> nearest(List<UUID> versionIds, String model, float[] query, int k) {
    if (versionIds.isEmpty()) return List.of();
    var literal = EmbeddingVectors.literal(query);
    var rank = new int[] {0};
    return jdbc.query(COLUMNS.formatted("1 - (e.embedding <=> CAST(? AS vector))") + """
        ORDER BY e.embedding <=> CAST(? AS vector), c.id
        LIMIT ?
        """, (rs, i) -> candidate(rs, ++rank[0]), literal, versionIds.toArray(UUID[]::new), model, query.length, literal, k);
  }

  /**
   * HNSW 近似检索（pgvector 迭代扫描，过滤后仍尽量凑满 k 条）。维度写进 SQL 字面量，部分索引才能命中；
   * 维度只来自受支持的整数集合，不来自用户输入。
   */
  List<Candidate> approximate(List<UUID> versionIds, String model, float[] query, int k) {
    if (versionIds.isEmpty()) return List.of();
    int dim = query.length;
    if (!ANN_DIMENSIONS.contains(dim)) return nearest(versionIds, model, query, k);
    var literal = EmbeddingVectors.literal(query);
    var cast = "CAST(? AS vector(" + dim + "))";
    var rank = new int[] {0};
    return tx.execute(status -> {
      jdbc.execute("SET LOCAL hnsw.iterative_scan = relaxed_order");
      jdbc.execute("SET LOCAL hnsw.ef_search = 100");
      var rows = jdbc.query(COLUMNS.formatted("1 - (e.embedding::vector(" + dim + ") <=> " + cast + ")").replace("e.dimension = ?", "e.dimension = " + dim) + """
          ORDER BY e.embedding::vector(%d) <=> %s
          LIMIT ?
          """.formatted(dim, cast), (rs, i) -> candidate(rs, 0), literal, versionIds.toArray(UUID[]::new), model, literal, k);
      // relaxed_order 下结果可能轻微乱序：按真实距离重新排序后再编号。
      var sorted = rows.stream().sorted(java.util.Comparator.comparingDouble(Candidate::score).reversed()
          .thenComparing(c -> c.chunkId().toString())).toList();
      return sorted.stream().map(c -> c.withScore(c.score(), ++rank[0])).toList();
    });
  }

  /** 词法召回：二元组 tsvector 命中数排序，同时算出每条的余弦相似度，供门槛和融合使用。 */
  List<Candidate> lexical(List<UUID> versionIds, String model, float[] query, Set<String> terms, int k) {
    if (versionIds.isEmpty() || terms.isEmpty()) return List.of();
    var literal = EmbeddingVectors.literal(query);
    var tsquery = Bigrams.tsquery(terms);
    var rank = new int[] {0};
    return jdbc.query(COLUMNS.formatted("1 - (e.embedding <=> CAST(? AS vector))") + """
        AND c.lexical @@ CAST(? AS tsquery)
        ORDER BY ts_rank(c.lexical, CAST(? AS tsquery)) DESC, c.id
        LIMIT ?
        """, (rs, i) -> candidate(rs, ++rank[0]), literal, versionIds.toArray(UUID[]::new), model, query.length, tsquery, tsquery, k);
  }

  int chunkCount(List<UUID> versionIds) {
    if (versionIds.isEmpty()) return 0;
    var n = jdbc.queryForObject("SELECT count(*) FROM chunk WHERE version_id = ANY(?)", Integer.class, (Object) versionIds.toArray(UUID[]::new));
    return n == null ? 0 : n;
  }

  boolean allChunksExist(List<UUID> chunkIds) {
    var found = jdbc.queryForObject("SELECT count(*) FROM chunk WHERE id = ANY(?)", Integer.class, (Object) chunkIds.toArray(UUID[]::new));
    return found != null && found == chunkIds.stream().distinct().count();
  }

  private static Candidate candidate(ResultSet rs, int rank) throws SQLException {
    double score = rs.getDouble("score");
    return new Candidate(rs.getObject("id", UUID.class), rs.getObject("document_id", UUID.class), rs.getString("title"),
        rs.getObject("version_id", UUID.class), rs.getString("version_label"), rs.getInt("seq"), rs.getString("heading"),
        rs.getInt("start_offset"), rs.getInt("end_offset"), rs.getString("text"), score, rank, score, rs.getString("context"), null, null, null);
  }
}
