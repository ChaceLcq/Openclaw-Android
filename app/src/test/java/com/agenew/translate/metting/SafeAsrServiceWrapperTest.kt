package com.agenew.translate.metting

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SafeAsrServiceWrapperTest {
  @Test
  fun selectsExistingPreferredModelBeforeCheckingAssets() {
    val dir = createTempDirectory(prefix = "asr-model").toFile()
    try {
      val preferred = File(dir, "sense_weight_quant.mnn").apply { writeText("model") }
      val fallback = File(dir, "sense.mnn")

      assertEquals(preferred, existingPreferredAssetFile(preferred, fallback))
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun selectsExistingFallbackModelWhenPreferredIsMissing() {
    val dir = createTempDirectory(prefix = "asr-model").toFile()
    try {
      val preferred = File(dir, "sense_weight_quant.mnn")
      val fallback = File(dir, "sense.mnn").apply { writeText("model") }

      assertEquals(fallback, existingPreferredAssetFile(preferred, fallback))
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun returnsNullWhenNoLocalModelExists() {
    val dir = createTempDirectory(prefix = "asr-model").toFile()
    try {
      assertNull(existingPreferredAssetFile(File(dir, "sense_weight_quant.mnn"), File(dir, "sense.mnn")))
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun findsExternalAsrModelFilesBeforeAssets() {
    val externalFilesDir = createTempDirectory(prefix = "external-files").toFile()
    val appFilesDir = createTempDirectory(prefix = "app-files").toFile()
    try {
      val asrRoot = File(externalFilesDir, "models/asr")
      val model = File(asrRoot, "sense/sense_weight_quant.mnn").also { it.parentFile?.mkdirs(); it.writeText("model") }
      val tokens = File(asrRoot, "sense/sense_tokens.txt").also { it.writeText("tokens") }
      val vad = File(asrRoot, "vad/silero_vad_int8.mnn").also { it.parentFile?.mkdirs(); it.writeText("vad") }

      val files = existingAsrModelFiles(appFilesDir = appFilesDir, externalFilesDir = externalFilesDir)

      assertEquals(model, files?.modelFile)
      assertEquals(tokens, files?.tokensFile)
      assertEquals(vad, files?.vadFile)
    } finally {
      externalFilesDir.deleteRecursively()
      appFilesDir.deleteRecursively()
    }
  }
}
