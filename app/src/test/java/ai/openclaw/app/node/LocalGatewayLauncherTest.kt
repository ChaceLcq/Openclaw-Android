package ai.openclaw.app.node

import ai.openclaw.app.gateway.GatewayEndpoint
import ai.openclaw.app.gateway.GatewayTlsParams
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalGatewayLauncherTest {
  private val launcher = LocalGatewayLauncher(runner = FakeCommandRunner)

  @Test
  fun shouldAttemptStart_acceptsLoopbackDefaultPortWithoutTls() {
    assertTrue(
      launcher.shouldAttemptStart(
        GatewayEndpoint.manual(host = "127.0.0.1", port = LocalGatewayLauncher.DEFAULT_LOCAL_GATEWAY_PORT),
        tls = null,
      ),
    )
  }

  @Test
  fun shouldAttemptStart_rejectsNonLoopbackHosts() {
    assertFalse(
      launcher.shouldAttemptStart(
        GatewayEndpoint.manual(host = "192.168.1.20", port = LocalGatewayLauncher.DEFAULT_LOCAL_GATEWAY_PORT),
        tls = null,
      ),
    )
  }

  @Test
  fun shouldAttemptStart_rejectsTlsEndpoints() {
    assertFalse(
      launcher.shouldAttemptStart(
        GatewayEndpoint.manual(host = "127.0.0.1", port = LocalGatewayLauncher.DEFAULT_LOCAL_GATEWAY_PORT),
        tls =
          GatewayTlsParams(
            required = true,
            expectedFingerprint = "fingerprint",
            allowTOFU = false,
            stableId = "manual|127.0.0.1|${LocalGatewayLauncher.DEFAULT_LOCAL_GATEWAY_PORT}",
          ),
      ),
    )
  }

  @Test
  fun shouldAttemptStart_rejectsNonDefaultPorts() {
    assertFalse(
      launcher.shouldAttemptStart(
        GatewayEndpoint.manual(host = "127.0.0.1", port = 18789),
        tls = null,
      ),
    )
  }

  @Test
  fun buildLocalGatewayStartScript_prefersPackagedNodeWhenAvailable() {
    val script =
      LocalGatewayLauncher(
        runner = FakeCommandRunner,
        nativeLibraryDir = "/data/app/lib/arm64",
      ).buildLocalGatewayStartScript()

    assertTrue(script.contains("\"/data/app/lib/arm64/libopenclaw_node.so\""))
    assertTrue(script.contains("\"/data/app/lib/arm64/arm64/libopenclaw_node.so\""))
    assertTrue(script.contains("PACKAGED_NODE=\"${'$'}candidate\""))
    assertTrue(script.contains("export OPENCLAW_NODE=\"${'$'}PACKAGED_NODE\""))
    assertTrue(script.contains("LAUNCH_MODE=\"packaged-node\""))
    assertTrue(script.contains("nohup \"${'$'}PACKAGED_NODE\" \"${'$'}ENTRY\" gateway run"))
  }

  @Test
  fun buildLocalGatewayStartScript_redirectsLegacyLogDirToAppPrivateTmp() {
    val script =
      LocalGatewayLauncher(
        runner = FakeCommandRunner,
        nativeLibraryDir = "/data/app/lib/arm64",
      ).buildLocalGatewayStartScript()

    assertTrue(script.contains("export TMP=\"${'$'}TMPDIR\""))
    assertTrue(script.contains("export TEMP=\"${'$'}TMPDIR\""))
    assertTrue(script.contains("\"${'$'}PREFIX/tmp/clawdbot\""))
    assertTrue(script.contains("OPENCLAW_ANDROID_LOG_DIR=\"${'$'}PREFIX/tmp/clawdbot\""))
    assertTrue(script.contains("DEFAULT_LOG_DIR = process.env.OPENCLAW_ANDROID_LOG_DIR"))
  }

  @Test
  fun bundledNativeLibraries_includeCxxRuntimeBeforeNodeDependencies() {
    val script =
      LocalGatewayLauncher(
        runner = FakeCommandRunner,
        nativeLibraryDir = "/data/app/lib/arm64",
      ).buildLocalGatewayStartScript()

    assertTrue(script.contains("LD_LIBRARY_PATH=\"${'$'}BUNDLED_NATIVE_LIB_DIR:${'$'}NATIVE_LIBRARY_DIR:${'$'}PREFIX/lib\""))
    assertTrue(LocalGatewayLauncher.BUNDLED_NATIVE_LIBS_FOR_TEST.first() == "libc++_shared.so")
  }

  private object FakeCommandRunner : CommandRunner {
    override suspend fun run(
      command: List<String>,
      timeoutMs: Long,
    ): CommandRunResult = CommandRunResult(exitCode = 0)
  }
}
