package dev.zhiku.platform;

import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 存活与就绪：数据库连不上时返回 503，供负载均衡和编排摘除实例。不含连接串或错误细节。 */
@RestController
public class HealthController {
  private static final Logger log = LoggerFactory.getLogger(HealthController.class);
  private final JdbcTemplate jdbc;

  public HealthController(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
    this.jdbc.setQueryTimeout(2);
  }

  @GetMapping("/api/health")
  public ResponseEntity<Map<String, String>> health() {
    var body = new LinkedHashMap<String, String>();
    boolean up;
    try {
      up = Integer.valueOf(1).equals(jdbc.queryForObject("SELECT 1", Integer.class));
    } catch (RuntimeException e) {
      log.warn("Health check: database unavailable ({})", e.getClass().getSimpleName());
      up = false;
    }
    body.put("status", up ? "UP" : "DOWN");
    body.put("database", up ? "UP" : "DOWN");
    return ResponseEntity.status(up ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).header("Cache-Control", "no-store").body(body);
  }
}
