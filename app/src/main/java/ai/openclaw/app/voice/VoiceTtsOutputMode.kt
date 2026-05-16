package ai.openclaw.app.voice

enum class VoiceTtsOutputMode(
  val rawValue: String,
  val label: String,
) {
  BuiltInSpeaker(rawValue = "speaker", label = "Speaker"),
  Q5Usb(rawValue = "q5_usb", label = "Q5+ USB"),
  SystemDefault(rawValue = "system", label = "System"),
  ;

  companion object {
    fun fromId(value: String?): VoiceTtsOutputMode =
      entries.firstOrNull { it.rawValue == value } ?: BuiltInSpeaker
  }
}
