package dev.zhiku.evaluation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 未登录可读。只返回仓库里的示例快照，不查询数据库，因此不会带出企业评测。 */
@RestController
class ExampleEvaluationController {
  @GetMapping(value = "/api/example-evaluation", produces = MediaType.APPLICATION_JSON_VALUE)
  ResponseEntity<String> example() throws IOException {
    try (var in = new ClassPathResource("example-evaluation.json").getInputStream()) {
      return ResponseEntity.ok(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
  }
}
