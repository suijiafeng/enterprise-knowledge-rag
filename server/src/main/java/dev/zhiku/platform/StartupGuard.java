package dev.zhiku.platform;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 启动时检查危险配置，不满足就拒绝启动，而不是带着默认值跑起来。
 * 任何环境：JWT 密钥必须配置且不少于 32 字节，且不是示例占位值。
 * prod 配置：不接受开发令牌、不写演示账号、刷新 cookie 必须 Secure、数据库密码必须配置、CORS 不能用通配符。
 * 作为 BeanFactoryPostProcessor 在任何业务 bean（包括连接数据库、执行迁移）之前运行。
 */
@Component
public class StartupGuard implements BeanFactoryPostProcessor, EnvironmentAware {
  private Environment environment;

  @Override
  public void setEnvironment(Environment environment) {
    this.environment = environment;
  }

  @Override
  public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
    check(environment);
  }

  static final int MIN_SECRET_BYTES = 32;
  private static final List<String> PLACEHOLDERS = List.of("change-me", "changeme", "secret", "example", "your-", "placeholder");

  public static void check(Environment env) {
    var problems = problems(env);
    if (!problems.isEmpty()) {
      throw new IllegalStateException("Refusing to start: " + String.join("; ", problems));
    }
  }

  static List<String> problems(Environment env) {
    var problems = new ArrayList<String>();
    var secret = env.getProperty("zhiku.auth.jwt-secret", "");
    if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
      problems.add("zhiku.auth.jwt-secret (ZHIKU_JWT_SECRET) must be at least " + MIN_SECRET_BYTES + " bytes, e.g. `openssl rand -base64 48`");
    } else if (PLACEHOLDERS.stream().anyMatch(p -> secret.toLowerCase(Locale.ROOT).contains(p))) {
      problems.add("zhiku.auth.jwt-secret looks like a placeholder; generate a random value");
    }
    boolean prod = Arrays.asList(env.getActiveProfiles()).contains("prod");
    boolean dev = Arrays.asList(env.getActiveProfiles()).contains("dev");
    if (prod && dev) problems.add("profiles prod and dev cannot be active together");
    if (prod) {
      if (env.getProperty("zhiku.auth.dev-tokens", Boolean.class, false)) problems.add("zhiku.auth.dev-tokens must be false in prod");
      if (env.getProperty("zhiku.demo.accounts", Boolean.class, false)) problems.add("zhiku.demo.accounts must be false in prod");
      if (!env.getProperty("zhiku.auth.cookie-secure", Boolean.class, true)) problems.add("zhiku.auth.cookie-secure must be true in prod");
      if (env.getProperty("spring.datasource.password", "").isBlank()) problems.add("spring.datasource.password (POSTGRES_PASSWORD) must be set in prod");
      if (env.getProperty("zhiku.widget.allowed-origins", "").contains("*")) problems.add("zhiku.widget.allowed-origins cannot contain *");
      if (!env.getProperty("zhiku.rate-limit.enabled", Boolean.class, true)) problems.add("zhiku.rate-limit.enabled must be true in prod");
    }
    return problems;
  }
}
