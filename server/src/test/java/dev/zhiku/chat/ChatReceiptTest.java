package dev.zhiku.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChatReceiptTest {
  @Test
  void classifiesAnInsertADuplicateAndAConflict() {
    assertThat(ChatService.receiptOutcome(true, "same", "same")).isEqualTo("accepted");
    assertThat(ChatService.receiptOutcome(false, "same", "same")).isEqualTo("duplicate");
    assertThat(ChatService.receiptOutcome(false, "same", "other")).isEqualTo("conflict");
  }

  @Test
  void digestIsSha256OfKbConversationAndQuestion() {
    var kb = UUID.fromString("00000000-0000-4000-8000-000000000001");
    var conversation = UUID.fromString("00000000-0000-4000-8000-000000000002");
    assertThat(ChatService.digest(kb, conversation, "年假有几天?")).hasSize(64);
    assertThat(ChatService.digest(kb, conversation, "年假有几天?"))
        .isEqualTo(ChatService.digest(kb, conversation, "年假有几天?"));
    assertThat(ChatService.digest(kb, conversation, "年假有几天?"))
        .isNotEqualTo(ChatService.digest(kb, conversation, "病假怎么请?"));
  }
}
