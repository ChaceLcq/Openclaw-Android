package com.agenew.translate.tts

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TtsServiceModelPathTest {
  @Test
  fun findsExternalTtsModelBeforeAssets() {
    val externalFilesDir = createTempDirectory(prefix = "external-files").toFile()
    val appFilesDir = createTempDirectory(prefix = "app-files").toFile()
    try {
      val modelDir = File(externalFilesDir, "models/tts/bert-vits2-MNN")
      File(modelDir, "config.json").also { it.parentFile?.mkdirs(); it.writeText("{}") }

      assertEquals(
        modelDir,
        existingTtsModelDir(
          appFilesDir = appFilesDir,
          externalFilesDir = externalFilesDir,
          candidates = listOf("bert-vits2-MNN"),
        ),
      )
    } finally {
      externalFilesDir.deleteRecursively()
      appFilesDir.deleteRecursively()
    }
  }

  @Test
  fun returnsNullWhenTtsConfigIsMissing() {
    val externalFilesDir = createTempDirectory(prefix = "external-files").toFile()
    val appFilesDir = createTempDirectory(prefix = "app-files").toFile()
    try {
      File(externalFilesDir, "models/tts/bert-vits2-MNN").mkdirs()

      assertNull(
        existingTtsModelDir(
          appFilesDir = appFilesDir,
          externalFilesDir = externalFilesDir,
          candidates = listOf("bert-vits2-MNN"),
        ),
      )
    } finally {
      externalFilesDir.deleteRecursively()
      appFilesDir.deleteRecursively()
    }
  }
}
