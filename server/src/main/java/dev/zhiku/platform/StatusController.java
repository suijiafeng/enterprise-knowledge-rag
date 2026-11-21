package dev.zhiku.platform;

import dev.zhiku.chat.ChatModel;
import dev.zhiku.document.BusinessSettings;
import dev.zhiku.embedding.EmbeddingProvider;
import dev.zhiku.ingestion.IngestionSettings;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 公开、非敏感的能力摘要：不含密钥、URL、业务数据计数或身份。 */
@RestController
public class StatusController {
  private final ObjectProvider<ChatModel> chat;
  private final ObjectProvider<EmbeddingProvider> embeddings;
  private final BusinessSettings business;
  private final IngestionSettings ingestion;

  private final boolean demo;
  private final String demoPassword;

  public StatusController(ObjectProvider<ChatModel> chat, ObjectProvider<EmbeddingProvider> embeddings,
      BusinessSettings business, IngestionSettings ingestion,
      @org.springframework.beans.factory.annotation.Value("${zhiku.demo.accounts:false}") boolean demo,
      @org.springframework.beans.factory.annotation.Value("${zhiku.demo.password:}") String demoPassword) {
    this.demo = demo;
    this.demoPassword = demoPassword;
    this.chat = chat;
    this.embeddings = embeddings;
    this.business = business;
    this.ingestion = ingestion;
  }

  @GetMapping("/api/status")
  public Map<String, Object> status() {
    var chatModel = chat.getIfAvailable();
    var embedding = embeddings.getIfAvailable();
    var body = new LinkedHashMap<String, Object>();
    body.put("name", "知库");
    body.put("stage", "development");
    body.put("prdVersion", "0.3");
    body.put("modelEnabled", chatModel != null);
    body.put("chatModel", chatModel == null ? null : chatModel.model());
    body.put("embeddingModel", embedding == null ? null : embedding.model());
    body.put("retrieval", "exact-vector");
    body.put("businessTimeZone", business.timeZone().getId());
    body.put("maxUploadBytes", ingestion.maxBytes());
    body.put("uploadFormats", java.util.List.of(".md", ".markdown", ".txt", ".pdf", ".docx"));
    // 只有 dev 配置写入了演示账号时才公开它们的登录信息，方便本地体验；生产永远为 null。
    body.put("demoLogin", demo ? Map.of("usernames", java.util.List.of("admin", "operator", "employee"), "password", demoPassword) : null);
    return body;
  }
}
