package ai.openclaw.app.voice

import org.junit.Assert.assertEquals
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
}
