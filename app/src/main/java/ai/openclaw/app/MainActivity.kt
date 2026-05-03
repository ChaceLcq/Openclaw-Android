package ai.openclaw.app

import ai.openclaw.app.gateway.GatewayEndpoint
import ai.openclaw.app.ui.OpenClawTheme
import ai.openclaw.app.ui.RootScreen
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
  private val viewModel: MainViewModel by viewModels()
  private lateinit var permissionRequester: PermissionRequester
  private var didAttachRuntimeUi = false
  private var didStartNodeService = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    handleLaunchIntent(intent)
    WindowCompat.setDecorFitsSystemWindows(window, false)
    permissionRequester = PermissionRequester(this)

    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        viewModel.preventSleep.collect { enabled ->
          if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
          } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
          }
        }
      }
    }

    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        viewModel.runtimeInitialized.collect { ready ->
          if (!ready || didAttachRuntimeUi) return@collect
          viewModel.attachRuntimeUi(owner = this@MainActivity, permissionRequester = permissionRequester)
          didAttachRuntimeUi = true
          if (!didStartNodeService) {
            NodeForegroundService.start(this@MainActivity)
            didStartNodeService = true
          }
        }
      }
    }

    setContent {
      OpenClawTheme {
        Surface(modifier = Modifier) {
          RootScreen(viewModel = viewModel)
        }
      }
    }
  }

  override fun onStart() {
    super.onStart()
    viewModel.setForeground(true)
  }

  override fun onStop() {
    viewModel.setForeground(false)
    super.onStop()
  }

  override fun onNewIntent(intent: android.content.Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleLaunchIntent(intent)
  }

  private fun handleLaunchIntent(intent: android.content.Intent?) {
    if (handleGatewayConfigureIntent(intent)) return
    handleAssistantIntent(intent)
  }

  private fun handleGatewayConfigureIntent(intent: android.content.Intent?): Boolean {
    if (!BuildConfig.DEBUG) return false
    val request = parseGatewayConfigureIntent(intent) ?: return false
    viewModel.setManualEnabled(true)
    viewModel.setManualHost(request.host)
    viewModel.setManualPort(request.port)
    viewModel.setManualTls(request.tls)
    viewModel.setGatewayToken(request.token.orEmpty())
    viewModel.setGatewayBootstrapToken(request.bootstrapToken.orEmpty())
    viewModel.setGatewayPassword(request.password.orEmpty())
    viewModel.setOnboardingCompleted(true)
    viewModel.connect(
      GatewayEndpoint.manual(host = request.host, port = request.port),
      token = request.token,
      bootstrapToken = request.bootstrapToken,
      password = request.password,
    )
    return true
  }

  private fun handleAssistantIntent(intent: android.content.Intent?) {
    val request = parseAssistantLaunchIntent(intent) ?: return
    viewModel.handleAssistantLaunch(request)
  }
}
