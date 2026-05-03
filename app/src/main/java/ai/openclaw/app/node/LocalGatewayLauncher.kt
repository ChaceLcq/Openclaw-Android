package ai.openclaw.app.node

import ai.openclaw.app.gateway.GatewayEndpoint
import ai.openclaw.app.gateway.GatewayTlsParams
import ai.openclaw.app.gateway.isLoopbackGatewayHost
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

class LocalGatewayLauncher(
  context: Context? = null,
  private val runner: CommandRunner = ProcessCommandRunner(),
  private val nativeLibraryDir: String? = context?.applicationContext?.applicationInfo?.nativeLibraryDir,
) {
  private val appContext = context?.applicationContext
  private val appRoot = context?.applicationContext?.filesDir?.resolve("openclaw")
  private val appPrefix = appRoot?.resolve("usr")?.absolutePath
  private val appHome = appRoot?.resolve("home")?.absolutePath
  private val pidFile = appHome?.let { File(it).resolve(".openclaw/android-gateway.pid") }
  private val bundledNativeLibDir = appRoot?.resolve("native-libs/arm64-v8a")?.absolutePath
  private val packagedNode = nativeLibraryDir?.trimEnd('/')?.let { nativeDir ->
    listOf(
      "$nativeDir/$PACKAGED_NODE_LIBRARY",
      "$nativeDir/arm64/$PACKAGED_NODE_LIBRARY",
    )
  }.orEmpty()
  private val startLock = Mutex()

  suspend fun ensureStarted(
    endpoint: GatewayEndpoint,
    tls: GatewayTlsParams?,
  ): LocalGatewayLaunchResult =
    startLock.withLock {
      ensureStartedLocked(endpoint, tls)
    }

  private suspend fun ensureStartedLocked(
    endpoint: GatewayEndpoint,
    tls: GatewayTlsParams?,
  ): LocalGatewayLaunchResult {
    if (!shouldAttemptStart(endpoint, tls)) {
      return LocalGatewayLaunchResult.Skipped
    }
    if (isOwnGatewayRunning()) {
      Log.i(LOG_TAG, "Stopping previous app-owned local gateway before restart")
      stopLocked()
      if (canConnect(endpoint.host, endpoint.port)) {
        Log.w(LOG_TAG, "Local gateway port is still reachable after stopping previous pid")
        return LocalGatewayLaunchResult.PortInUse
      }
    }
    if (canConnect(endpoint.host, endpoint.port)) {
      Log.w(LOG_TAG, "Local gateway port is reachable but not owned by this app")
      return LocalGatewayLaunchResult.PortInUse
    }

    installBundledRuntime()
    stageBundledNativeLibraries()
    val command = buildLocalGatewayStartScript()
    val direct = runner.run(listOf("/system/bin/sh", "-c", command), timeoutMs = 10_000)
    Log.i(LOG_TAG, "Local gateway direct start exit=${direct.exitCode}")

    if (direct.success && waitUntilReachable(endpoint.host, endpoint.port, attempts = 12)) {
      Log.i(LOG_TAG, "Local gateway became reachable after direct start")
      return LocalGatewayLaunchResult.Started
    }

    return if (waitUntilReachable(endpoint.host, endpoint.port)) {
      Log.i(LOG_TAG, "Local gateway became reachable")
      LocalGatewayLaunchResult.Started
    } else {
      Log.w(LOG_TAG, "Local gateway did not become reachable")
      LocalGatewayLaunchResult.Failed
    }
  }

  suspend fun stop(): LocalGatewayStopResult =
    startLock.withLock {
      stopLocked()
    }

  private suspend fun stopLocked(): LocalGatewayStopResult {
    val command = buildLocalGatewayStopScript()
    val result = runner.run(listOf("/system/bin/sh", "-c", command), timeoutMs = 8_000)
    return if (result.success) {
      LocalGatewayStopResult.Stopped
    } else {
      LocalGatewayStopResult.Failed
    }
  }

  internal fun shouldAttemptStart(
    endpoint: GatewayEndpoint,
    tls: GatewayTlsParams?,
  ): Boolean {
    if (tls != null) return false
    if (!isLoopbackGatewayHost(endpoint.host, allowEmulatorBridgeAlias = false)) return false
    return endpoint.port == DEFAULT_LOCAL_GATEWAY_PORT
  }

  private suspend fun waitUntilReachable(
    host: String,
    port: Int,
    attempts: Int = 150,
  ): Boolean {
    repeat(attempts) {
      if (canConnect(host, port)) return true
      delay(500)
    }
    return false
  }

  private suspend fun canConnect(
    host: String,
    port: Int,
  ): Boolean =
    withContext(Dispatchers.IO) {
      runCatching {
        Socket().use { socket ->
          socket.connect(InetSocketAddress(host.trim().trim('[', ']'), port), 650)
        }
      }.isSuccess
    }

  private suspend fun isOwnGatewayRunning(): Boolean =
    withContext(Dispatchers.IO) {
      val pid = pidFile?.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() } ?: return@withContext false
      runner.run(listOf("/system/bin/sh", "-c", "kill -0 '$pid' 2>/dev/null"), timeoutMs = 2_000).success
    }

  internal fun buildLocalGatewayStartScript(): String =
    """
    APP_PREFIX="${appPrefix.orEmpty()}"
    APP_HOME="${appHome.orEmpty()}"
    NATIVE_LIBRARY_DIR="${nativeLibraryDir.orEmpty()}"
    BUNDLED_NATIVE_LIB_DIR="${bundledNativeLibDir.orEmpty()}"
    PACKAGED_NODE=""
    for candidate in ${packagedNode.joinToString(" ") { "\"$it\"" }}; do
      if [ -x "${'$'}candidate" ]; then
        PACKAGED_NODE="${'$'}candidate"
        break
      fi
    done
    if [ -n "${'$'}APP_PREFIX" ] && [ -x "${'$'}APP_PREFIX/bin/openclaw-cn" ]; then
      PREFIX="${'$'}APP_PREFIX"
      HOME="${'$'}APP_HOME"
    else
      PREFIX="$TERMUX_PREFIX"
      HOME="$TERMUX_HOME"
    fi
    export HOME
    export PREFIX
    export TMPDIR="${'$'}PREFIX/tmp"
    export TMP="${'$'}TMPDIR"
    export TEMP="${'$'}TMPDIR"
    if [ -n "${'$'}NATIVE_LIBRARY_DIR" ]; then
      export LD_LIBRARY_PATH="${'$'}BUNDLED_NATIVE_LIB_DIR:${'$'}NATIVE_LIBRARY_DIR:${'$'}PREFIX/lib"
    else
      export LD_LIBRARY_PATH="${'$'}BUNDLED_NATIVE_LIB_DIR:${'$'}PREFIX/lib"
    fi
    export PATH="${'$'}PREFIX/bin:/system/bin:/system/xbin"
    if [ -n "${'$'}PACKAGED_NODE" ]; then
      export OPENCLAW_NODE="${'$'}PACKAGED_NODE"
    fi
    mkdir -p "${'$'}HOME" "${'$'}HOME/.openclaw" "${'$'}PREFIX/tmp" "${'$'}PREFIX/tmp/clawdbot"
    ENTRY="${'$'}PREFIX/lib/node_modules/openclaw-cn/dist/entry.js"
    LOGGER="${'$'}PREFIX/lib/node_modules/openclaw-cn/dist/logging/logger.js"
    if [ -f "${'$'}LOGGER" ] && grep -q 'DEFAULT_LOG_DIR = "/tmp/clawdbot"' "${'$'}LOGGER"; then
      sed -i 's#DEFAULT_LOG_DIR = "/tmp/clawdbot"#DEFAULT_LOG_DIR = process.env.OPENCLAW_ANDROID_LOG_DIR ?? "/tmp/clawdbot"#' "${'$'}LOGGER"
    fi
    export OPENCLAW_ANDROID_LOG_DIR="${'$'}PREFIX/tmp/clawdbot"
    if [ -n "${'$'}PACKAGED_NODE" ] && [ -x "${'$'}PACKAGED_NODE" ] && [ -f "${'$'}ENTRY" ]; then
      LAUNCH_MODE="packaged-node"
    elif [ -x "${'$'}PREFIX/bin/openclaw-cn" ]; then
      LAUNCH_MODE="wrapper"
    else
      exit 127
    fi
    PID_FILE="${'$'}HOME/.openclaw/android-gateway.pid"
    LOG_FILE="${'$'}HOME/.openclaw/android-gateway.log"
    old="${'$'}(cat "${'$'}PID_FILE" 2>/dev/null || true)"
    if [ -n "${'$'}old" ] && kill -0 "${'$'}old" 2>/dev/null; then
      exit 0
    fi
    echo "launch_mode=${'$'}LAUNCH_MODE packaged_node=${'$'}PACKAGED_NODE native_library_dir=${'$'}NATIVE_LIBRARY_DIR" >"${'$'}LOG_FILE"
    if [ "${'$'}LAUNCH_MODE" = "packaged-node" ]; then
      nohup "${'$'}PACKAGED_NODE" "${'$'}ENTRY" gateway run >>"${'$'}LOG_FILE" 2>&1 &
    else
      nohup "${'$'}PREFIX/bin/openclaw-cn" gateway run >>"${'$'}LOG_FILE" 2>&1 &
    fi
    echo ${'$'}! >"${'$'}PID_FILE"
    """.trimIndent()

  internal fun buildLocalGatewayStopScript(): String =
    """
    APP_HOME="${appHome.orEmpty()}"
    if [ -z "${'$'}APP_HOME" ]; then
      exit 0
    fi
    PID_FILE="${'$'}APP_HOME/.openclaw/android-gateway.pid"
    LOG_FILE="${'$'}APP_HOME/.openclaw/android-gateway.log"
    old="${'$'}(cat "${'$'}PID_FILE" 2>/dev/null || true)"
    if [ -z "${'$'}old" ]; then
      rm -f "${'$'}PID_FILE"
      exit 0
    fi
    if kill -0 "${'$'}old" 2>/dev/null; then
      echo "stopping pid=${'$'}old" >>"${'$'}LOG_FILE"
      kill "${'$'}old" 2>/dev/null || true
      for i in 1 2 3 4 5 6 7 8 9 10; do
        if ! kill -0 "${'$'}old" 2>/dev/null; then
          rm -f "${'$'}PID_FILE"
          exit 0
        fi
        sleep 0.2
      done
      kill -9 "${'$'}old" 2>/dev/null || true
    fi
    rm -f "${'$'}PID_FILE"
    exit 0
    """.trimIndent()

  private suspend fun stageBundledNativeLibraries() {
    val context = appContext ?: return
    val targetDir = appRoot?.resolve("native-libs/arm64-v8a") ?: return
    withContext(Dispatchers.IO) {
      targetDir.mkdirs()
      BUNDLED_NATIVE_LIBS.forEach { name ->
        val target = targetDir.resolve(name)
        context.assets.open("$BUNDLED_NATIVE_LIB_ASSET_DIR/$name").use { input ->
          if (target.exists() && target.length() == input.available().toLong()) {
            return@forEach
          }
          target.outputStream().use { output -> input.copyTo(output) }
          target.setReadable(true, true)
        }
      }
    }
  }

  private suspend fun installBundledRuntime() {
    val context = appContext ?: return
    val root = appRoot ?: return
    AndroidRuntimeAssets.ensureInstalled(context, root)
  }

  companion object {
    private const val LOG_TAG = "LocalGatewayLauncher"
    private const val BUNDLED_NATIVE_LIB_ASSET_DIR = "openclaw/native-libs/arm64-v8a"
    internal val BUNDLED_NATIVE_LIBS_FOR_TEST: List<String>
      get() = BUNDLED_NATIVE_LIBS
    private val BUNDLED_NATIVE_LIBS =
      listOf(
        "libc++_shared.so",
        "libz.so.1",
        "libcares.so",
        "libsqlite3.so",
        "libcrypto.so.3",
        "libssl.so.3",
        "libicudata.so.78",
        "libicui18n.so.78",
        "libicuuc.so.78",
      )
    const val PACKAGED_NODE_LIBRARY = "libopenclaw_node.so"
    const val DEFAULT_LOCAL_GATEWAY_PORT = 18790
    const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
    const val TERMUX_HOME = "/data/data/com.termux/files/home"
  }
}

enum class LocalGatewayLaunchResult {
  Skipped,
  AlreadyRunning,
  Started,
  PortInUse,
  Failed,
}

enum class LocalGatewayStopResult {
  Stopped,
  Failed,
}

interface CommandRunner {
  suspend fun run(
    command: List<String>,
    timeoutMs: Long,
  ): CommandRunResult
}

data class CommandRunResult(
  val exitCode: Int?,
) {
  val success: Boolean
    get() = exitCode == 0
}

class ProcessCommandRunner : CommandRunner {
  override suspend fun run(
    command: List<String>,
    timeoutMs: Long,
  ): CommandRunResult =
    withContext(Dispatchers.IO) {
      val process: Process =
        try {
          ProcessBuilder(command)
            .redirectErrorStream(true)
            .directory(File("/"))
            .start()
        } catch (_: Throwable) {
          return@withContext CommandRunResult(exitCode = null)
        }
      runCatching { process.outputStream.close() }

      val exit =
        withTimeoutOrNull<Int>(timeoutMs) {
          process.waitFor()
        }
      if (exit == null) {
        process.destroy()
        withTimeoutOrNull<Int>(500) { process.waitFor() }
      }
      CommandRunResult(exitCode = exit)
    }
}
