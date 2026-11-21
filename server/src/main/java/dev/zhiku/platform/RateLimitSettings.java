package dev.zhiku.platform;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 固定窗口限流规则。登录按来源 IP 和用户名各计一份；问答、上传、评测按登录用户计。
 * 上限与窗口在 application.yml 的 zhiku.rate-limit 下配置；未配置的规则不限流。
 * 计数默认放在进程内存（zhiku.rate-limit.store=memory）；多实例部署设为 postgres，各实例共用一份计数。
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
