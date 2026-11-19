package dev.zhiku.chat;

import java.util.UUID;

/**
 * 一条引用来源在删除（文档已不存在）或禁用（版本已撤回）后，既不能出现在历史里，
 * 也不能进入模型上下文。本仓没有单独的禁用标志，撤回即禁用。
 */
public final class SourceAvailability {
  private SourceAvailability() {}

  public static boolean usable(UUID documentId, boolean withdrawn, String chunkText, UUID sourceKbId, UUID conversationKbId) {
    return documentId != null && !withdrawn && chunkText != null && conversationKbId.equals(sourceKbId);
  }
}
