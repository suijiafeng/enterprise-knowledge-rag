package dev.zhiku.ingestion;

import static dev.zhiku.auth.KnowledgeBaseAction.MANAGE_DOCUMENTS;

import dev.zhiku.audit.AuditLog;
import dev.zhiku.embedding.EmbeddingProvider;
import dev.zhiku.embedding.EmbeddingVectors;
import dev.zhiku.ingestion.TextChunker.Chunk;
import dev.zhiku.knowledgebase.KnowledgeBaseAccess;
import dev.zhiku.platform.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 在线改切块：保存后整版替换并用当前 embedding 模型重写向量，立刻参与检索。 */
@Service
public class ChunkEditService {
  public static final int MAX_TEXT = 8000;

  public record ChunkView(UUID id, int seq, int startOffset, int endOffset, String text) {}

  public record Draft(String text, int startOffset, int endOffset) {}

  private final KnowledgeBaseAccess access;
  private final IngestionRepository repository;
  private final IngestionSettings settings;
  private final ObjectProvider<EmbeddingProvider> embeddings;
  private final AuditLog audit;

  public ChunkEditService(KnowledgeBaseAccess access, IngestionRepository repository, IngestionSettings settings,
      ObjectProvider<EmbeddingProvider> embeddings, AuditLog audit) {
    this.access = access;
    this.repository = repository;
    this.settings = settings;
    this.embeddings = embeddings;
    this.audit = audit;
  }

  @Transactional(readOnly = true)
  public List<ChunkView> list(UUID kbId, UUID documentId, UUID versionId) {
    access.require(kbId, MANAGE_DOCUMENTS);
    gate(kbId, documentId, versionId);
    return views(versionId);
  }

  @Transactional
  public List<ChunkView> replace(UUID kbId, UUID documentId, UUID versionId, List<Draft> drafts) {
    var scope = access.require(kbId, MANAGE_DOCUMENTS);
    var gate = gate(kbId, documentId, versionId);
    if (gate.withdrawn() || gate.deleted()) throw ApiException.unprocessable("Withdrawn or deleted sources cannot be rechunked");
    if (drafts == null || drafts.isEmpty()) throw ApiException.unprocessable("At least one chunk is required");
    if (drafts.size() > settings.maxChunks()) throw ApiException.unprocessable("Too many chunks");
    var provider = embeddings.getIfAvailable();
    if (provider == null) throw ApiException.unavailable("Embedding model is not configured");
    var chunks = new ArrayList<Chunk>();
    for (int i = 0; i < drafts.size(); i++) {
      var draft = drafts.get(i);
      if (draft == null) throw ApiException.unprocessable("Chunk entries must not be empty");
      var text = draft.text() == null ? "" : draft.text().strip();
      if (text.isEmpty() || text.length() > MAX_TEXT) throw ApiException.unprocessable("Chunk text must be 1 to " + MAX_TEXT + " characters");
      if (draft.startOffset() < 0 || draft.endOffset() <= draft.startOffset() || draft.endOffset() > gate.sourceLength()) {
        throw ApiException.unprocessable("Chunk offsets must stay inside the source text");
      }
      chunks.add(new Chunk(i, draft.startOffset(), draft.endOffset(), null, text));
    }
    var ids = repository.replaceChunks(versionId, chunks);
    var vectors = provider.embed(chunks.stream().map(Chunk::text).toList());
    int dimension = EmbeddingVectors.validate(vectors, chunks.size());
    repository.insertEmbeddings(ids, vectors, provider.model(), dimension);
    audit.record(scope, "CHUNK_EDIT", "version:" + versionId, "OK", Map.of("chunks", chunks.size()));
    return views(versionId);
  }

  private IngestionRepository.VersionGate gate(UUID kbId, UUID documentId, UUID versionId) {
    return repository.versionGate(kbId, documentId, versionId).orElseThrow(ApiException::notFound);
  }

  private List<ChunkView> views(UUID versionId) {
    return repository.chunks(versionId).stream()
        .map(chunk -> new ChunkView(chunk.id(), chunk.seq(), chunk.startOffset(), chunk.endOffset(), chunk.text()))
        .toList();
  }
}
