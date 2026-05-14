package ai.openclaw.app.voice

internal object MeloTtsTextPreprocessor {
  private const val MIN_CHUNK_CHARS = 28
  private const val MAX_CHUNK_CHARS = 48

  fun sanitize(text: String): String =
    normalizeStructure(text)
      .filter { char ->
        val type = Character.getType(char)
        type != Character.SURROGATE.toInt() &&
          type != Character.OTHER_SYMBOL.toInt() &&
          type != Character.NON_SPACING_MARK.toInt()
      }.replace(Regex("\\s+"), " ")
      .replace(Regex("\\s*([，。！？；、：])\\s*"), "$1")
      .replace(Regex("([。！？]){2,}"), "$1")
      .trim()
      .trimStart('，', '。', '！', '？', '、')

  fun split(text: String): List<String> {
    val normalized = text.replace(Regex("\\s+"), " ").trim()
    if (normalized.isEmpty()) return emptyList()
    val chunks = mutableListOf<String>()
    val current = StringBuilder()

    fun flush() {
      val chunk = current.toString().trim()
      if (chunk.isNotEmpty()) chunks.add(chunk)
      current.clear()
    }

    fun splitAt(indexInclusive: Int) {
      val head = current.substring(0, indexInclusive + 1).trim()
      val tail = current.substring(indexInclusive + 1).trimStart()
      if (head.isNotEmpty()) chunks.add(head)
      current.clear()
      current.append(tail)
    }

    for (char in normalized) {
      current.append(char)
      when {
        isStrongBreak(char) -> flush()
        current.length >= MIN_CHUNK_CHARS && isSoftBreak(char) -> flush()
        current.length >= MAX_CHUNK_CHARS -> {
          val softBreak = current.indexOfLast { isSoftBreak(it) || isStrongBreak(it) }
          if (softBreak >= MIN_CHUNK_CHARS) splitAt(softBreak) else flush()
        }
      }
    }
    flush()
    return chunks
  }

  fun nextStreamingBoundary(
    text: String,
    start: Int,
    force: Boolean,
  ): Int? {
    if (start >= text.length) return null
    for (index in start until text.length) {
      val chars = index + 1 - start
      val char = text[index]
      if (chars >= MIN_CHUNK_CHARS && isSoftBreak(char)) return index + 1
      if (isStrongBreak(char)) return index + 1
      if (!force && chars >= MAX_CHUNK_CHARS) return index + 1
    }
    return if (force && text.length > start) text.length else null
  }

  private fun normalizeStructure(text: String): String =
    text
      .replace("\r\n", "\n")
      .replace('\r', '\n')
      .replace(Regex("\\n\\s*[-•*+]\\s*"), "。")
      .replace(Regex("(?m)^\\s*[-•*+]\\s*"), "")
      .replace(Regex("\\n+"), "。")
      .replace(Regex("[*_`#>\\[\\]{}()]+"), " ")
      .replace(Regex("[—–]+"), "，")

  private fun isStrongBreak(char: Char): Boolean =
    char == '\u3002' ||
      char == '\uff01' ||
      char == '\uff1f' ||
      char == '!' ||
      char == '?' ||
      char == '.' ||
      char == '\n'

  private fun isSoftBreak(char: Char): Boolean =
    char == '\uff1b' ||
      char == ';' ||
      char == '\uff0c' ||
      char == ',' ||
      char == '\u3001' ||
      char == '\uff1a' ||
      char == ':'
}
