package dev.zhiku.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChatCursorTest {
  static final Instant SAME = Instant.ofEpochMilli(1_763_424_000_000L);
  static final UUID LOWER = UUID.fromString("00000000-0000-4000-8000-000000000001");
  static final UUID HIGHER = UUID.fromString("00000000-0000-4000-8000-000000000002");

  @Test
  void roundTripsAndRejectsATamperedCursor() {
    var cursor = ChatCursor.encode(SAME, HIGHER);
    assertThat(cursor).isEqualTo("MTc2MzQyNDAwMDAwMHwwMDAwMDAwMC0wMDAwLTQwMDAtODAwMC0wMDAwMDAwMDAwMDI");
    var decoded = ChatCursor.decode(cursor);
    assertThat(decoded.createdAt()).isEqualTo(SAME);
    assertThat(decoded.id()).isEqualTo(HIGHER);
    assertThatThrownBy(() -> ChatCursor.decode("not-a-cursor")).isInstanceOf(dev.zhiku.platform.ApiException.class);
  }

  @Test
  void sameTimestampUsesIdAsTheNextPageBoundary() {
    var times = List.of(SAME, SAME.plusNanos(500_000));
    var ids = List.of(LOWER, HIGHER);
    var cursor = ChatCursor.decode(ChatCursor.encode(SAME, HIGHER));
    assertThat(ChatCursor.compare(SAME, LOWER, SAME, HIGHER)).isNegative();
    assertThat(ChatCursor.exclusiveEnd(times, ids, cursor)).isEqualTo(1);
    assertThat(ChatCursor.exclusiveEnd(times, ids, null)).isEqualTo(2);
    assertThat(ChatCursor.exclusiveEnd(times, ids, new ChatCursor.Position(SAME, LOWER))).isZero();
  }
}
