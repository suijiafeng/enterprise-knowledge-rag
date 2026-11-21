package localdemo;

import dev.zhiku.ZhikuApplication;
import dev.zhiku.auth.Role;
import dev.zhiku.ingestion.IngestionWorker;
import dev.zhiku.support.TestChatModel;
import dev.zhiku.support.TestEmbeddingConfiguration;
import dev.zhiku.support.TestEmbeddingProvider;
import dev.zhiku.support.TestJwt;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 本地演示进程，不参与生产启动，也不会被组件扫描。
 * 使用测试用的确定性向量和脚本化对话模型，跑两次示例评测后保持服务，供管理端查看。
 * 分数来自这次真实运行；对话模型是 test-scripted，评审是模拟词面规则。
 */
public final class EvalDashboardDemo {
  private EvalDashboardDemo() {}

  public static void main(String[] args) throws Exception {
    var secret = secret();
    System.setProperty("ZHIKU_JWT_SECRET", secret);
    boolean ollama = args.length > 0 && "ollama".equals(args[0]);
    if (ollama) {
      System.setProperty("ZHIKU_CHAT_PROVIDER", "ollama");
      System.setProperty("ZHIKU_CHAT_MODEL", "qwen2.5:0.5b");
    }
    var enterprise = UUID.fromString("00000000-0000-4000-8000-0000000000e1");
    var user = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    var sources = ollama
        ? new Class<?>[] {ZhikuApplication.class, DemoEmbeddingOnly.class}
        : new Class<?>[] {ZhikuApplication.class, TestEmbeddingConfiguration.class};
    var ctx = new SpringApplicationBuilder(sources)
        .properties(
            "server.port=8080",
            "server.address=127.0.0.1",
            "zhiku.chat.provider=" + (ollama ? "ollama" : "none"),
            "zhiku.chat.model=" + (ollama ? "qwen2.5:0.5b" : "qwen3.5:latest"),
            "zhiku.embedding.provider=none",
            "zhiku.ingestion.worker-enabled=false",
            "zhiku.document.cleanup-enabled=false",
            "zhiku.retention.enabled=false",
            "zhiku.widget.allowed-origins=",
            "zhiku.auth.jwt-secret=" + secret,
            "zhiku.auth.dev-tokens=true",
            "zhiku.auth.issuer=" + TestJwt.ISSUER,
            "zhiku.auth.audience=" + TestJwt.AUDIENCE,
            "spring.datasource.url=" + env("ZHIKU_DB_URL", "jdbc:postgresql://127.0.0.1:5432/zhiku"),
            "spring.datasource.username=" + env("POSTGRES_USER", "zhiku_local"),
            "spring.datasource.password=" + env("POSTGRES_PASSWORD", "zhiku_local_demo"))
        .run(args);
    if (!ollama) {
      var model = ctx.getBean(TestChatModel.class);
      model.script = messages -> "司龄满十年的员工每年可以休假十五天[1]。申请须提前三个工作日。";
    }
    var token = TestJwt.token(enterprise, user, claims -> claims.expirationTime(Date.from(Instant.now().plusSeconds(7200))), secret, Role.IT_ADMIN, Role.KB_OPERATOR);
    Files.writeString(Path.of("/tmp/zhiku-demo-token.txt"), token);
    if (ctx.getBean(JdbcTemplate.class).queryForObject("SELECT count(*) FROM knowledge_base WHERE name = ?", Integer.class, "员工制度示例库") == 0) {
      seed(token, ctx.getBean(IngestionWorker.class));
    }
    System.out.println("EVAL_DEMO_READY");
  }

  private static void seed(String token, IngestionWorker worker) throws Exception {
    var http = HttpClient.newHttpClient();
    var kb = jsonId(send(http, token, "POST", "/api/knowledge-bases", "application/json", "{\"name\":\"员工制度示例库\"}"));
    send(http, token, "PUT", "/api/knowledge-bases/" + kb + "/grants/" + "00000000-0000-4000-8000-0000000000a1", null, null);
    upload(http, token, worker, kb, "年假办法（示例）", "leave.md");
    upload(http, token, worker, kb, "年假窗口说明（示例）", "trap.md");
    upload(http, token, worker, kb, "差旅办法（示例）", "travel.md");
    var dataset = Files.readString(Path.of("scripts/example-eval/dataset.json"));
    send(http, token, "POST", "/api/knowledge-bases/" + kb + "/eval-cases/import?approve=true", "application/json", dataset);
    send(http, token, "POST", "/api/knowledge-bases/" + kb + "/eval-runs", "application/json",
        "{\"k\":1,\"contextChunks\":1,\"generate\":true,\"judge\":\"simulated\"}");
    send(http, token, "POST", "/api/knowledge-bases/" + kb + "/eval-runs", "application/json",
        "{\"k\":5,\"contextChunks\":5,\"generate\":true,\"judge\":\"simulated\"}");
  }

  private static void upload(HttpClient http, String token, IngestionWorker worker, String kb, String title, String file) throws Exception {
    var doc = jsonId(send(http, token, "POST", "/api/knowledge-bases/" + kb + "/documents", "application/json", "{\"title\":\"" + title + "\"}"));
    var version = jsonId(send(http, token, "POST", "/api/knowledge-bases/" + kb + "/documents/" + doc + "/versions", "application/json",
        "{\"versionLabel\":\"v1\",\"effectiveFrom\":\"2026-01-01\"}"));
    send(http, token, "POST", "/api/knowledge-bases/" + kb + "/documents/" + doc + "/versions/" + version + "/approve", null, null);
    send(http, token, "POST", "/api/knowledge-bases/" + kb + "/documents/" + doc + "/versions/" + version + "/publish", null, null);
    var boundary = "----zhikuDemo";
    var text = Files.readString(Path.of("scripts/example-eval/" + file));
    var body = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"doc.md\"\r\nContent-Type: text/markdown\r\n\r\n"
        + text + "\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:8080/api/knowledge-bases/" + kb + "/documents/" + doc + "/versions/" + version + "/content"))
        .header("Authorization", "Bearer " + token).header("Content-Type", "multipart/form-data; boundary=" + boundary)
        .PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build();
    var response = http.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() / 100 != 2) throw new IllegalStateException("upload " + response.statusCode() + " " + response.body());
    if (!worker.processNext()) throw new IllegalStateException("ingestion did not run for " + title);
  }

  private static String send(HttpClient http, String token, String method, String path, String type, String body) throws Exception {
    var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:8080" + path)).header("Authorization", "Bearer " + token);
    if (type != null) builder.header("Content-Type", type);
    var publisher = body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body);
    var response = http.send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() / 100 != 2) throw new IllegalStateException(method + " " + path + " -> " + response.statusCode() + " " + response.body());
    return response.body();
  }

  private static String jsonId(String body) {
    int at = body.indexOf("\"id\"");
    int quote = body.indexOf('"', body.indexOf(':', at) + 1);
    return body.substring(quote + 1, body.indexOf('"', quote + 1));
  }

  private static String secret() {
    var bytes = new byte[48];
    new SecureRandom().nextBytes(bytes);
    return Base64.getEncoder().encodeToString(bytes);
  }

  private static String env(String name, String fallback) {
    var value = System.getenv(name);
    return value == null || value.isBlank() ? fallback : value;
  }
}

/** 只提供测试向量，对话走 Ollama。不在 dev.zhiku 包里，不会被生产扫描到。 */
@Configuration
class DemoEmbeddingOnly {
  @Bean
  TestEmbeddingProvider testEmbeddingProvider() {
    return new TestEmbeddingProvider();
  }
}
