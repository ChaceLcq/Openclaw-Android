package ai.openclaw.app.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatControllerSessionPolicyTest {
  @Test
  fun applyMainSessionKeyMovesCurrentSessionWhenStillOnDefault() {
    val state =
      applyMainSessionKey(
        currentSessionKey = "main",
        appliedMainSessionKey = "main",
        nextMainSessionKey = "agent:ops:node-device",
      )

    assertEquals("agent:ops:node-device", state.currentSessionKey)
    assertEquals("agent:ops:node-device", state.appliedMainSessionKey)
  }

  @Test
  fun applyMainSessionKeyKeepsUserSelectedSession() {
    val state =
      applyMainSessionKey(
        currentSessionKey = "custom",
        appliedMainSessionKey = "agent:ops:node-old",
        nextMainSessionKey = "agent:ops:node-new",
      )

    assertEquals("custom", state.currentSessionKey)
    assertEquals("agent:ops:node-new", state.appliedMainSessionKey)
  }

  @Test
  fun freshChatSessionKeyUsesMainSessionBaseAndTimestamp() {
    val key =
      freshChatSessionKey(
        baseSessionKey = " agent:ops:node-device ",
        nowMs = 1_789_123_456_789L,
      )

    assertEquals("agent:ops:node-device:clean:1789123456789", key)
  }

  @Test
  fun freshChatSessionKeyFallsBackToMainWhenBaseBlank() {
    val key = freshChatSessionKey(baseSessionKey = " ", nowMs = 42L)

    assertEquals("main:clean:42", key)
  }

  @Test
  fun chatErrorAfterTerminalStateClearsTransientStreamErrorOnFinal() {
    assertEquals(
      null,
      chatErrorAfterTerminalState(
        state = "final",
        payloadErrorMessage = null,
        existingError = EVENT_STREAM_INTERRUPTED_ERROR,
      ),
    )
  }

  @Test
  fun chatErrorAfterTerminalStateKeepsRealChatError() {
    assertEquals(
      "DLA failed",
      chatErrorAfterTerminalState(
        state = "error",
        payloadErrorMessage = "DLA failed",
        existingError = EVENT_STREAM_INTERRUPTED_ERROR,
      ),
    )
  }

  @Test
  fun streamInterruptedErrorIsSuppressedWhenAssistantTextIsVisible() {
    assertEquals(null, eventStreamInterruptedError(assistantTextVisible = true))
    assertEquals(EVENT_STREAM_INTERRUPTED_ERROR, eventStreamInterruptedError(assistantTextVisible = false))
  }

  @Test
  fun historyRefreshClearsTransientStreamErrorWhenAssistantReplyExists() {
    val messages =
      listOf(
        ChatMessage(
          id = "assistant-1",
          role = "assistant",
          content = listOf(ChatMessageContent(text = "reply")),
          timestampMs = 1L,
        ),
      )

    assertEquals(
      null,
      chatErrorAfterHistoryRefresh(
        messages = messages,
        existingError = EVENT_STREAM_INTERRUPTED_ERROR,
      ),
    )
  }

  @Test
  fun historyRefreshKeepsRealErrorWhenAssistantReplyExists() {
    val messages =
      listOf(
        ChatMessage(
          id = "assistant-1",
          role = "assistant",
          content = listOf(ChatMessageContent(text = "reply")),
          timestampMs = 1L,
        ),
      )

    assertEquals(
      "DLA failed",
      chatErrorAfterHistoryRefresh(
        messages = messages,
        existingError = "DLA failed",
      ),
    )
  }

  @Test
  fun displayableHistoryMessagesHideSystemMessages() {
    val messages =
      listOf(
        ChatMessage(
          id = "system-compaction",
          role = "system",
          content = listOf(ChatMessageContent(text = "Compaction")),
          timestampMs = 1L,
        ),
        ChatMessage(
          id = "system-context",
          role = "system",
          content = listOf(ChatMessageContent(text = "product-docs/extracted/m328l-zh-v1-4.txt")),
          timestampMs = 2L,
        ),
        ChatMessage(
          id = "assistant-1",
          role = "assistant",
          content = listOf(ChatMessageContent(text = "reply")),
          timestampMs = 3L,
        ),
      )

    assertEquals(
      listOf("assistant-1"),
      displayableHistoryMessages(messages).map { it.id },
    )
  }
}
