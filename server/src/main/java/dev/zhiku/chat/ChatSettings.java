package dev.zhiku.chat;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("zhiku.chat")
public record ChatSettings(String provider, String baseUrl, String model, int contextChunks, int maxChunkChars,
    Duration timeout, Duration streamTimeout, Duration recheckInterval, Duration idleTimeout, int maxAnswerChars,
    Duration heartbeatInterval, String handoffChannel, int maxTurns, int maxContextChars, String queryRewrite) {

  @org.springframework.boot.context.properties.bind.ConstructorBinding
  public ChatSettings {
    if (recheckInterval == null) recheckInterval = Duration.ofSeconds(1);
    if (idleTimeout == null) idleTimeout = Duration.ofSeconds(30);
    if (maxAnswerChars <= 0) maxAnswerChars = 8000;
    if (heartbeatInterval == null) heartbeatInterval = Duration.ofSeconds(15);
    if (maxTurns <= 0) maxTurns = 100;
    if (maxContextChars <= 0) maxContextChars = 16000;
    // llm | heuristic | off：llm 先用模型改写追问，失败时退回规则。
    if (queryRewrite == null || queryRewrite.isBlank()) queryRewrite = "llm";
  }

  public ChatSettings(String provider, String baseUrl, String model, int contextChunks, int maxChunkChars,
      Duration timeout, Duration streamTimeout, Duration recheckInterval, Duration idleTimeout, int maxAnswerChars,
      Duration heartbeatInterval, String handoffChannel, int maxTurns, int maxContextChars) {
    this(provider, baseUrl, model, contextChunks, maxChunkChars, timeout, streamTimeout, recheckInterval, idleTimeout, maxAnswerChars,
        heartbeatInterval, handoffChannel, maxTurns, maxContextChars, null);
  }
}
