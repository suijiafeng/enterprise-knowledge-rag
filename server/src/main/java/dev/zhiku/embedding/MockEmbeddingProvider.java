package dev.zhiku.embedding;

import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 本地演示与 CI 用的确定性 embedding（zhiku.embedding.provider=mock）：字符二元组哈希到 256 维并做 L2 归一化。
 * 不理解语义，只反映字面重合；不调用任何外部服务。数值与 /workspace 外的 mock-ollama 脚本一致，便于前后对比。
 */
@Component
@ConditionalOnProperty(name = "zhiku.embedding.provider", havingValue = "mock")
public class MockEmbeddingProvider implements EmbeddingProvider {
  public static final int DIMENSION = 256;

  @Override
  public String model() {
    return "mock:bigram-" + DIMENSION;
  }

  @Override
  public List<float[]> embed(List<String> texts) {
    return texts.stream().map(MockEmbeddingProvider::vector).toList();
  }

  static float[] vector(String text) {
    var v = new double[DIMENSION];
    var cps = text.codePoints().filter(cp -> !Character.isWhitespace(cp)).toArray();
    for (int i = 0; i + 1 < cps.length; i++) {
      long h = 0;
      h = (h * 31 + cps[i]) & 0xFFFFFFFFL;
      h = (h * 31 + cps[i + 1]) & 0xFFFFFFFFL;
      v[(int) (h % DIMENSION)] += 1;
    }
    double norm = 0;
    for (var x : v) norm += x * x;
    norm = Math.sqrt(norm);
    var out = new float[DIMENSION];
    // 空文本或单字也要返回可比较的非零向量，避免余弦距离为 NaN。
    if (norm == 0) {
      out[DIMENSION - 1] = 1f;
      return out;
    }
    for (int i = 0; i < DIMENSION; i++) out[i] = (float) (v[i] / norm);
    return out;
  }
}
