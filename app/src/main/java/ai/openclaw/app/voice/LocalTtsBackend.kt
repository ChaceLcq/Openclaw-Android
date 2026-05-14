package ai.openclaw.app.voice

internal interface LocalTtsBackend {
  val provider: String

  suspend fun preload(): Boolean

  fun isReady(): Boolean

  suspend fun synthesize(
    text: String,
    shouldContinue: () -> Boolean = { true },
  ): TalkSpeakAudio?

  fun reason(): String?

  fun release()
}

internal fun ShortArray.toLittleEndianBytes(): ByteArray {
  val out = ByteArray(size * 2)
  for (index in indices) {
    val value = this[index].toInt()
    out[index * 2] = (value and 0xFF).toByte()
    out[index * 2 + 1] = ((value ushr 8) and 0xFF).toByte()
  }
  return out
}

internal fun FloatArray.toPcm16LittleEndianBytes(): ByteArray {
  val out = ByteArray(size * 2)
  for (index in indices) {
    val scaled = (this[index].coerceIn(-1.0f, 1.0f) * Short.MAX_VALUE).toInt()
    out[index * 2] = (scaled and 0xFF).toByte()
    out[index * 2 + 1] = ((scaled ushr 8) and 0xFF).toByte()
  }
  return out
}
