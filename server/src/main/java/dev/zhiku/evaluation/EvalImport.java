package dev.zhiku.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/** JSON / CSV 评测集解析。真值是文档标题、版本标签和原文摘录，不接受 chunk id。 */
public final class EvalImport {
  public static final int MAX_CASES = 50;
  public static final int MAX_CHARS = 512_000;

  private EvalImport() {}

  public static final class Invalid extends IllegalArgumentException {
    public Invalid(String message) {
      super(message);
    }
  }

  public record EvidenceRef(String documentTitle, String versionLabel, String quote) {}

  public record Draft(String question, String label, String expectedAnswer, List<String> facts, String appliesTo, List<EvidenceRef> evidence) {}

  public record Batch(String name, boolean example, List<Draft> cases) {}

  public static Batch parse(String body, String contentType, ObjectMapper json) {
    if (body == null || body.isBlank()) throw new Invalid("Import is empty");
    if (body.length() > MAX_CHARS) throw new Invalid("Import is larger than " + MAX_CHARS + " characters");
    var type = contentType == null ? "" : contentType.toLowerCase();
    var stripped = body.stripLeading();
    if (type.contains("json") || stripped.startsWith("{")) return json(body, json);
    if (type.contains("csv") || stripped.startsWith("question,")) return csv(body);
    throw new Invalid("Import must be JSON or CSV");
  }

  private static Batch json(String body, ObjectMapper mapper) {
    JsonNode root;
    try {
      root = mapper.readTree(body);
    } catch (Exception e) {
      throw new Invalid("Import JSON is not an object");
    }
    if (root == null || !root.isObject()) throw new Invalid("Import JSON is not an object");
    var name = text(root, "name");
    if (name.isBlank() || name.length() > 80 || name.indexOf('\n') >= 0 || name.indexOf('\r') >= 0) {
      throw new Invalid("Dataset name must be 1 to 80 characters");
    }
    var casesNode = root.get("cases");
    if (casesNode == null || !casesNode.isArray() || casesNode.isEmpty()) throw new Invalid("Import has no cases");
    if (casesNode.size() > MAX_CASES) throw new Invalid("At most " + MAX_CASES + " cases");
    var cases = new ArrayList<Draft>();
    for (int i = 0; i < casesNode.size(); i++) {
      var node = casesNode.get(i);
      if (node == null || !node.isObject()) throw new Invalid("Case " + (i + 1) + " is not an object");
      var facts = new ArrayList<String>();
      var factsNode = node.get("requiredFacts");
      if (factsNode != null && !factsNode.isNull()) {
        if (!factsNode.isArray()) throw new Invalid("Case " + (i + 1) + " requiredFacts must be an array");
        factsNode.forEach(fact -> facts.add(fact.asText("")));
      }
      var evidence = new ArrayList<EvidenceRef>();
      var evidenceNode = node.get("evidence");
      if (evidenceNode != null && !evidenceNode.isNull()) {
        if (!evidenceNode.isArray()) throw new Invalid("Case " + (i + 1) + " evidence must be an array");
        for (var item : evidenceNode) {
          if (item != null && item.hasNonNull("chunkId")) throw new Invalid("Ground truth is a document version and quote, not a chunk id");
          evidence.add(new EvidenceRef(text(item, "documentTitle"), text(item, "versionLabel"), text(item, "quote")));
        }
      }
      cases.add(check(new Draft(text(node, "question"), text(node, "label"), blank(text(node, "expectedAnswer")), facts,
          blank(text(node, "appliesTo")), evidence), i + 1));
    }
    return new Batch(name.strip(), root.path("example").asBoolean(false), List.copyOf(cases));
  }

  private static Batch csv(String body) {
    var rows = parseCsv(body.replace("\uFEFF", ""));
    if (rows.isEmpty()) throw new Invalid("CSV is empty");
    var header = rows.getFirst();
    var expected = List.of("question", "label", "expectedAnswer", "requiredFacts", "documentTitle", "versionLabel", "quote");
    if (header.size() < expected.size() || !header.subList(0, expected.size()).equals(expected)) {
      throw new Invalid("CSV header must start with question,label,expectedAnswer,requiredFacts,documentTitle,versionLabel,quote");
    }
    boolean applies = header.size() > 7 && "appliesTo".equals(header.get(7));
    var cases = new ArrayList<Draft>();
    for (int i = 1; i < rows.size(); i++) {
      var row = rows.get(i);
      if (row.stream().allMatch(String::isBlank)) continue;
      if (row.size() < expected.size()) throw new Invalid("CSV row " + i + " is missing columns");
      var facts = new ArrayList<String>();
      if (!row.get(3).isBlank()) {
        for (var part : row.get(3).split("\\|", -1)) {
          if (!part.isBlank()) facts.add(part.strip());
        }
      }
      var audience = applies && row.size() > 7 ? blank(row.get(7)) : null;
      cases.add(check(new Draft(row.get(0).strip(), row.get(1).strip(), blank(row.get(2)), facts, audience,
          List.of(new EvidenceRef(row.get(4).strip(), row.get(5).strip(), row.get(6).strip()))), i));
    }
    if (cases.isEmpty()) throw new Invalid("CSV has no cases");
    if (cases.size() > MAX_CASES) throw new Invalid("At most " + MAX_CASES + " cases");
    return new Batch("csv", false, List.copyOf(cases));
  }

  static List<List<String>> parseCsv(String text) {
    var rows = new ArrayList<List<String>>();
    var row = new ArrayList<String>();
    var cell = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (quoted) {
        if (c == '"') {
          if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
            cell.append('"');
            i++;
          } else quoted = false;
        } else cell.append(c);
      } else if (c == '"') quoted = true;
      else if (c == ',') {
        row.add(cell.toString());
        cell.setLength(0);
      } else if (c == '\n' || c == '\r') {
        if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
        row.add(cell.toString());
        cell.setLength(0);
        rows.add(row);
        row = new ArrayList<>();
      } else cell.append(c);
    }
    if (quoted) throw new Invalid("CSV has an unclosed quote");
    if (cell.length() > 0 || !row.isEmpty()) {
      row.add(cell.toString());
      rows.add(row);
    }
    return rows;
  }

  private static Draft check(Draft draft, int index) {
    if (draft.question() == null || draft.question().isBlank() || draft.question().length() > 2000) {
      throw new Invalid("Case " + index + " question must be 1 to 2000 characters");
    }
    if (!EvaluationService.LABELS.contains(draft.label())) throw new Invalid("Case " + index + " has an unknown label");
    if (draft.expectedAnswer() != null && draft.expectedAnswer().length() > 2000) {
      throw new Invalid("Case " + index + " expected answer is too long");
    }
    if (draft.appliesTo() != null && draft.appliesTo().length() > 200) throw new Invalid("Case " + index + " appliesTo is too long");
    if (draft.facts().size() > 10) throw new Invalid("Case " + index + " has more than 10 required facts");
    if (draft.facts().stream().anyMatch(fact -> fact == null || fact.isBlank() || fact.strip().length() > 200)) {
      throw new Invalid("Case " + index + " required facts must be 1 to 200 characters");
    }
    if (draft.evidence().size() > 10) throw new Invalid("Case " + index + " has more than 10 evidence quotes");
    for (var evidence : draft.evidence()) {
      if (evidence.documentTitle() == null || evidence.documentTitle().isBlank() || evidence.versionLabel() == null || evidence.versionLabel().isBlank()) {
        throw new Invalid("Case " + index + " evidence needs a document title and version label");
      }
      if (evidence.quote() == null || evidence.quote().strip().length() < 2 || evidence.quote().length() > 2000) {
        throw new Invalid("Case " + index + " evidence quote must be 2 to 2000 characters");
      }
    }
    return draft;
  }

  private static String text(JsonNode node, String field) {
    if (node == null || node.get(field) == null || node.get(field).isNull()) return "";
    return node.get(field).asText("");
  }

  private static String blank(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }
}
