package ai.openclaw.app.voice

import com.agenew.translate.metting.existingAsrModelFiles
import com.agenew.translate.tts.existingTtsModelDir
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceModelInstallerTest {
  @Test
  fun importsAsrAndTtsFromZipWithProductRootFolder() {
    val externalFilesDir = createTempDirectory(prefix = "voice-model-external").toFile()
    val appFilesDir = createTempDirectory(prefix = "voice-model-app").toFile()
    val zip = File(createTempDirectory(prefix = "voice-model-zip").toFile(), "voice-models.zip")
    try {
      writeZip(
        zip,
        mapOf(
          "OpenClawVoiceModels/openclaw-voice-model.json" to supportedManifest(),
          "OpenClawVoiceModels/models/asr/sense/sense_weight_quant.mnn" to "asr-model",
          "OpenClawVoiceModels/models/asr/sense/sense_tokens.txt" to "tokens",
          "OpenClawVoiceModels/models/asr/vad/silero_vad_int8.mnn" to "vad",
          "OpenClawVoiceModels/models/tts/bert-vits2-MNN/config.json" to "{}",
          "OpenClawVoiceModels/models/tts/bert-vits2-MNN/acoustic/model.mnn" to "tts-model",
        ),
      )

      val result =
        zip.inputStream().use { input ->
          installVoiceModelPackageFromZip(
            input = input,
            externalFilesDir = externalFilesDir,
            onProgress = {},
          )
        }

      assertTrue(result.asrImported)
      assertTrue(result.ttsImported)
      assertNotNull(existingAsrModelFiles(appFilesDir = appFilesDir, externalFilesDir = externalFilesDir))
      assertEquals(
        File(externalFilesDir, "models/tts/bert-vits2-MNN"),
        existingTtsModelDir(
          appFilesDir = appFilesDir,
          externalFilesDir = externalFilesDir,
          candidates = listOf("bert-vits2-MNN"),
        ),
      )
    } finally {
      externalFilesDir.deleteRecursively()
      appFilesDir.deleteRecursively()
      zip.parentFile?.deleteRecursively()
    }
  }

  @Test
  fun ignoresZipTraversalEntries() {
    val externalFilesDir = createTempDirectory(prefix = "voice-model-external").toFile()
    val zip = File(createTempDirectory(prefix = "voice-model-zip").toFile(), "voice-models.zip")
    try {
      writeZip(
        zip,
        mapOf(
          "openclaw-voice-model.json" to supportedManifest(),
          "../models/asr/sense/sense_weight_quant.mnn" to "bad",
          "models/asr/sense/sense_weight_quant.mnn" to "asr-model",
          "models/asr/sense/sense_tokens.txt" to "tokens",
          "models/asr/vad/silero_vad_int8.mnn" to "vad",
        ),
      )

      val result =
        zip.inputStream().use { input ->
          installVoiceModelPackageFromZip(
            input = input,
            externalFilesDir = externalFilesDir,
            onProgress = {},
          )
        }

      assertTrue(result.asrImported)
      assertFalse(File(externalFilesDir.parentFile, "models/asr/sense/sense_weight_quant.mnn").exists())
    } finally {
      externalFilesDir.deleteRecursively()
      zip.parentFile?.deleteRecursively()
    }
  }

  @Test
  fun rejectsPackagesWithoutOpenClawManifest() {
    val externalFilesDir = createTempDirectory(prefix = "voice-model-external").toFile()
    val zip = File(createTempDirectory(prefix = "voice-model-zip").toFile(), "voice-models.zip")
    try {
      writeZip(
        zip,
        mapOf(
          "models/asr/sense/sense_weight_quant.mnn" to "asr-model",
          "models/asr/sense/sense_tokens.txt" to "tokens",
          "models/asr/vad/silero_vad_int8.mnn" to "vad",
        ),
      )

      assertThrows(IllegalStateException::class.java) {
        zip.inputStream().use { input ->
          installVoiceModelPackageFromZip(
            input = input,
            externalFilesDir = externalFilesDir,
            onProgress = {},
          )
        }
      }
    } finally {
      externalFilesDir.deleteRecursively()
      zip.parentFile?.deleteRecursively()
    }
  }

  @Test
  fun rejectsUnsupportedManifestModels() {
    val externalFilesDir = createTempDirectory(prefix = "voice-model-external").toFile()
    val zip = File(createTempDirectory(prefix = "voice-model-zip").toFile(), "voice-models.zip")
    try {
      writeZip(
        zip,
        mapOf(
          "openclaw-voice-model.json" to """{"packageType":"openclaw-voice-model","version":1,"asr":{"engine":"unknown-asr"}}""",
          "models/asr/sense/sense_weight_quant.mnn" to "asr-model",
          "models/asr/sense/sense_tokens.txt" to "tokens",
          "models/asr/vad/silero_vad_int8.mnn" to "vad",
        ),
      )

      assertThrows(IllegalStateException::class.java) {
        zip.inputStream().use { input ->
          installVoiceModelPackageFromZip(
            input = input,
            externalFilesDir = externalFilesDir,
            onProgress = {},
          )
        }
      }
    } finally {
      externalFilesDir.deleteRecursively()
      zip.parentFile?.deleteRecursively()
    }
  }

  private fun writeZip(
    target: File,
    entries: Map<String, String>,
  ) {
    target.parentFile?.mkdirs()
    ZipOutputStream(FileOutputStream(target)).use { zip ->
      for ((name, content) in entries) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray())
        zip.closeEntry()
      }
    }
  }

  private fun supportedManifest(): String =
    """{"packageType":"openclaw-voice-model","version":1,"asr":{"engine":"sensevoice-mnn"},"tts":{"engine":"bert-vits2-mnn"}}"""
}
