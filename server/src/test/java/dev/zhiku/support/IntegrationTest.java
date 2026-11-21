package dev.zhiku.support;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 基于真实 pgvector 容器的共享 Spring 上下文。模型提供方和 CORS 在此固定，使开发者的
 * ../.env（由 application.yml 导入）永远无法改变测试行为；由测试替身代替。
 */
@SpringBootTest(properties = {"zhiku.chat.provider=none", "zhiku.embedding.provider=none", "zhiku.widget.allowed-origins=",
    "zhiku.chat.recheck-interval=PT0S", "zhiku.chat.max-answer-chars=200", "zhiku.chat.handoff-channel=",
    "zhiku.retrieval.hybrid=false", "zhiku.retrieval.rerank=false", "zhiku.retrieval.min-score=-1", "zhiku.chat.query-rewrite=heuristic",
    // 既有用例直接签发不绑定会话的测试令牌并高频调用接口；会话绑定与限流在 AuthSecurityTest 里按生产配置验证。
    "zhiku.auth.dev-tokens=true", "zhiku.rate-limit.enabled=false"})
// 检索增强（混合召回、相关性门槛、重排、模型改写）在 PipelineTest 等专门的测试里按默认值开启验证；
// 这里显式关闭，使以授权、效力、可靠性为目标的既有用例不受排序变化影响。
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestEmbeddingConfiguration.class})
public abstract class IntegrationTest {
  @DynamicPropertySource
  static void jwt(DynamicPropertyRegistry registry) {
    registry.add("zhiku.auth.jwt-secret", () -> TestJwt.SECRET);
    // 测试显式驱动 ingestion worker，而不是依赖调度器。
    registry.add("zhiku.ingestion.worker-enabled", () -> "false");
    registry.add("zhiku.document.cleanup-enabled", () -> "false");
    registry.add("zhiku.retention.enabled", () -> "false");
  }
}
