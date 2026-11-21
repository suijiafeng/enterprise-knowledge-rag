package dev.zhiku.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.zhiku.platform.ApiError;
import dev.zhiku.platform.RateLimitSettings;
import dev.zhiku.platform.RateLimiter;
import dev.zhiku.platform.RequestIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Function;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 在 bearer 认证之后、授权之前限流：登录与刷新按来源 IP，问答、上传、评测按登录用户（未登录时按 IP）。
 * 只看 servlet 的 remoteAddr，不信任客户端自带的 X-Forwarded-For。
 */
final class RateLimitFilter extends OncePerRequestFilter {
  private record Route(String method, String pattern, String bucket, Function<RateLimitSettings, RateLimitSettings.Rule> rule, boolean perUser) {}

  private static final List<Route> ROUTES = List.of(
      new Route("POST", "/api/auth/login", "login", RateLimitSettings::login, false),
      new Route("POST", "/api/auth/refresh", "refresh", RateLimitSettings::refresh, false),
      new Route("POST", "/api/chat", "chat", RateLimitSettings::chat, true),
      new Route("PUT", "/api/knowledge-bases/*/documents/*/versions/*/content", "upload", RateLimitSettings::upload, true),
      new Route("POST", "/api/knowledge-bases/*/eval-runs", "evaluation", RateLimitSettings::evaluation, true),
      new Route("POST", "/api/knowledge-bases/*/eval-cases/import", "evaluation", RateLimitSettings::evaluation, true));

  private final RateLimiter limiter;
  private final ObjectMapper json;
  private final AntPathMatcher paths = new AntPathMatcher();

  RateLimitFilter(RateLimiter limiter, ObjectMapper json) {
    this.limiter = limiter;
    this.json = json;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (limiter.enabled()) {
      var path = request.getRequestURI().substring(request.getContextPath().length());
      for (var route : ROUTES) {
        if (!route.method().equals(request.getMethod()) || !paths.match(route.pattern(), path)) continue;
        var retry = limiter.acquire(route.bucket() + ":" + key(request, route.perUser()), route.rule().apply(limiter.settings()));
        if (retry.isPresent()) {
          response.setStatus(429);
          response.setHeader("Retry-After", Long.toString(Math.max(1, (retry.get().toMillis() + 999) / 1000)));
          response.setContentType(MediaType.APPLICATION_JSON_VALUE);
          response.setCharacterEncoding(StandardCharsets.UTF_8.name());
          json.writeValue(response.getWriter(), new ApiError("RATE_LIMITED", "Too many requests, retry later", RequestIdFilter.of(request)));
          return;
        }
        break;
      }
    }
    chain.doFilter(request, response);
  }

  private static String key(HttpServletRequest request, boolean perUser) {
    if (perUser && SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken t && t.getToken().getSubject() != null) {
      return "user:" + t.getToken().getSubject();
    }
    return "ip:" + request.getRemoteAddr();
  }
}
