package ai.openclaw.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeRuntimeVoiceFallbackTest {
  @Test
  fun mouthAsrFailureUsesShortUnavailableStatus() {
    assertEquals(
      "Mouth ASR unavailable",
      mouthAsrUnavailableReadyText("dlopen failed: library \"libsherpa-mnn-jni.so\" not found"),
    )
    assertEquals(
      "Mouth ASR unavailable",
      mouthAsrUnavailableReadyText(null),
    )
  }

  @Test
  fun mouthAsrFallbackKeepsCameraPreviewRunning() {
    assertTrue(shouldKeepMouthCameraRunningAfterMouthAsrFallback())
  }

  @Test
  fun ttsStatusTextIsShortAndActionable() {
    assertEquals("TTS ready", voiceTtsReadyStatusText(ready = true, reason = null))
    assertEquals("TTS unavailable: missing model", voiceTtsReadyStatusText(ready = false, reason = "missing model"))
    assertEquals(
      "TTS unavailable: libMNN.so missing",
      voiceTtsReadyStatusText(ready = false, reason = "dlopen failed: library \"libMNN.so\" not found"),
    )
    assertEquals(
      "TTS unavailable: libmtk_llm_jni.so missing",
      voiceTtsReadyStatusText(ready = false, reason = "dlopen failed: library \"libmtk_llm_jni.so\" not found"),
    )
    assertEquals("TTS unavailable: unknown", voiceTtsReadyStatusText(ready = false, reason = null))
  }
}
