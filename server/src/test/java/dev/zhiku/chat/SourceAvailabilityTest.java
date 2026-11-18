package dev.zhiku.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SourceAvailabilityTest {
  static final UUID KB = UUID.fromString("00000000-0000-4000-8000-0000000000aa");
  static final UUID OTHER = UUID.fromString("00000000-0000-4000-8000-0000000000bb");
  static final UUID DOC = UUID.fromString("00000000-0000-4000-8000-0000000000d1");

  @Test
  void deletedWithdrawnOrForeignSourcesAreUnusable() {
    assertThat(SourceAvailability.usable(DOC, false, "年假按司龄计算。", KB, KB)).isTrue();
    assertThat(SourceAvailability.usable(null, false, "年假按司龄计算。", KB, KB)).isFalse();
    assertThat(SourceAvailability.usable(DOC, true, "年假按司龄计算。", KB, KB)).isFalse();
    assertThat(SourceAvailability.usable(DOC, false, null, KB, KB)).isFalse();
    assertThat(SourceAvailability.usable(DOC, false, "年假按司龄计算。", OTHER, KB)).isFalse();
  }

  @Test
  void modelContextDropsHiddenAndIncompleteTurns() {
    var hidden = message("hidden", true, "complete");
    var failed = message("failed", false, "failed");
    var kept = message("kept", false, "complete");
    assertThat(ChatService.modelContext(List.of(hidden, failed, kept))).containsExactly(kept);
  }

  private static ChatService.MessageView message(String question, boolean hidden, String status) {
    return new ChatService.MessageView(UUID.randomUUID(), question, hidden ? null : "回答", hidden, hidden ? "SOURCE_UNAVAILABLE" : null,
        false, List.of(), Instant.EPOCH, null, status, null);
  }
}
