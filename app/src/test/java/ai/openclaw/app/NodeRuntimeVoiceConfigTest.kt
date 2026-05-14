package ai.openclaw.app

import org.junit.Assert.assertTrue
import org.junit.Test

class NodeRuntimeVoiceConfigTest {
  @Test
  fun voicePageTtsIsEnabledByDefault() {
    assertTrue(VOICE_PAGE_TTS_ENABLED)
  }
}
