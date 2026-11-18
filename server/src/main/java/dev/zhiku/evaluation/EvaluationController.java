package dev.zhiku.evaluation;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/knowledge-bases/{kbId}")
class EvaluationController {
  record CreateCaseRequest(@NotBlank @Size(max = 2000) String question, @NotNull String label,
      @Size(max = 10) @Valid List<EvaluationService.EvidenceInput> evidence, @Size(max = 100) String source, UUID originMessageId,
      @Size(max = 2000) String expectedAnswer, List<String> requiredFacts, @Size(max = 200) String appliesTo, Instant queryAt) {}

  record DetailsRequest(@Size(max = 2000) String expectedAnswer, List<String> requiredFacts, @Size(max = 200) String appliesTo, Instant queryAt) {}

  record RunRequest(Integer k, Integer contextChunks, Boolean generate, String judge, Boolean rerank, Integer rerankPool, String chunkStrategy) {}

  record EvidenceRequest(@Size(max = 10) @Valid List<EvaluationService.EvidenceInput> evidence) {}

  private final EvaluationService service;

  EvaluationController(EvaluationService service) {
    this.service = service;
  }

  @GetMapping("/eval-cases")
  List<EvaluationService.CaseView> cases(@PathVariable UUID kbId) {
    return service.cases(kbId);
  }

  @PostMapping("/eval-cases")
  @ResponseStatus(HttpStatus.CREATED)
  Map<String, UUID> create(@PathVariable UUID kbId, @Valid @RequestBody CreateCaseRequest request) {
    return Map.of("id", service.createCase(kbId, request.question().strip(), request.label(), request.evidence(), request.source(),
        request.originMessageId(), new EvaluationService.Details(request.expectedAnswer(), request.requiredFacts(), request.appliesTo(), request.queryAt())));
  }

  @PostMapping("/eval-cases/{caseId}/approve")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void approve(@PathVariable UUID kbId, @PathVariable UUID caseId) {
    service.approve(kbId, caseId);
  }

  @PutMapping("/eval-cases/{caseId}/evidence")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void evidence(@PathVariable UUID kbId, @PathVariable UUID caseId, @Valid @RequestBody EvidenceRequest request) {
    service.setEvidence(kbId, caseId, request.evidence());
  }

  @PutMapping("/eval-cases/{caseId}/details")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void details(@PathVariable UUID kbId, @PathVariable UUID caseId, @Valid @RequestBody DetailsRequest request) {
    service.setDetails(kbId, caseId, new EvaluationService.Details(request.expectedAnswer(), request.requiredFacts(), request.appliesTo(), request.queryAt()));
  }

  @PostMapping(value = "/eval-cases/import", consumes = {"application/json", "text/csv", "text/plain", "application/octet-stream"})
  @ResponseStatus(HttpStatus.CREATED)
  EvaluationService.ImportResult importCases(@PathVariable UUID kbId, HttpServletRequest request,
      @RequestParam(defaultValue = "false") boolean approve, @RequestParam(required = false) String name) throws IOException {
    var bytes = request.getInputStream().readNBytes(2_000_001);
    if (bytes.length > 2_000_000) throw dev.zhiku.platform.ApiException.unprocessable("Import is too large");
    return service.importCases(kbId, new String(bytes, StandardCharsets.UTF_8), request.getContentType(), approve, name);
  }

  @GetMapping("/eval-settings")
  EvaluationService.EvalSettings settings(@PathVariable UUID kbId) {
    return service.settings(kbId);
  }

  @PostMapping("/eval-runs")
  EvaluationService.RunView run(@PathVariable UUID kbId, @RequestBody(required = false) RunRequest request) {
    var options = request == null ? null
        : new EvaluationService.RunOptions(request.k(), request.contextChunks(), request.generate(), request.judge(), request.rerank(),
            request.rerankPool(), request.chunkStrategy());
    return service.run(kbId, options);
  }

  @GetMapping("/eval-runs")
  List<EvaluationService.RunView> runs(@PathVariable UUID kbId) {
    return service.runs(kbId);
  }

  @GetMapping("/eval-runs/{runId}")
  EvaluationService.RunView run(@PathVariable UUID kbId, @PathVariable UUID runId) {
    return service.get(kbId, runId);
  }

  @GetMapping("/eval-runs/latest")
  ResponseEntity<EvaluationService.RunView> latest(@PathVariable UUID kbId) {
    var run = service.latest(kbId);
    return run == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(run);
  }
}
