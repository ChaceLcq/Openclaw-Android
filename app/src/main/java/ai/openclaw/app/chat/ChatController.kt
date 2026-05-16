package ai.openclaw.app.chat

import ai.openclaw.app.gateway.GatewaySession
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal const val EVENT_STREAM_INTERRUPTED_ERROR = "Event stream interrupted; try refreshing."

internal fun chatErrorAfterTerminalState(
  state: String?,
  payloadErrorMessage: String?,
  existingError: String?,
): String? =
  when (state) {
    "error" -> payloadErrorMessage ?: "Chat failed"
    "final", "aborted" -> if (existingError == EVENT_STREAM_INTERRUPTED_ERROR) null else existingError
    else -> existingError
  }

internal fun eventStreamInterruptedError(assistantTextVisible: Boolean): String? =
  if (assistantTextVisible) null else EVENT_STREAM_INTERRUPTED_ERROR

internal fun chatErrorAfterHistoryRefresh(
  messages: List<ChatMessage>,
  existingError: String?,
): String? =
  if (existingError == EVENT_STREAM_INTERRUPTED_ERROR && messagesContainAssistantText(messages)) {
    null
  } else {
    existingError
  }

internal fun messagesContainAssistantText(messages: List<ChatMessage>): Boolean =
  messages.any { message ->
    message.role == "assistant" && message.content.any { !it.text.isNullOrBlank() }
  }

class ChatController(
  private val scope: CoroutineScope,
  private val session: GatewaySession,
  private val json: Json,
  private val supportsChatSubscribe: Boolean,
) {
  private var appliedMainSessionKey = "main"
  private val _sessionKey = MutableStateFlow("main")
  val sessionKey: StateFlow<String> = _sessionKey.asStateFlow()

  private val _sessionId = MutableStateFlow<String?>(null)
  val sessionId: StateFlow<String?> = _sessionId.asStateFlow()

  private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
  val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

  private val _errorText = MutableStateFlow<String?>(null)
  val errorText: StateFlow<String?> = _errorText.asStateFlow()

  private val _healthOk = MutableStateFlow(false)
  val healthOk: StateFlow<Boolean> = _healthOk.asStateFlow()

  private val _thinkingLevel = MutableStateFlow("off")
  val thinkingLevel: StateFlow<String> = _thinkingLevel.asStateFlow()

  private val _pendingRunCount = MutableStateFlow(0)
  val pendingRunCount: StateFlow<Int> = _pendingRunCount.asStateFlow()

  private val _streamingAssistantText = MutableStateFlow<String?>(null)
  val streamingAssistantText: StateFlow<String?> = _streamingAssistantText.asStateFlow()

  private val pendingToolCallsById = ConcurrentHashMap<String, ChatPendingToolCall>()
  private val _pendingToolCalls = MutableStateFlow<List<ChatPendingToolCall>>(emptyList())
  val pendingToolCalls: StateFlow<List<ChatPendingToolCall>> = _pendingToolCalls.asStateFlow()

  private val _sessions = MutableStateFlow<List<ChatSessionEntry>>(emptyList())
  val sessions: StateFlow<List<ChatSessionEntry>> = _sessions.asStateFlow()

  private val pendingRuns = mutableSetOf<String>()
  private val pendingRunTimeoutJobs = ConcurrentHashMap<String, Job>()
  private val pendingRunStartedAtMs = ConcurrentHashMap<String, Long>()
  private val firstDeltaLogged = ConcurrentHashMap.newKeySet<String>()
  private val pendingRunTimeoutMs = 120_000L

  private var lastHealthPollAtMs: Long? = null

  fun onDisconnected(message: String) {
    _healthOk.value = false
    // Not an error; keep connection status in the UI pill.
    _errorText.value = null
    clearPendingRuns()
    pendingToolCallsById.clear()
    publishPendingToolCalls()
    _streamingAssistantText.value = null
    _sessionId.value = null
  }

  fun load(sessionKey: String) {
    val key = normalizeRequestedSessionKey(sessionKey)
    _sessionKey.value = key
    scope.launch { bootstrap(forceHealth = true, refreshSessions = true) }
  }

  fun applyMainSessionKey(mainSessionKey: String) {
    val trimmed = mainSessionKey.trim()
    if (trimmed.isEmpty()) return
    val nextState =
      applyMainSessionKey(
        currentSessionKey = normalizeRequestedSessionKey(_sessionKey.value),
        appliedMainSessionKey = appliedMainSessionKey,
        nextMainSessionKey = trimmed,
      )
    appliedMainSessionKey = nextState.appliedMainSessionKey
    if (_sessionKey.value == nextState.currentSessionKey) return
    _sessionKey.value = nextState.currentSessionKey
    scope.launch { bootstrap(forceHealth = true, refreshSessions = true) }
  }

  fun refresh() {
    scope.launch { bootstrap(forceHealth = true, refreshSessions = true) }
  }

  fun refreshSessions(limit: Int? = null) {
    scope.launch { fetchSessions(limit = limit) }
  }

  fun setThinkingLevel(thinkingLevel: String) {
    val normalized = normalizeThinking(thinkingLevel)
    if (normalized == _thinkingLevel.value) return
    _thinkingLevel.value = normalized
  }

  fun switchSession(sessionKey: String) {
    val key = normalizeRequestedSessionKey(sessionKey)
    if (key.isEmpty()) return
    if (key == _sessionKey.value) return
    _sessionKey.value = key
    // Keep the thread switch path lean: history + health are needed immediately,
    // but the session list is usually unchanged and can refresh on explicit pull-to-refresh.
    scope.launch { bootstrap(forceHealth = true, refreshSessions = false) }
  }

  fun startFreshSession(nowMs: Long = System.currentTimeMillis()) {
    val key = freshChatSessionKey(baseSessionKey = _sessionKey.value, nowMs = nowMs)
    _sessionKey.value = key
    clearPendingRuns()
    pendingToolCallsById.clear()
    publishPendingToolCalls()
    _messages.value = emptyList()
    _errorText.value = null
    _streamingAssistantText.value = null
    _sessionId.value = null
    scope.launch { bootstrap(forceHealth = true, refreshSessions = true) }
  }

  private fun normalizeRequestedSessionKey(sessionKey: String): String {
    val key = sessionKey.trim()
    if (key.isEmpty()) return appliedMainSessionKey
    if (key == "main" && appliedMainSessionKey != "main") return appliedMainSessionKey
    return key
  }

  fun sendMessage(
    message: String,
    thinkingLevel: String,
    attachments: List<OutgoingAttachment>,
  ) {
    scope.launch {
      sendMessageAwaitAcceptance(
        message = message,
        thinkingLevel = thinkingLevel,
        attachments = attachments,
      )
    }
  }

  suspend fun sendMessageAwaitAcceptance(
    message: String,
    thinkingLevel: String,
    attachments: List<OutgoingAttachment>,
  ): Boolean {
    val trimmed = message.trim()
    if (trimmed.isEmpty() && attachments.isEmpty()) return false
    if (!_healthOk.value) {
      _errorText.value = "Gateway health not OK; cannot send"
      return false
    }

    val text = if (trimmed.isEmpty() && attachments.isNotEmpty()) "See attached." else trimmed
    val sessionKey = _sessionKey.value
    val thinking = normalizeThinking(thinkingLevel)
    val runId = UUID.randomUUID().toString()
    pendingRunStartedAtMs[runId] = SystemClock.elapsedRealtime()
    logRunPhase(runId, "ui_send", "sessionKey=$sessionKey messageChars=${text.length} thinking=$thinking attachments=${attachments.size}")

    // Optimistic user message.
    val userContent =
      buildList {
        add(ChatMessageContent(type = "text", text = text))
        for (att in attachments) {
          add(
            ChatMessageContent(
              type = att.type,
              mimeType = att.mimeType,
              fileName = att.fileName,
              base64 = att.base64,
            ),
          )
        }
      }
    _messages.value =
      _messages.value +
      ChatMessage(
        id = UUID.randomUUID().toString(),
        role = "user",
        content = userContent,
        timestampMs = System.currentTimeMillis(),
      )

    armPendingRunTimeout(runId)
    synchronized(pendingRuns) {
      pendingRuns.add(runId)
      _pendingRunCount.value = pendingRuns.size
    }

    _errorText.value = null
    _streamingAssistantText.value = null
    pendingToolCallsById.clear()
    publishPendingToolCalls()

    return try {
      val params =
        buildJsonObject {
          put("sessionKey", JsonPrimitive(sessionKey))
          put("message", JsonPrimitive(text))
          put("thinking", JsonPrimitive(thinking))
          put("timeoutMs", JsonPrimitive(30_000))
          put("idempotencyKey", JsonPrimitive(runId))
          if (attachments.isNotEmpty()) {
            put(
              "attachments",
              JsonArray(
                attachments.map { att ->
                  buildJsonObject {
                    put("type", JsonPrimitive(att.type))
                    put("mimeType", JsonPrimitive(att.mimeType))
                    put("fileName", JsonPrimitive(att.fileName))
                    put("content", JsonPrimitive(att.base64))
                  }
                },
              ),
            )
          }
        }
      val res = session.request("chat.send", params.toString())
      logRunPhase(runId, "chat_send_accepted")
      val actualRunId = parseRunId(res) ?: runId
      if (actualRunId != runId) {
        pendingRunStartedAtMs[actualRunId] = pendingRunStartedAtMs[runId] ?: SystemClock.elapsedRealtime()
        firstDeltaLogged.remove(runId)
        clearPendingRun(runId)
        armPendingRunTimeout(actualRunId)
        synchronized(pendingRuns) {
          pendingRuns.add(actualRunId)
          _pendingRunCount.value = pendingRuns.size
        }
      }
      true
    } catch (err: Throwable) {
      logRunPhase(runId, "chat_send_failed", "message=${err.message.orEmpty().take(120)}")
      clearPendingRun(runId)
      _errorText.value = err.message
      false
    }
  }

  fun abort() {
    val runIds =
      synchronized(pendingRuns) {
        pendingRuns.toList()
      }
    if (runIds.isEmpty()) return
    scope.launch {
      for (runId in runIds) {
        try {
          val params =
            buildJsonObject {
              put("sessionKey", JsonPrimitive(_sessionKey.value))
              put("runId", JsonPrimitive(runId))
            }
          session.request("chat.abort", params.toString())
        } catch (_: Throwable) {
          // best-effort
        }
      }
    }
  }

  fun handleGatewayEvent(
    event: String,
    payloadJson: String?,
  ) {
    when (event) {
      "tick" -> {
        scope.launch { pollHealthIfNeeded(force = false) }
      }
      "health" -> {
        // If we receive a health snapshot, the gateway is reachable.
        _healthOk.value = true
      }
      "seqGap" -> {
        _errorText.value = eventStreamInterruptedError(assistantTextVisible = hasAssistantTextVisible())
        clearPendingRuns()
      }
      "chat" -> {
        if (payloadJson.isNullOrBlank()) return
        handleChatEvent(payloadJson)
      }
      "agent" -> {
        if (payloadJson.isNullOrBlank()) return
        handleAgentEvent(payloadJson)
      }
    }
  }

  private suspend fun bootstrap(
    forceHealth: Boolean,
    refreshSessions: Boolean,
  ) {
    _errorText.value = null
    _healthOk.value = false
    clearPendingRuns()
    pendingToolCallsById.clear()
    publishPendingToolCalls()
    _streamingAssistantText.value = null
    _sessionId.value = null

    val key = _sessionKey.value
    try {
      if (supportsChatSubscribe) {
        session.sendNodeEvent("chat.subscribe", """{"sessionKey":"$key"}""")
      }

      val historyJson = session.request("chat.history", """{"sessionKey":"$key"}""")
      val history = parseHistory(historyJson, sessionKey = key, previousMessages = _messages.value)
      _messages.value = history.messages
      _errorText.value =
        chatErrorAfterHistoryRefresh(
          messages = history.messages,
          existingError = _errorText.value,
        )
      _sessionId.value = history.sessionId
      history.thinkingLevel
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { _thinkingLevel.value = it }

      pollHealthIfNeeded(force = forceHealth)
      if (refreshSessions) {
        fetchSessions(limit = 50)
      }
    } catch (err: Throwable) {
      _errorText.value = err.message
    }
  }

  private suspend fun fetchSessions(limit: Int?) {
    try {
      val params =
        buildJsonObject {
          put("includeGlobal", JsonPrimitive(true))
          put("includeUnknown", JsonPrimitive(false))
          if (limit != null && limit > 0) put("limit", JsonPrimitive(limit))
        }
      val res = session.request("sessions.list", params.toString())
      _sessions.value = parseSessions(res)
    } catch (_: Throwable) {
      // best-effort
    }
  }

  private suspend fun pollHealthIfNeeded(force: Boolean) {
    val now = System.currentTimeMillis()
    val last = lastHealthPollAtMs
    if (!force && last != null && now - last < 10_000) return
    lastHealthPollAtMs = now
    try {
      session.request("health", null)
      _healthOk.value = true
    } catch (_: Throwable) {
      _healthOk.value = false
    }
  }

  private fun handleChatEvent(payloadJson: String) {
    val payload = json.parseToJsonElement(payloadJson).asObjectOrNull() ?: return
    val sessionKey = payload["sessionKey"].asStringOrNull()?.trim()
    if (!sessionKey.isNullOrEmpty() && sessionKey != _sessionKey.value) return

    val runId = payload["runId"].asStringOrNull()
    val isPending =
      if (runId != null) synchronized(pendingRuns) { pendingRuns.contains(runId) } else true

    val state = payload["state"].asStringOrNull()
    when (state) {
      "delta" -> {
        // Only show streaming text for runs we initiated
        if (!isPending) return
        val text = parseAssistantDeltaText(payload)
        if (!text.isNullOrEmpty()) {
          if (runId != null && firstDeltaLogged.add(runId)) {
            logRunPhase(runId, "first_delta", "chars=${text.length}")
          }
          _streamingAssistantText.value = text
        }
      }
      "final", "aborted", "error" -> {
        if (runId != null) {
          logRunPhase(runId, "terminal_event", "state=${state.orEmpty()} error=${payload["errorMessage"].asStringOrNull().orEmpty().take(120)}")
        }
        _errorText.value =
          chatErrorAfterTerminalState(
            state = state,
            payloadErrorMessage = payload["errorMessage"].asStringOrNull(),
            existingError = _errorText.value,
          )
        if (runId != null) clearPendingRun(runId) else clearPendingRuns()
        pendingToolCallsById.clear()
        publishPendingToolCalls()
        _streamingAssistantText.value = null
        scope.launch {
          try {
            val historyJson =
              session.request("chat.history", """{"sessionKey":"${_sessionKey.value}"}""")
            val history = parseHistory(historyJson, sessionKey = _sessionKey.value, previousMessages = _messages.value)
            _messages.value = history.messages
            if (runId != null) {
              logRunPhase(runId, "history_refreshed", "messages=${history.messages.size}")
              pendingRunStartedAtMs.remove(runId)
              firstDeltaLogged.remove(runId)
            }
            _errorText.value =
              chatErrorAfterHistoryRefresh(
                messages = history.messages,
                existingError = _errorText.value,
              )
            _sessionId.value = history.sessionId
            history.thinkingLevel
              ?.trim()
              ?.takeIf { it.isNotEmpty() }
              ?.let { _thinkingLevel.value = it }
          } catch (_: Throwable) {
            // best-effort
          }
        }
      }
    }
  }

  private fun handleAgentEvent(payloadJson: String) {
    val payload = json.parseToJsonElement(payloadJson).asObjectOrNull() ?: return
    val sessionKey = payload["sessionKey"].asStringOrNull()?.trim()
    if (!sessionKey.isNullOrEmpty() && sessionKey != _sessionKey.value) return

    val stream = payload["stream"].asStringOrNull()
    val data = payload["data"].asObjectOrNull()

    when (stream) {
      "assistant" -> {
        val text = data?.get("text")?.asStringOrNull()
        if (!text.isNullOrEmpty()) {
          _streamingAssistantText.value = text
        }
      }
      "tool" -> {
        val phase = data?.get("phase")?.asStringOrNull()
        val name = data?.get("name")?.asStringOrNull()
        val toolCallId = data?.get("toolCallId")?.asStringOrNull()
        if (phase.isNullOrEmpty() || name.isNullOrEmpty() || toolCallId.isNullOrEmpty()) return

        val ts = payload["ts"].asLongOrNull() ?: System.currentTimeMillis()
        if (phase == "start") {
          val args = data.get("args").asObjectOrNull()
          pendingToolCallsById[toolCallId] =
            ChatPendingToolCall(
              toolCallId = toolCallId,
              name = name,
              args = args,
              startedAtMs = ts,
              isError = null,
            )
          publishPendingToolCalls()
        } else if (phase == "result") {
          pendingToolCallsById.remove(toolCallId)
          publishPendingToolCalls()
        }
      }
      "error" -> {
        _errorText.value = eventStreamInterruptedError(assistantTextVisible = hasAssistantTextVisible())
        clearPendingRuns()
        pendingToolCallsById.clear()
        publishPendingToolCalls()
        _streamingAssistantText.value = null
      }
    }
  }

  private fun parseAssistantDeltaText(payload: JsonObject): String? {
    val message = payload["message"].asObjectOrNull() ?: return null
    if (message["role"].asStringOrNull() != "assistant") return null
    val content = message["content"].asArrayOrNull() ?: return null
    for (item in content) {
      val obj = item.asObjectOrNull() ?: continue
      if (obj["type"].asStringOrNull() != "text") continue
      val text = obj["text"].asStringOrNull()
      if (!text.isNullOrEmpty()) {
        return text
      }
    }
    return null
  }

  private fun publishPendingToolCalls() {
    _pendingToolCalls.value =
      pendingToolCallsById.values.sortedBy { it.startedAtMs }
  }

  private fun hasAssistantTextVisible(): Boolean =
    !_streamingAssistantText.value.isNullOrBlank() ||
      messagesContainAssistantText(_messages.value)

  private fun logRunPhase(
    runId: String,
    phase: String,
    extra: String = "",
  ) {
    val now = SystemClock.elapsedRealtime()
    val started = pendingRunStartedAtMs[runId] ?: now
    val suffix = if (extra.isBlank()) "" else " $extra"
    Log.d("OpenClawChatPerf", "run=$runId phase=$phase t=${System.currentTimeMillis()} elapsedMs=${now - started}$suffix")
  }

  private fun armPendingRunTimeout(runId: String) {
    pendingRunTimeoutJobs[runId]?.cancel()
    pendingRunTimeoutJobs[runId] =
      scope.launch {
        delay(pendingRunTimeoutMs)
        val stillPending =
          synchronized(pendingRuns) {
            pendingRuns.contains(runId)
          }
        if (!stillPending) return@launch
        clearPendingRun(runId)
        _errorText.value = "Timed out waiting for a reply; try again or refresh."
      }
  }

  private fun clearPendingRun(runId: String) {
    pendingRunTimeoutJobs.remove(runId)?.cancel()
    synchronized(pendingRuns) {
      pendingRuns.remove(runId)
      _pendingRunCount.value = pendingRuns.size
    }
  }

  private fun clearPendingRuns() {
    for ((_, job) in pendingRunTimeoutJobs) {
      job.cancel()
    }
    pendingRunTimeoutJobs.clear()
    pendingRunStartedAtMs.clear()
    firstDeltaLogged.clear()
    synchronized(pendingRuns) {
      pendingRuns.clear()
      _pendingRunCount.value = 0
    }
  }

  private fun parseHistory(
    historyJson: String,
    sessionKey: String,
    previousMessages: List<ChatMessage>,
  ): ChatHistory {
    val root = json.parseToJsonElement(historyJson).asObjectOrNull() ?: return ChatHistory(sessionKey, null, null, emptyList())
    val sid = root["sessionId"].asStringOrNull()
    val thinkingLevel = root["thinkingLevel"].asStringOrNull()
    val array = root["messages"].asArrayOrNull() ?: JsonArray(emptyList())

    val messages =
      array.mapNotNull { item ->
        val obj = item.asObjectOrNull() ?: return@mapNotNull null
        val role = obj["role"].asStringOrNull() ?: return@mapNotNull null
        val content = obj["content"].asArrayOrNull()?.mapNotNull(::parseMessageContent) ?: emptyList()
        val ts = obj["timestamp"].asLongOrNull()
        ChatMessage(
          id = UUID.randomUUID().toString(),
          role = role,
          content = content,
          timestampMs = ts,
        )
      }

    return ChatHistory(
      sessionKey = sessionKey,
      sessionId = sid,
      thinkingLevel = thinkingLevel,
      messages = reconcileMessageIds(previous = previousMessages, incoming = displayableHistoryMessages(messages)),
    )
  }

  private fun parseMessageContent(el: JsonElement): ChatMessageContent? {
    val obj = el.asObjectOrNull() ?: return null
    val type = obj["type"].asStringOrNull() ?: "text"
    return if (type == "text") {
      ChatMessageContent(type = "text", text = obj["text"].asStringOrNull())
    } else {
      ChatMessageContent(
        type = type,
        mimeType = obj["mimeType"].asStringOrNull(),
        fileName = obj["fileName"].asStringOrNull(),
        base64 = obj["content"].asStringOrNull(),
      )
    }
  }

  private fun parseSessions(jsonString: String): List<ChatSessionEntry> {
    val root = json.parseToJsonElement(jsonString).asObjectOrNull() ?: return emptyList()
    val sessions = root["sessions"].asArrayOrNull() ?: return emptyList()
    return sessions.mapNotNull { item ->
      val obj = item.asObjectOrNull() ?: return@mapNotNull null
      val key = obj["key"].asStringOrNull()?.trim().orEmpty()
      if (key.isEmpty()) return@mapNotNull null
      val updatedAt = obj["updatedAt"].asLongOrNull()
      val displayName = obj["displayName"].asStringOrNull()?.trim()
      ChatSessionEntry(key = key, updatedAtMs = updatedAt, displayName = displayName)
    }
  }

  private fun parseRunId(resJson: String): String? =
    try {
      json
        .parseToJsonElement(resJson)
        .asObjectOrNull()
        ?.get("runId")
        .asStringOrNull()
    } catch (_: Throwable) {
      null
    }

  private fun normalizeThinking(raw: String): String =
    when (raw.trim().lowercase()) {
      "low" -> "low"
      "medium" -> "medium"
      "high" -> "high"
      else -> "off"
    }
}

internal data class MainSessionState(
  val currentSessionKey: String,
  val appliedMainSessionKey: String,
)

internal fun applyMainSessionKey(
  currentSessionKey: String,
  appliedMainSessionKey: String,
  nextMainSessionKey: String,
): MainSessionState {
  if (currentSessionKey == appliedMainSessionKey) {
    return MainSessionState(
      currentSessionKey = nextMainSessionKey,
      appliedMainSessionKey = nextMainSessionKey,
    )
  }
  return MainSessionState(
    currentSessionKey = currentSessionKey,
    appliedMainSessionKey = nextMainSessionKey,
  )
}

internal fun freshChatSessionKey(
  baseSessionKey: String,
  nowMs: Long,
): String {
  val base = baseSessionKey.trim().ifEmpty { "main" }
  return "$base:clean:$nowMs"
}

internal fun displayableHistoryMessages(messages: List<ChatMessage>): List<ChatMessage> =
  messages.filterNot(::isHiddenSystemHistoryMessage)

private fun isHiddenSystemHistoryMessage(message: ChatMessage): Boolean =
  message.role.equals("system", ignoreCase = true)

internal fun reconcileMessageIds(
  previous: List<ChatMessage>,
  incoming: List<ChatMessage>,
): List<ChatMessage> {
  if (previous.isEmpty() || incoming.isEmpty()) return incoming

  val idsByKey = LinkedHashMap<String, ArrayDeque<String>>()
  for (message in previous) {
    val key = messageIdentityKey(message) ?: continue
    idsByKey.getOrPut(key) { ArrayDeque() }.addLast(message.id)
  }

  return incoming.map { message ->
    val key = messageIdentityKey(message) ?: return@map message
    val ids = idsByKey[key] ?: return@map message
    val reusedId = ids.removeFirstOrNull() ?: return@map message
    if (ids.isEmpty()) {
      idsByKey.remove(key)
    }
    if (reusedId == message.id) return@map message
    message.copy(id = reusedId)
  }
}

internal fun messageIdentityKey(message: ChatMessage): String? {
  val role = message.role.trim().lowercase()
  if (role.isEmpty()) return null

  val timestamp = message.timestampMs?.toString().orEmpty()
  val contentFingerprint =
    message.content.joinToString(separator = "\u001E") { part ->
      listOf(
        part.type.trim().lowercase(),
        part.text?.trim().orEmpty(),
        part.mimeType
          ?.trim()
          ?.lowercase()
          .orEmpty(),
        part.fileName?.trim().orEmpty(),
        part.base64
          ?.hashCode()
          ?.toString()
          .orEmpty(),
      ).joinToString(separator = "\u001F")
    }

  if (timestamp.isEmpty() && contentFingerprint.isEmpty()) return null
  return listOf(role, timestamp, contentFingerprint).joinToString(separator = "|")
}

private fun JsonElement?.asObjectOrNull(): JsonObject? = this as? JsonObject

private fun JsonElement?.asArrayOrNull(): JsonArray? = this as? JsonArray

private fun JsonElement?.asStringOrNull(): String? =
  when (this) {
    is JsonNull -> null
    is JsonPrimitive -> content
    else -> null
  }

private fun JsonElement?.asLongOrNull(): Long? =
  when (this) {
    is JsonPrimitive -> content.toLongOrNull()
    else -> null
  }
