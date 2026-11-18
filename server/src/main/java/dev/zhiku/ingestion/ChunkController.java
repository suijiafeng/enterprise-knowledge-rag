package dev.zhiku.ingestion;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/knowledge-bases/{kbId}/documents/{documentId}/versions/{versionId}/chunks")
class ChunkController {
  record DraftRequest(@NotBlank @Size(max = ChunkEditService.MAX_TEXT) String text, @Min(0) int startOffset, @Min(1) int endOffset) {}

  record ReplaceRequest(@NotNull @Size(min = 1, max = 20000) @Valid List<DraftRequest> chunks) {}

  private final ChunkEditService service;

  ChunkController(ChunkEditService service) {
    this.service = service;
  }

  @GetMapping
  List<ChunkEditService.ChunkView> list(@PathVariable UUID kbId, @PathVariable UUID documentId, @PathVariable UUID versionId) {
    return service.list(kbId, documentId, versionId);
  }

  @PutMapping
  List<ChunkEditService.ChunkView> replace(@PathVariable UUID kbId, @PathVariable UUID documentId, @PathVariable UUID versionId,
      @Valid @RequestBody ReplaceRequest request) {
    return service.replace(kbId, documentId, versionId, request.chunks().stream()
        .map(chunk -> new ChunkEditService.Draft(chunk.text(), chunk.startOffset(), chunk.endOffset())).toList());
  }
}
