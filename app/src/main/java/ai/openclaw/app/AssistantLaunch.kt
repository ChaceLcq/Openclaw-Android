package ai.openclaw.app

import android.content.Intent

const val actionAskOpenClaw = "io.github.openclawcn.app.action.ASK_OPENCLAW"
const val actionConfigureGateway = "io.github.openclawcn.app.action.CONFIGURE_GATEWAY"
const val extraAssistantPrompt = "prompt"
const val extraGatewayHost = "host"
const val extraGatewayPort = "port"
const val extraGatewayTls = "tls"
const val extraGatewayToken = "token"
const val extraGatewayBootstrapToken = "bootstrapToken"
const val extraGatewayPassword = "password"

enum class HomeDestination {
  Connect,
  Chat,
  Voice,
  Screen,
  Settings,
}

data class AssistantLaunchRequest(
  val source: String,
  val prompt: String?,
  val autoSend: Boolean,
)

data class GatewayConfigureRequest(
  val host: String,
  val port: Int,
  val tls: Boolean,
  val token: String?,
  val bootstrapToken: String?,
  val password: String?,
)

fun parseAssistantLaunchIntent(intent: Intent?): AssistantLaunchRequest? {
  val action = intent?.action ?: return null
  return when (action) {
    Intent.ACTION_ASSIST ->
      AssistantLaunchRequest(
        source = "assist",
        prompt = null,
        autoSend = false,
      )

    actionAskOpenClaw -> {
      val prompt = intent.getStringExtra(extraAssistantPrompt)?.trim()?.ifEmpty { null }
      AssistantLaunchRequest(
        source = "app_action",
        prompt = prompt,
        autoSend = false,
      )
    }

    else -> null
  }
}

fun parseGatewayConfigureIntent(intent: Intent?): GatewayConfigureRequest? {
  if (intent?.action != actionConfigureGateway) return null
  val host = intent.getStringExtra(extraGatewayHost)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
  val port = intent.getIntExtra(extraGatewayPort, SecurePrefs.defaultManualGatewayPort)
  if (port !in 1..65535) return null
  return GatewayConfigureRequest(
    host = host,
    port = port,
    tls = intent.getBooleanExtra(extraGatewayTls, SecurePrefs.defaultManualGatewayTls),
    token = intent.getStringExtra(extraGatewayToken)?.trim()?.takeIf { it.isNotEmpty() },
    bootstrapToken = intent.getStringExtra(extraGatewayBootstrapToken)?.trim()?.takeIf { it.isNotEmpty() },
    password = intent.getStringExtra(extraGatewayPassword)?.trim()?.takeIf { it.isNotEmpty() },
  )
}
