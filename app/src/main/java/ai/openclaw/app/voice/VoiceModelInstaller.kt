package ai.openclaw.app.voice

import android.content.Context
import android.net.Uri
import com.agenew.translate.metting.existingAsrModelFiles
import com.agenew.translate.tts.TtsBackendType
import com.agenew.translate.tts.existingTtsModelDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

private const val MAX_MANIFEST_BYTES = 64 * 1024
private val SUPPORTED_ASR_ENGINES = setOf("sensevoice-mnn", "sense-mnn")
private val SUPPORTED_TTS_ENGINES = setOf("bert-vits2-mnn", "bert-vits2-mnn-zh")

data class VoiceModelInstallState(
  val asrInstalled: Boolean = false,
  val ttsInstalled: Boolean = false,
  val isInstalling: Boolean = false,
  val statusText: String = "Voice models not installed",
  val errorText: String? = null,
)

data class VoiceModelInstallProgress(
  val filesImported: Int,
  val bytesImported: Long,
  val currentFile: String,
)

data class VoiceModelInstallResult(
  val asrImported: Boolean,
  val ttsImported: Boolean,
  val filesImported: Int,
  val bytesImported: Long,
) {
  val anyImported: Boolean = asrImported || ttsImported
}

class VoiceModelInstaller(
  private val context: Context,
) {
  fun currentState(): VoiceModelInstallState {
    val appFilesDir = context.filesDir
    val externalFilesDir = context.getExternalFilesDir(null)
    val asrInstalled = existingAsrModelFiles(appFilesDir = appFilesDir, externalFilesDir = externalFilesDir) != null
    val ttsInstalled =
      existingTtsModelDir(
        appFilesDir = appFilesDir,
        externalFilesDir = externalFilesDir,
        candidates = TtsBackendType.BERT_VITS2.assetFolderCandidates,
      ) != null
    return VoiceModelInstallState(
      asrInstalled = asrInstalled,
      ttsInstalled = ttsInstalled,
      statusText = voiceModelInstallStatusText(asrInstalled = asrInstalled, ttsInstalled = ttsInstalled),
    )
  }

  suspend fun installFromUri(
    uri: Uri,
    onProgress: (VoiceModelInstallProgress) -> Unit,
  ): VoiceModelInstallResult =
    withContext(Dispatchers.IO) {
      val externalFilesDir = requireNotNull(context.getExternalFilesDir(null)) { "External files dir unavailable" }
      val input =
        context.contentResolver.openInputStream(uri)
          ?: error("Cannot open selected model package")
      input.use {
        installVoiceModelPackageFromZip(
          input = it,
          externalFilesDir = externalFilesDir,
          onProgress = onProgress,
        )
      }
    }
}

internal fun voiceModelInstallStatusText(
  asrInstalled: Boolean,
  ttsInstalled: Boolean,
): String =
  when {
    asrInstalled && ttsInstalled -> "Voice models ready"
    asrInstalled -> "ASR ready · TTS model missing"
    ttsInstalled -> "TTS ready · ASR model missing"
    else -> "Voice models not installed"
  }

internal fun installVoiceModelPackageFromZip(
  input: InputStream,
  externalFilesDir: File,
  onProgress: (VoiceModelInstallProgress) -> Unit,
): VoiceModelInstallResult {
  val stagingExternalDir = File(externalFilesDir, "model-import-staging-${System.nanoTime()}")
  val stagingModelsRoot = File(stagingExternalDir, "models")
  var filesImported = 0
  var bytesImported = 0L
  var manifestAccepted = false

  try {
    ZipInputStream(BufferedInputStream(input)).use { zip ->
      while (true) {
        val entry = zip.nextEntry ?: break
        val entryName = normalizedZipEntryName(entry.name)
        if (!entry.isDirectory && entryName != null) {
          if (entryName.endsWith("openclaw-voice-model.json")) {
            manifestAccepted = supportedVoiceModelManifest(readZipEntryText(zip))
          } else {
            val destination = voiceModelDestinationForEntry(entryName, stagingModelsRoot)
            if (destination != null) {
              val copied = copyZipEntryToFile(zip, destination)
              filesImported += 1
              bytesImported += copied
              onProgress(
                VoiceModelInstallProgress(
                  filesImported = filesImported,
                  bytesImported = bytesImported,
                  currentFile = entryName,
                ),
              )
            }
          }
        }
        zip.closeEntry()
      }
    }

    if (!manifestAccepted) {
      error("Unsupported voice model package")
    }

    val asrImported = existingAsrModelFiles(appFilesDir = File(stagingExternalDir, "__unused"), externalFilesDir = stagingExternalDir) != null
    val ttsImported =
      existingTtsModelDir(
        appFilesDir = File(stagingExternalDir, "__unused"),
        externalFilesDir = stagingExternalDir,
        candidates = TtsBackendType.BERT_VITS2.assetFolderCandidates,
      ) != null

    if (!asrImported && !ttsImported) {
      error("No supported ASR/TTS model files found in selected package")
    }

    val finalModelsRoot = File(externalFilesDir, "models")
    if (asrImported) {
      File(stagingModelsRoot, "asr").copyRecursively(File(finalModelsRoot, "asr"), overwrite = true)
    }
    if (ttsImported) {
      File(stagingModelsRoot, "tts").copyRecursively(File(finalModelsRoot, "tts"), overwrite = true)
    }

    return VoiceModelInstallResult(
      asrImported = asrImported,
      ttsImported = ttsImported,
      filesImported = filesImported,
      bytesImported = bytesImported,
    )
  } finally {
    stagingExternalDir.deleteRecursively()
  }
}

private fun readZipEntryText(zip: ZipInputStream): String {
  val bytes = ArrayList<Byte>()
  val buffer = ByteArray(4096)
  var total = 0
  while (true) {
    val read = zip.read(buffer)
    if (read < 0) break
    total += read
    if (total > MAX_MANIFEST_BYTES) {
      error("Voice model manifest is too large")
    }
    for (index in 0 until read) {
      bytes += buffer[index]
    }
  }
  return bytes.toByteArray().toString(Charsets.UTF_8)
}

private fun supportedVoiceModelManifest(text: String): Boolean =
  runCatching {
    val packageType = jsonStringValue(text, "packageType")
    if (packageType != "openclaw-voice-model") return@runCatching false
    val version = jsonIntValue(text, "version") ?: 0
    if (version < 1) return@runCatching false
    val asrEngine = jsonNestedStringValue(text, "asr", "engine").orEmpty().lowercase()
    val ttsEngine = jsonNestedStringValue(text, "tts", "engine").orEmpty().lowercase()
    val asrSupported = asrEngine in SUPPORTED_ASR_ENGINES
    val ttsSupported = ttsEngine in SUPPORTED_TTS_ENGINES
    asrSupported || ttsSupported
  }.getOrDefault(false)

private fun jsonStringValue(
  text: String,
  key: String,
): String? =
  Regex(""""${Regex.escape(key)}"\s*:\s*"([^"]+)"""")
    .find(text)
    ?.groupValues
    ?.getOrNull(1)

private fun jsonIntValue(
  text: String,
  key: String,
): Int? =
  Regex(""""${Regex.escape(key)}"\s*:\s*(\d+)""")
    .find(text)
    ?.groupValues
    ?.getOrNull(1)
    ?.toIntOrNull()

private fun jsonNestedStringValue(
  text: String,
  objectKey: String,
  valueKey: String,
): String? =
  Regex(""""${Regex.escape(objectKey)}"\s*:\s*\{[^}]*"${Regex.escape(valueKey)}"\s*:\s*"([^"]+)"""")
    .find(text)
    ?.groupValues
    ?.getOrNull(1)

private fun normalizedZipEntryName(rawName: String): String? {
  val parts =
    rawName
      .replace('\\', '/')
      .split('/')
      .map { it.trim() }
      .filter { it.isNotEmpty() && it != "." }
  if (parts.isEmpty() || parts.any { it == ".." }) return null
  return parts.joinToString("/")
}

private fun voiceModelDestinationForEntry(
  entryName: String,
  modelsRoot: File,
): File? {
  val normalized = entryName.replace('\\', '/')
  val asrRoot = File(modelsRoot, "asr")
  return when {
    normalized.endsWith("/sense/sense_weight_quant.mnn") -> File(asrRoot, "sense/sense_weight_quant.mnn")
    normalized.endsWith("/sense/sense.mnn") -> File(asrRoot, "sense/sense.mnn")
    normalized.endsWith("/sense/sense_tokens.txt") -> File(asrRoot, "sense/sense_tokens.txt")
    normalized.endsWith("/vad/silero_vad_int8.mnn") -> File(asrRoot, "vad/silero_vad_int8.mnn")
    else -> ttsDestinationForEntry(normalized, modelsRoot)
  }
}

private fun ttsDestinationForEntry(
  entryName: String,
  modelsRoot: File,
): File? {
  val parts = entryName.split('/').filter { it.isNotBlank() }
  for (candidate in TtsBackendType.BERT_VITS2.assetFolderCandidates) {
    val index = parts.indexOf(candidate)
    if (index < 0 || index >= parts.lastIndex) continue
    val relativeParts = parts.drop(index + 1)
    if (relativeParts.any { it == "." || it == ".." || it.isBlank() }) continue
    return File(File(modelsRoot, "tts/$candidate"), relativeParts.joinToString(File.separator))
  }
  return null
}

private fun copyZipEntryToFile(
  zip: ZipInputStream,
  destination: File,
): Long {
  destination.parentFile?.mkdirs()
  val tmp = File(destination.parentFile, "${destination.name}.tmp")
  var copied = 0L
  tmp.outputStream().use { output ->
    val buffer = ByteArray(64 * 1024)
    while (true) {
      val read = zip.read(buffer)
      if (read < 0) break
      output.write(buffer, 0, read)
      copied += read
    }
  }
  if (destination.exists() && !destination.delete()) {
    tmp.delete()
    error("Cannot replace ${destination.name}")
  }
  if (!tmp.renameTo(destination)) {
    tmp.delete()
    error("Cannot install ${destination.name}")
  }
  return copied
}
