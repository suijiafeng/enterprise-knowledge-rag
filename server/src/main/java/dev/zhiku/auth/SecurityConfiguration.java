package dev.zhiku.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import dev.zhiku.platform.RateLimiter;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.header.writers.CrossOriginOpenerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;

@Configuration
@EnableConfigurationProperties(JwtSettings.class)
public class SecurityConfiguration {
  private static final Logger log = LoggerFactory.getLogger(SecurityConfiguration.class);

  @Bean
  SecurityFilterChain security(HttpSecurity http, JwtDecoder decoder, ObjectMapper json, RateLimiter limiter,
      @Value("${zhiku.widget.allowed-origins:}") List<String> widgetOrigins) throws Exception {
    var errors = new ApiErrorResponses(json);
    var origins = widgetOrigins.stream().map(String::strip).filter(o -> !o.isEmpty()).toList();
    if (!origins.isEmpty()) {
      // 仅限显式配置的 widget 宿主、仅限 widget 端点，绝不携带凭据。
      var cors = new CorsConfiguration();
      cors.setAllowedOrigins(origins);
      cors.setAllowedMethods(List.of("POST", "PUT"));
      cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
      cors.setExposedHeaders(List.of("X-Request-Id"));
      cors.setAllowCredentials(false);
      var source = new UrlBasedCorsConfigurationSource();
      source.registerCorsConfiguration("/api/chat", cors);
      source.registerCorsConfiguration("/api/chat/**", cors);
      source.registerCorsConfiguration("/api/messages/*/feedback", cors);
      http.cors(c -> c.configurationSource(source));
    }
    return http
        // 业务接口只认 bearer token，不认 cookie，因此 CSRF 不适用。唯一的 cookie 是刷新令牌：HttpOnly + SameSite=Strict，
        // 路径限定 /api/auth，只能换回 access token（JSON 响应，跨站读不到，/api/auth 不开 CORS），登录只收 application/json。
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        // API 只返回 JSON / SSE：禁止被嵌入、禁止加载任何子资源、不外泄 Referer。HSTS 由 Spring 在 HTTPS 下自动加上。
        .headers(h -> h
            .contentSecurityPolicy(c -> c.policyDirectives("default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"))
            .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
            .crossOriginOpenerPolicy(c -> c.policy(CrossOriginOpenerPolicyHeaderWriter.CrossOriginOpenerPolicy.SAME_ORIGIN))
            .addHeaderWriter(new StaticHeadersWriter("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()")))
        .addFilterAfter(new RateLimitFilter(limiter, json), BearerTokenAuthenticationFilter.class)
        .authorizeHttpRequests(a -> a
            .requestMatchers(HttpMethod.GET, "/api/status", "/api/health", "/api/example-evaluation").permitAll()
            // 登录、刷新、退出本身不需要 access token：刷新靠 HttpOnly cookie，退出在 token 过期后也要能用。
            .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/refresh", "/api/auth/logout").permitAll()
            .requestMatchers(HttpMethod.POST, "/api/chat").authenticated()
            .requestMatchers(HttpMethod.GET, "/api/chat/requests/*").authenticated()
            .requestMatchers(HttpMethod.PUT, "/api/messages/*/feedback").authenticated()
            // Widget token 只能提问和评价自己的回答。
            .requestMatchers("/api/**").access((auth, ctx) -> new AuthorizationDecision(auth.get() instanceof JwtAuthenticationToken t
                && t.isAuthenticated() && !JwtAccessScopeResolver.WIDGET.equals(t.getToken().getClaimAsString(JwtAccessScopeResolver.TOKEN_USE))))
            .anyRequest().denyAll())
        .oauth2ResourceServer(o -> o
            .jwt(j -> j.decoder(decoder))
            .authenticationEntryPoint(errors.unauthenticated())
            .accessDeniedHandler(errors.forbidden()))
        .exceptionHandling(e -> e
            .authenticationEntryPoint(errors.unauthenticated())
            .accessDeniedHandler(errors.forbidden()))
        .build();
  }

  @Bean
  @ConditionalOnExpression("'${zhiku.auth.jwt-secret:}'.getBytes(T(java.nio.charset.StandardCharsets).UTF_8).length >= 32")
  JwtEncoder jwtEncoder(JwtSettings settings) {
    return new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(settings.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256")));
  }

  @Bean
  PasswordEncoder passwordEncoder() {
    return AccountSessions.passwordEncoder();
  }

  /** 缺少密钥时 StartupGuard 已拒绝启动；这里的兜底只为单独装配 bean 的场景。 */
  @Bean
  JwtDecoder jwtDecoder(JwtSettings settings, @Lazy AccountSessions sessions) {
    if (!settings.configured()) {
      log.warn("zhiku.auth.jwt-secret is missing or shorter than {} bytes; every bearer token will be rejected", JwtSettings.MIN_SECRET_BYTES);
      return token -> {
        throw new BadJwtException("JWT verification is not configured");
      };
    }
    var key = new SecretKeySpec(settings.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    var decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
        JwtValidators.createDefaultWithIssuer(settings.issuer()),
        new JwtClaimValidator<Instant>(JwtClaimNames.EXP, Objects::nonNull),
        new JwtClaimValidator<List<String>>(JwtClaimNames.AUD, aud -> aud != null && aud.contains(settings.audience())),
        sessionValidator(settings, sessions)));
    return decoder;
  }

  /**
   * 绑定会话（sid）的令牌：会话必须未撤销、账号未停用。不绑定会话的令牌只在 zhiku.auth.dev-tokens=true（dev 配置、测试）时接受。
   */
  static OAuth2TokenValidator<Jwt> sessionValidator(JwtSettings settings, AccountSessions sessions) {
    var revoked = OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Session is no longer valid", null));
    var unbound = OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Token is not bound to a session", null));
    return jwt -> {
      if (jwt.hasClaim(AccountSessions.SESSION)) return sessions.check(jwt).isPresent() ? OAuth2TokenValidatorResult.success() : revoked;
      return settings.devTokens() ? OAuth2TokenValidatorResult.success() : unbound;
    };
  }
}
