package dev.zhiku.chat;

import dev.zhiku.platform.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * 历史分页游标是 {@code (created_at, id)}，精度到毫秒。同一毫秒内用 id 决定先后，
 * 避免只按时间分页时漏掉或重复同一时刻的消息。游标是不透明字符串，调用方不解析。
 */
public final class ChatCursor {
  public record Position(Instant createdAt, UUID id) {}

  private ChatCursor() {}

  public static String encode(Instant createdAt, UUID id) {
    var raw = createdAt.toEpochMilli() + "|" + id;
    return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  public static Position decode(String cursor) {
    try {
      var raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      var bar = raw.indexOf('|');
      if (bar <= 0 || bar == raw.length() - 1) throw new IllegalArgumentException();
      var createdAt = Instant.ofEpochMilli(Long.parseLong(raw.substring(0, bar)));
      var id = UUID.fromString(raw.substring(bar + 1));
      return new Position(createdAt, id);
    } catch (RuntimeException e) {
      throw ApiException.unprocessable("History cursor is invalid");
    }
  }

  /** 负数表示 a 在按 (created_at 毫秒, id) 升序时位于 b 之前。 */
  public static int compare(Instant aTime, UUID aId, Instant bTime, UUID bId) {
    var time = Long.compare(aTime.toEpochMilli(), bTime.toEpochMilli());
    return time != 0 ? time : aId.compareTo(bId);
  }

  /**
   * {@code createdAt}/{@code ids} 已按升序排列。返回严格早于 {@code before} 的消息条数；
   * {@code before} 为空时返回全部。
   */
  public static int exclusiveEnd(List<Instant> createdAt, List<UUID> ids, Position before) {
    if (before == null) return createdAt.size();
    var end = 0;
    for (var i = 0; i < createdAt.size(); i++) {
      if (compare(createdAt.get(i), ids.get(i), before.createdAt(), before.id()) < 0) end++;
      else break;
    }
    return end;
  }
}
