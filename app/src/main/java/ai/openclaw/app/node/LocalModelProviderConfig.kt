package ai.openclaw.app.node

import ai.openclaw.app.LocalModelProviderSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import java.security.SecureRandom

object LocalModelProviderConfig {
  private val json = Json { prettyPrint = true }
  private val secureRandom = SecureRandom()

  data class SyncResult(
    val gatewayToken: String,
  )

  fun sync(
    openClawHome: File,
    settings: LocalModelProviderSettings,
    gatewayPort: Int = 18790,
  ): SyncResult? {
    val apiKey = settings.apiKey.trim()
    val baseUrl = settings.baseUrl.trim()
    val primary = settings.primaryModel.trim()
    val models = settings.modelIds.map { it.trim() }.filter { it.isNotEmpty() }
    if (apiKey.isEmpty()) return null
    if (baseUrl.isEmpty() || primary.isEmpty() || models.isEmpty()) return null

    val configDir = openClawHome.resolve(".openclaw")
    val configFile = configDir.resolve("openclaw.json")
    configDir.mkdirs()

    val existing =
      runCatching {
        if (configFile.exists()) {
          json.parseToJsonElement(configFile.readText()).asObject()
        } else {
          JsonObject(emptyMap())
        }
      }.getOrElse { JsonObject(emptyMap()) }

    val next =
      applyProviderConfig(
        existing,
        settings.copy(apiKey = apiKey, baseUrl = baseUrl, primaryModel = primary, modelIds = models),
        gatewayPort = gatewayPort,
      )
    configFile.writeText(json.encodeToString(JsonElement.serializer(), next))
    return SyncResult(gatewayToken(next))
  }

  internal fun applyProviderConfig(
    existing: JsonObject,
    settings: LocalModelProviderSettings,
    gatewayPort: Int = 18790,
  ): JsonObject {
    val providerId = settings.providerId.trim().ifEmpty { "dashscope-coding" }
    val models = sanitizeModels(settings.modelIds, settings.primaryModel)
    require(models.isNotEmpty()) { "At least one model is required." }
    val primary = settings.primaryModel.trim().takeIf { models.contains(it) } ?: models.first()
    return mergeJsonObjects(
      existing,
      mapOf(
        "models" to
          mergeJsonObjects(
            existing["models"].asObjectOrEmpty(),
            mapOf(
              "mode" to JsonPrimitive("merge"),
              "providers" to
                mergeJsonObjects(
                  existing["models"].asObjectOrEmpty()["providers"].asObjectOrEmpty(),
                  mapOf(providerId to providerJson(settings, models)),
                ),
            ),
          ),
        "agents" to
          mergeJsonObjects(
            existing["agents"].asObjectOrEmpty(),
            mapOf(
              "defaults" to
                mergeJsonObjects(
                  existing["agents"].asObjectOrEmpty()["defaults"].asObjectOrEmpty(),
                  mapOf(
                    "model" to JsonObject(mapOf("primary" to JsonPrimitive("$providerId/$primary"))),
                    "models" to JsonObject(mapOf("$providerId/$primary" to JsonObject(emptyMap()))),
                  ),
                ),
              "list" to updateAgentsList(existing["agents"].asObjectOrEmpty()["list"], providerId, primary),
            ),
          ),
        "gateway" to gatewayJson(existing["gateway"].asObjectOrEmpty(), gatewayPort = gatewayPort),
      ),
    )
  }

  private fun gatewayJson(
    existingGateway: JsonObject,
    gatewayPort: Int,
  ): JsonObject =
    mergeJsonObjects(
      existingGateway,
      mapOf(
        "port" to JsonPrimitive(gatewayPort),
        "mode" to JsonPrimitive("local"),
        "bind" to JsonPrimitive("loopback"),
        "auth" to
          mergeJsonObjects(
            existingGateway["auth"].asObjectOrEmpty(),
            mapOf(
              "mode" to JsonPrimitive("token"),
              "token" to
                JsonPrimitive(
                  existingGateway["auth"].asObjectOrEmpty()["token"].asStringOrNull() ?: newToken(),
                ),
            ),
          ),
      ),
    )

  private fun providerJson(
    settings: LocalModelProviderSettings,
    models: List<String>,
  ): JsonObject =
    buildJsonObject {
      put("baseUrl", JsonPrimitive(settings.baseUrl.trim()))
      put("apiKey", JsonPrimitive(settings.apiKey.trim()))
      put("auth", JsonPrimitive("api-key"))
      put("api", JsonPrimitive("anthropic-messages"))
      put("authHeader", JsonPrimitive(true))
      put("models", buildJsonArray { models.forEach { add(modelJson(it)) } })
    }

  private fun modelJson(id: String): JsonObject =
    buildJsonObject {
      put("id", JsonPrimitive(id))
      put("name", JsonPrimitive(id))
      put("api", JsonPrimitive("anthropic-messages"))
      put("reasoning", JsonPrimitive(false))
      put("input", JsonArray(listOf(JsonPrimitive("text"))))
      put(
        "cost",
        buildJsonObject {
          put("input", JsonPrimitive(0))
          put("output", JsonPrimitive(0))
          put("cacheRead", JsonPrimitive(0))
          put("cacheWrite", JsonPrimitive(0))
        },
      )
      put("contextWindow", JsonPrimitive(262144))
      put("maxTokens", JsonPrimitive(65536))
    }

  private fun updateAgentsList(
    current: JsonElement?,
    providerId: String,
    primary: String,
  ): JsonArray {
    val currentAgents = current as? JsonArray ?: JsonArray(emptyList())
    if (currentAgents.isEmpty()) {
      return JsonArray(
        listOf(
          buildJsonObject {
            put("id", JsonPrimitive("main"))
            put("default", JsonPrimitive(true))
            put("model", JsonPrimitive("$providerId/$primary"))
          },
        ),
      )
    }
    return JsonArray(
      currentAgents.mapIndexed { index, item ->
        val obj = item.asObjectOrEmpty()
        val shouldPatch =
          obj["default"] == JsonPrimitive(true) ||
            obj["id"] == JsonPrimitive("main") ||
            index == 0
        if (shouldPatch) {
          mergeJsonObjects(obj, mapOf("model" to JsonPrimitive("$providerId/$primary")))
        } else {
          obj
        }
      },
    )
  }

  private fun sanitizeModels(
    modelIds: List<String>,
    primary: String,
  ): List<String> =
    (modelIds + primary)
      .map { it.trim() }
      .filter { it.isNotEmpty() }
      .distinct()

  private fun mergeJsonObjects(
    base: JsonObject,
    updates: Map<String, JsonElement>,
  ): JsonObject = JsonObject(base.toMutableMap().apply { putAll(updates) })

  private fun JsonElement?.asObjectOrEmpty(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())

  private fun JsonElement.asObject(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())

  private fun JsonElement?.asStringOrNull(): String? =
    (this as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }

  private fun gatewayToken(config: JsonObject): String =
    config["gateway"].asObjectOrEmpty()["auth"].asObjectOrEmpty()["token"].asStringOrNull()
      ?: error("Local gateway token was not written")

  private fun newToken(): String {
    val bytes = ByteArray(24)
    secureRandom.nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
  }
}
