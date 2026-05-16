package ai.openclaw.app.ui

import ai.openclaw.app.voice.VoiceModelInstallState
import ai.openclaw.app.voice.VoiceTtsOutputMode
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceTabScreenTest {
  @Test
  fun emptyStateExplainsMouthAsrUnavailableWithoutSystemFallback() {
    val status = "Mouth ASR unavailable"

    assertEquals("Mouth ASR unavailable", voiceEmptyStateTitle(status))
    assertEquals(
      "Local MNN ASR is unavailable. Camera preview can still run.",
      voiceEmptyStateSubtitle(status),
    )
  }

  @Test
  fun emptyStateExplainsReadyMouthAsr() {
    assertEquals("Mouth ASR ready", voiceEmptyStateTitle("Ready to speak"))
    assertEquals(
      "Speak to the camera to send a voice turn.",
      voiceEmptyStateSubtitle("Ready to speak"),
    )
  }

  @Test
  fun statusTextUsesReadableSeparator() {
    assertEquals(
      "Connected · Mouth ASR unavailable",
      voiceCombinedStatus("Connected", "Mouth ASR unavailable"),
    )
  }

  @Test
  fun modelImportButtonLabelsMissingAndReadyStates() {
    assertEquals(
      "Import voice model package",
      voiceModelInstallActionLabel(VoiceModelInstallState(asrInstalled = false, ttsInstalled = false)),
    )
    assertEquals(
      "Replace voice model package",
      voiceModelInstallActionLabel(VoiceModelInstallState(asrInstalled = true, ttsInstalled = true)),
    )
  }

  @Test
  fun ttsOutputLabelIncludesSelectedRoute() {
    assertEquals("Output: Q5+ USB", voiceTtsOutputLabel(VoiceTtsOutputMode.Q5Usb))
  }
}
