package ai.openclaw.app.node

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

object AndroidRuntimeAssets {
  private const val RUNTIME_ASSET = "openclaw/runtime/openclaw-runtime.zip"
  private const val RUNTIME_MARKER = "android-runtime=3"
  private const val VERSION_FILE = "usr/.openclaw-android-runtime.version"
  private const val ENTRY_FILE = "usr/lib/node_modules/openclaw-cn/dist/entry.js"
  private const val OPENCLAW_BIN = "usr/bin/openclaw-cn"

  suspend fun ensureInstalled(
    context: Context,
    appRoot: File,
  ) {
    withContext(Dispatchers.IO) {
      val entry = appRoot.resolve(ENTRY_FILE)
      val bin = appRoot.resolve(OPENCLAW_BIN)
      val version = appRoot.resolve(VERSION_FILE)
      if (entry.isFile && bin.isFile && version.isFile && version.readText().contains(RUNTIME_MARKER)) {
        bin.setExecutable(true, true)
        return@withContext
      }

      val usr = appRoot.resolve("usr")
      if (usr.exists()) {
        usr.deleteRecursively()
      }
      appRoot.mkdirs()

      context.assets.open(RUNTIME_ASSET).use { raw ->
        ZipInputStream(raw.buffered()).use { zip ->
          while (true) {
            val item = zip.nextEntry ?: break
            val target = appRoot.resolve(item.name)
            val canonicalRoot = appRoot.canonicalFile
            val canonicalTarget = target.canonicalFile
            if (!canonicalTarget.path.startsWith(canonicalRoot.path + File.separator)) {
              error("Blocked unsafe runtime zip entry: ${item.name}")
            }
            if (item.isDirectory) {
              canonicalTarget.mkdirs()
            } else {
              canonicalTarget.parentFile?.mkdirs()
              canonicalTarget.outputStream().use { output -> zip.copyTo(output) }
            }
            zip.closeEntry()
          }
        }
      }

      if (!entry.isFile || !bin.isFile) {
        error("Bundled OpenClaw Android runtime is incomplete")
      }
      bin.setReadable(true, true)
      bin.setExecutable(true, true)
    }
  }
}
