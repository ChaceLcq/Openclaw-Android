package ai.openclaw.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalVoiceCaptureExternalGateTest {
  @Test
  fun externalGateStartIncludesLookbackWhenBufferContainsIt() {
    val start =
      externalGateSpeechStartOffset(
        bufferStartOffset = 0L,
        currentOffset = 16_000L,
        lookbackSamples = 12_800L,
      )

    assertEquals(3_200L, start)
  }

  @Test
  fun externalGateStartClampsToAvailablePreRoll() {
    val start =
      externalGateSpeechStartOffset(
        bufferStartOffset = 10_000L,
        currentOffset = 16_000L,
        lookbackSamples = 12_800L,
      )

    assertEquals(10_000L, start)
  }

  @Test
  fun externalGateModeDisablesPartialRecognitionByDefault() {
    assertFalse(
      shouldSchedulePartialRecognition(
        externalGateMode = true,
        externalGatePartialsEnabled = false,
      ),
    )
  }

  @Test
  fun vadModeKeepsPartialRecognitionEnabled() {
    assertTrue(
      shouldSchedulePartialRecognition(
        externalGateMode = false,
        externalGatePartialsEnabled = false,
      ),
    )
  }

  @Test
  fun shortOneCharacterFinalIsFilteredAsFalsePositive() {
    assertFalse(isUsefulLocalAsrFinal(text = "我", sampleCount = 1_000))
  }

  @Test
  fun normalFinalTextIsAccepted() {
    assertTrue(isUsefulLocalAsrFinal(text = "你好", sampleCount = 16_000))
  }
}
