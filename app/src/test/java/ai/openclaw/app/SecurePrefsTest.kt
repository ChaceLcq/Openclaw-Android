package ai.openclaw.app

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import ai.openclaw.app.node.LocalModelProviderConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SecurePrefsTest {
  @Test
  fun defaultsManualGatewayToLocalDeviceGateway() {
    val context = RuntimeEnvironment.getApplication()
    val plainPrefs = context.getSharedPreferences("openclaw.node", Context.MODE_PRIVATE)
    plainPrefs.edit().clear().commit()

    val prefs = SecurePrefs(context)

    assertTrue(prefs.manualEnabled.value)
    assertEquals("127.0.0.1", prefs.manualHost.value)
    assertEquals(SecurePrefs.defaultManualGatewayPort, prefs.manualPort.value)
    assertFalse(prefs.manualTls.value)
  }

  @Test
  fun loadLocationMode_migratesLegacyAlwaysValue() {
    val context = RuntimeEnvironment.getApplication()
    val plainPrefs = context.getSharedPreferences("openclaw.node", Context.MODE_PRIVATE)
    plainPrefs
      .edit()
      .clear()
      .putString("location.enabledMode", "always")
      .commit()

    val prefs = SecurePrefs(context)

    assertEquals(LocationMode.WhileUsing, prefs.locationMode.value)
    assertEquals("whileUsing", plainPrefs.getString("location.enabledMode", null))
  }

  @Test
  fun voiceMicEnabled_ignoresOldTalkEnabledKey() {
    val context = RuntimeEnvironment.getApplication()
    val plainPrefs = context.getSharedPreferences("openclaw.node", Context.MODE_PRIVATE)
    plainPrefs
      .edit()
      .clear()
      .putBoolean("talk.enabled", true)
      .commit()

    val prefs = SecurePrefs(context)

    assertFalse(prefs.voiceMicEnabled.value)
    assertFalse(plainPrefs.contains("voice.micEnabled"))
  }

  @Test
  fun setVoiceMicEnabled_persistsNewKeyOnly() {
    val context = RuntimeEnvironment.getApplication()
    val plainPrefs = context.getSharedPreferences("openclaw.node", Context.MODE_PRIVATE)
    plainPrefs
      .edit()
      .clear()
      .putBoolean("talk.enabled", false)
      .commit()
    val prefs = SecurePrefs(context)

    prefs.setVoiceMicEnabled(true)

    assertTrue(prefs.voiceMicEnabled.value)
    assertTrue(plainPrefs.getBoolean("voice.micEnabled", false))
    assertFalse(plainPrefs.getBoolean("talk.enabled", false))
  }

  @Test
  fun saveGatewayBootstrapToken_persistsSeparatelyFromSharedToken() {
    val context = RuntimeEnvironment.getApplication()
    val securePrefs = context.getSharedPreferences("openclaw.node.secure.test", Context.MODE_PRIVATE)
    securePrefs.edit().clear().commit()
    val prefs = SecurePrefs(context, securePrefsOverride = securePrefs)

    prefs.setGatewayToken("shared-token")
    prefs.setGatewayBootstrapToken("bootstrap-token")

    assertEquals("shared-token", prefs.loadGatewayToken())
    assertEquals("bootstrap-token", prefs.loadGatewayBootstrapToken())
    assertEquals("bootstrap-token", prefs.gatewayBootstrapToken.value)
  }

  @Test
  fun clearGatewaySetupAuth_removesStoredGatewayAuth() {
    val context = RuntimeEnvironment.getApplication()
    val securePrefs = context.getSharedPreferences("openclaw.node.secure.test.clear", Context.MODE_PRIVATE)
    securePrefs.edit().clear().commit()
    val prefs = SecurePrefs(context, securePrefsOverride = securePrefs)

    prefs.setGatewayToken("shared-token")
    prefs.setGatewayBootstrapToken("bootstrap-token")
    prefs.setGatewayPassword("password-token")

    prefs.clearGatewaySetupAuth()

    assertEquals("", prefs.gatewayToken.value)
    assertEquals("", prefs.gatewayBootstrapToken.value)
    assertNull(prefs.loadGatewayToken())
    assertNull(prefs.loadGatewayBootstrapToken())
    assertNull(prefs.loadGatewayPassword())
  }

  @Test
  fun localModelProviderConfig_persistsKeySeparatelyFromModelMetadata() {
    val context = RuntimeEnvironment.getApplication()
    val securePrefs = context.getSharedPreferences("openclaw.node.secure.test.local-model", Context.MODE_PRIVATE)
    securePrefs.edit().clear().commit()
    val plainPrefs = context.getSharedPreferences("openclaw.node", Context.MODE_PRIVATE)
    plainPrefs.edit().clear().commit()
    val prefs = SecurePrefs(context, securePrefsOverride = securePrefs)

    prefs.setLocalModelProviderConfig(
      baseUrl = " https://example.test/anthropic ",
      apiKey = " test-key ",
      primaryModel = "qwen3.6-plus",
      modelIds = listOf("qwen3.6-plus", "custom-model"),
    )

    val settings = prefs.localModelProviderSettings()

    assertEquals("https://example.test/anthropic", settings.baseUrl)
    assertEquals("test-key", settings.apiKey)
    assertEquals("qwen3.6-plus", settings.primaryModel)
    assertEquals(listOf("qwen3.6-plus", "custom-model"), settings.modelIds.take(2))
    assertFalse(plainPrefs.all.values.any { it.toString().contains("test-key") })
  }

  @Test
  fun localModelProviderConfig_removesDisabledDlaProviderFromGatewayConfig() {
    val existing =
      Json
        .parseToJsonElement(
          """
          {
            "models": {
              "mode": "merge",
              "providers": {
                "qwen3-dla": {
                  "baseUrl": "http://127.0.0.1:8081/v1",
                  "api": "openai-completions",
                  "models": [{ "id": "qwen3-1.7b-dla" }]
                },
                "dashscope-coding": {
                  "baseUrl": "https://old.example",
                  "models": [{ "id": "old" }]
                }
              }
            },
            "agents": {
              "defaults": {
                "model": { "primary": "qwen3-dla/qwen3-1.7b-dla" },
                "models": { "qwen3-dla/qwen3-1.7b-dla": {} }
              },
              "list": [
                { "id": "main", "default": true, "model": "qwen3-dla/qwen3-1.7b-dla" }
              ]
            }
          }
          """.trimIndent(),
        ).jsonObject

    val next =
      LocalModelProviderConfig.applyProviderConfig(
        existing = existing,
        settings =
          LocalModelProviderSettings(
            providerId = SecurePrefs.defaultLocalModelProviderId,
            baseUrl = "https://coding.dashscope.aliyuncs.com/apps/anthropic",
            apiKey = "cloud-key",
            primaryModel = "qwen3.6-plus",
            modelIds = listOf("qwen3.6-plus"),
          ),
      )

    val providers = next["models"]!!.jsonObject["providers"]!!.jsonObject
    val defaults = next["agents"]!!.jsonObject["defaults"]!!.jsonObject
    val defaultModels = defaults["models"]!!.jsonObject
    val firstAgent = (next["agents"]!!.jsonObject["list"] as kotlinx.serialization.json.JsonArray)[0].jsonObject

    assertFalse(providers.containsKey("qwen3-dla"))
    assertFalse(defaultModels.containsKey("qwen3-dla/qwen3-1.7b-dla"))
    assertEquals("dashscope-coding/qwen3.6-plus", defaults["model"]!!.jsonObject["primary"].toString().trim('"'))
    assertEquals("dashscope-coding/qwen3.6-plus", firstAgent["model"].toString().trim('"'))
  }
}
