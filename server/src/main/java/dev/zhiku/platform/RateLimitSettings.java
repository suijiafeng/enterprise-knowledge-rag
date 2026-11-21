package dev.zhiku.platform;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 单实例内存限流（固定窗口）。登录按来源 IP 和用户名各计一份；问答、上传、评测按登录用户计。
 * 上限与窗口在 application.yml 的 zhiku.rate-limit 下配置；未配置的规则不限流。
 * 多实例部署时应在网关层统一限流，这里是最后一道防线。
 */
@ConfigurationProperties("zhiku.rate-limit")
public record RateLimitSettings(
    @DefaultValue("true") boolean enabled,
    Rule login,
    Rule loginAccount,
    Rule refresh,
    Rule chat,
    Rule upload,
    Rule evaluation) {
  public record Rule(int limit, Duration window) {}
}
