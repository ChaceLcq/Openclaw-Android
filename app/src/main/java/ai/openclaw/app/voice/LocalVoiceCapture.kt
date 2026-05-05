package ai.openclaw.app.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max
import kotlin.math.sqrt

class LocalVoiceCapture(
  private val context: Context,
  private val scope: CoroutineScope,
  private val inputManager: VoiceAudioInputManager,
  private val asrEngine: LocalMnnAsrEngine,
  private val onStatus: (String) -> Unit,
  private val onListening: (Boolean) -> Unit,
  private val onInputLevel: (Float) -> Unit,
  private val onPartial: (String) -> Unit,
  private val onFinal: (String) -> Unit,
  private val onUnavailable: (String) -> Unit,
) {
  private var job: Job? = null
  private var recorder: AudioRecord? = null
  private var acousticEchoCanceler: AcousticEchoCanceler? = null
  private var noiseSuppressor: NoiseSuppressor? = null
  private var automaticGainControl: AutomaticGainControl? = null
  @Volatile private var stopRequested = false
  @Volatile private var stopShouldFinalize = true

  fun start(): Boolean {
    job?.let { active ->
      if (active.isActive) {
        return false
      }
    }
    if (
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
      PackageManager.PERMISSION_GRANTED
    ) {
      onUnavailable("Microphone permission required")
      return false
    }
    job =
      scope.launch(Dispatchers.IO) {
        stopRequested = false
        stopShouldFinalize = true
        if (!asrEngine.isReady()) {
          onStatus("ASR warming")
        }
        val ready = asrEngine.ensureReady()
        if (!ready) {
          onUnavailable(asrEngine.reason() ?: "MNN ASR unavailable")
          return@launch
        }
        val activeRecorder =
          try {
            createRecorder()
          } catch (err: Throwable) {
            onUnavailable("AudioRecord failed: ${err.message ?: err::class.simpleName}")
            return@launch
          }
        recorder = activeRecorder
        enableAudioEffects(activeRecorder)
        val device = inputManager.resolveAudioDevice()
        if (device != null) {
          runCatching { activeRecorder.setPreferredDevice(device) }
        }
        inputManager.updateActiveDevice(device)
        onStatus(inputManager.activeInputLabel.value)
        onListening(true)
        activeRecorder.startRecording()
        asrEngine.resetVad()
        readLoop(activeRecorder)
      }
    return true
  }

  fun stop(finalizePending: Boolean = true) {
    stopShouldFinalize = finalizePending
    stopRequested = true
    if (!finalizePending) {
      job?.cancel()
      runCatching { recorder?.stop() }
    }
    onListening(false)
    onInputLevel(0f)
  }

  suspend fun stopAndJoin(
    finalizePending: Boolean = true,
    timeoutMs: Long = 1_500L,
  ) {
    val active = job
    stop(finalizePending = finalizePending)
    if (active != null) {
      withTimeoutOrNull(timeoutMs) {
        active.join()
      }
    }
  }

  private fun createRecorder(): AudioRecord {
    val errors = ArrayList<String>()
    listOf(
      MediaRecorder.AudioSource.VOICE_COMMUNICATION,
      MediaRecorder.AudioSource.MIC,
    ).forEach { source ->
      try {
        return tryCreateAudioRecord(source)
      } catch (err: Throwable) {
        errors.add("${audioSourceName(source)}: ${err.message ?: err::class.simpleName}")
      }
    }
    throw IllegalStateException("AudioRecord not initialized (${errors.joinToString("; ")})")
  }

  private fun tryCreateAudioRecord(source: Int): AudioRecord {
    val minBuffer =
      AudioRecord.getMinBufferSize(
        SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
      )
    val bufferSizeBytes = minBuffer.coerceAtLeast(CHUNK_SAMPLES * 2 * 4)
    return AudioRecord
      .Builder()
      .setAudioSource(source)
      .setAudioFormat(
        AudioFormat
          .Builder()
          .setSampleRate(SAMPLE_RATE)
          .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
          .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
          .build(),
      ).setBufferSizeInBytes(bufferSizeBytes)
      .build()
      .also {
        if (it.state != AudioRecord.STATE_INITIALIZED) {
          it.release()
          throw IllegalStateException("AudioRecord not initialized")
        }
      }
  }

  private suspend fun readLoop(activeRecorder: AudioRecord) {
    val audioChannel = Channel<FloatArray>(CHANNEL_CAPACITY)
    val readerJob =
      scope.launch(Dispatchers.IO) {
        val readBuffer = ShortArray(CHUNK_SAMPLES)
        try {
          while (scope.isActive && job?.isActive == true && !stopRequested) {
            val read = activeRecorder.read(readBuffer, 0, readBuffer.size)
            if (read <= 0) {
              delay(20)
              continue
            }

            val samples = FloatArray(read)
            var sumSquares = 0.0
            for (i in 0 until read) {
              val sample = readBuffer[i] / 32768.0f
              samples[i] = sample
              sumSquares += (sample * sample).toDouble()
            }
            val level = sqrt(sumSquares / read).toFloat().coerceIn(0f, 1f)
            onInputLevel((level * 8f).coerceIn(0f, 1f))
            audioChannel.trySend(samples)
          }
        } finally {
          audioChannel.close()
        }
      }

    val buffer = ArrayList<Float>(MAX_AUDIO_BUFFER_SAMPLES)
    var bufferStartOffset = 0L
    var currentOffset = 0L
    var isSpeechStarted = false
    var speechStartOffset = 0L
    var speechDetectedStartOffset = 0L
    var lastSpeechDetectedAt = 0L
    var lastActivityAt = SystemClock.elapsedRealtime()
    var lastPartialAt = 0L
    var partialUpdatesForSegment = 0
    var lastPartialText = ""
    var consecutiveVadWindows = 0

    fun resetSegmentState(
      clearBuffer: Boolean,
      resetVad: Boolean,
      reason: String,
    ) {
      if (clearBuffer) {
        buffer.clear()
        bufferStartOffset = currentOffset
      }
      isSpeechStarted = false
      speechStartOffset = 0L
      speechDetectedStartOffset = 0L
      lastSpeechDetectedAt = 0L
      lastPartialAt = 0L
      partialUpdatesForSegment = 0
      lastPartialText = ""
      consecutiveVadWindows = 0
      if (resetVad) asrEngine.resetVad()
      Log.d(TAG, "ASR segment reset: $reason currentOffset=$currentOffset")
    }

    fun segmentAudio(): FloatArray =
      sliceBuffer(
        buffer = buffer,
        bufferStartOffset = bufferStartOffset,
        fromOffset = speechStartOffset,
        toOffset = currentOffset,
      )

    fun partialAudio(): FloatArray =
      sliceBuffer(
        buffer = buffer,
        bufferStartOffset = bufferStartOffset,
        fromOffset = max(speechStartOffset, currentOffset - PARTIAL_WINDOW_SAMPLES),
        toOffset = currentOffset,
      )

    try {
      for (samples in audioChannel) {
        if (stopRequested) {
          if (
            stopShouldFinalize &&
            isSpeechStarted &&
            currentOffset - speechDetectedStartOffset >= MIN_UTTERANCE_SAMPLES
          ) {
            val finalText = recognizeFinalUtterance(segmentAudio())
            if (isUsefulFinal(finalText)) onFinal(requireNotNull(finalText))
          }
          break
        }

        val now = SystemClock.elapsedRealtime()
        appendSlidingBuffer(buffer, samples, MAX_AUDIO_BUFFER_SAMPLES)
        currentOffset += samples.size
        val overflow = currentOffset - bufferStartOffset - buffer.size
        if (overflow > 0) bufferStartOffset += overflow

        val vadSpeech = asrEngine.detectSpeech(samples)
        if (vadSpeech) {
          consecutiveVadWindows += 1
          lastSpeechDetectedAt = now
          lastActivityAt = now
        } else {
          consecutiveVadWindows = 0
        }

        if (!isSpeechStarted && consecutiveVadWindows >= START_CONFIRM_WINDOWS) {
          val firstVadOffset = currentOffset - samples.size.toLong() * consecutiveVadWindows
          speechDetectedStartOffset = max(bufferStartOffset, firstVadOffset)
          speechStartOffset =
            max(
              bufferStartOffset,
              speechDetectedStartOffset - SPEECH_START_LOOKBACK_SAMPLES,
            )
          isSpeechStarted = true
          partialUpdatesForSegment = 0
          lastPartialText = ""
          lastPartialAt = 0L
          lastSpeechDetectedAt = now
          lastActivityAt = now
          Log.d(
            TAG,
            "speech start speechStartOffset=$speechStartOffset detectedOffset=$speechDetectedStartOffset currentOffset=$currentOffset lookback=$SPEECH_START_LOOKBACK_SAMPLES",
          )
        }

        if (isSpeechStarted) {
          val detectedSpeechSamples = currentOffset - speechDetectedStartOffset
          val segmentSamples = currentOffset - speechStartOffset
          val silenceElapsed = now - lastSpeechDetectedAt
          val shouldFinalize =
            (!vadSpeech && silenceElapsed > FINAL_SILENCE_MS && detectedSpeechSamples >= MIN_UTTERANCE_SAMPLES) ||
              segmentSamples >= MAX_UTTERANCE_SAMPLES
          if (shouldFinalize) {
            val audio = segmentAudio()
            val finalText = recognizeFinalUtterance(audio)
            Log.d(
              TAG,
              "speech end silenceMs=$silenceElapsed samples=${audio.size} start=$speechStartOffset current=$currentOffset",
            )
            resetSegmentState(clearBuffer = true, resetVad = true, reason = "final")
            if (isUsefulFinal(finalText)) onFinal(requireNotNull(finalText))
            continue
          }

          if (
            vadSpeech &&
            partialUpdatesForSegment < MAX_PARTIAL_UPDATES &&
            detectedSpeechSamples >= MIN_PARTIAL_SAMPLES &&
            now - lastPartialAt >= PARTIAL_INTERVAL_MS
          ) {
            lastPartialAt = now
            val partial = recognizeAudio(partialAudio(), final = false)
            if (isUsefulPartial(partial) && partial != lastPartialText) {
              val safePartial = requireNotNull(partial)
              onPartial(safePartial)
              partialUpdatesForSegment += 1
              lastPartialText = safePartial
            }
          }
        }

        if (now - lastActivityAt > INACTIVITY_RESET_MS) {
          resetSegmentState(clearBuffer = true, resetVad = true, reason = "inactive")
          lastActivityAt = now
        }
      }
    } finally {
      readerJob.cancel()
      audioChannel.close()
      job = null
      recorder = null
      releaseAudioEffects()
      runCatching { activeRecorder.stop() }
      runCatching { activeRecorder.release() }
      asrEngine.resetVad()
      onListening(false)
      onInputLevel(0f)
    }
    Log.d(TAG, "Local voice capture loop stopped")
  }

  private fun recognizeAudio(
    audio: FloatArray,
    final: Boolean,
  ): String? {
    val started = SystemClock.elapsedRealtime()
    val text =
      if (final) {
        asrEngine.recognizeFinal(audio)
      } else {
        asrEngine.recognizePartial(audio)
      }?.trim()?.takeIf { it.isNotEmpty() }
    Log.d(
      TAG,
      "ASR ${if (final) "final" else "partial"} samples=${audio.size} durMs=${SystemClock.elapsedRealtime() - started}",
    )
    return text
  }

  private fun recognizeFinalUtterance(audio: FloatArray): String? = recognizeAudio(audio, final = true)

  private fun isUsefulPartial(text: String?): Boolean {
    val normalized = text?.trim().orEmpty()
    if (normalized.length < 2) return false
    return normalized !in fillerResults
  }

  private fun isUsefulFinal(text: String?): Boolean {
    val normalized = text?.trim().orEmpty()
    if (normalized.isEmpty()) return false
    return normalized !in fillerResults
  }

  private fun enableAudioEffects(record: AudioRecord) {
    releaseAudioEffects()
    val sessionId = record.audioSessionId
    acousticEchoCanceler =
      runCatching {
        if (AcousticEchoCanceler.isAvailable()) {
          AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
        } else {
          null
        }
      }.getOrNull()
    noiseSuppressor =
      runCatching {
        if (NoiseSuppressor.isAvailable()) {
          NoiseSuppressor.create(sessionId)?.apply { enabled = true }
        } else {
          null
        }
      }.getOrNull()
    automaticGainControl =
      runCatching {
        if (AutomaticGainControl.isAvailable()) {
          AutomaticGainControl.create(sessionId)?.apply { enabled = true }
        } else {
          null
        }
      }.getOrNull()
  }

  private fun releaseAudioEffects() {
    runCatching { acousticEchoCanceler?.release() }
    runCatching { noiseSuppressor?.release() }
    runCatching { automaticGainControl?.release() }
    acousticEchoCanceler = null
    noiseSuppressor = null
    automaticGainControl = null
  }

  companion object {
    private const val TAG = "LocalVoiceCapture"
    private const val SAMPLE_RATE = 16_000
    private const val CHUNK_SAMPLES = SAMPLE_RATE / 20
    private const val CHANNEL_CAPACITY = 600
    private const val SPEECH_START_LOOKBACK_SAMPLES = SAMPLE_RATE * 8 / 10
    private const val START_CONFIRM_WINDOWS = 2
    private const val PARTIAL_INTERVAL_MS = 1_200L
    private const val MIN_PARTIAL_SAMPLES = SAMPLE_RATE * 8 / 10
    private const val PARTIAL_WINDOW_SAMPLES = SAMPLE_RATE * 3 / 2
    private const val FINAL_SILENCE_MS = 200L
    private const val MIN_UTTERANCE_SAMPLES = SAMPLE_RATE / 3
    private const val MAX_UTTERANCE_SAMPLES = SAMPLE_RATE * 60
    private const val MAX_AUDIO_BUFFER_SAMPLES = SAMPLE_RATE * 65
    private const val INACTIVITY_RESET_MS = 60_000L
    private const val MAX_PARTIAL_UPDATES = 2
    private val fillerResults = setOf("\u55ef", "\u55ef\u55ef", "\u55ef\u55ef\u55ef", "\u554a", "N")
  }
}

private fun appendSlidingBuffer(
  target: ArrayList<Float>,
  samples: FloatArray,
  limit: Int,
) {
  samples.forEach(target::add)
  val overflow = target.size - limit
  if (overflow > 0) {
    target.subList(0, overflow).clear()
  }
}

private fun sliceBuffer(
  buffer: List<Float>,
  bufferStartOffset: Long,
  fromOffset: Long,
  toOffset: Long,
): FloatArray {
  if (buffer.isEmpty() || toOffset <= fromOffset) return FloatArray(0)
  val from = (fromOffset - bufferStartOffset).coerceIn(0L, buffer.size.toLong()).toInt()
  val to = (toOffset - bufferStartOffset).coerceIn(0L, buffer.size.toLong()).toInt()
  if (to <= from) return FloatArray(0)
  return buffer.subList(from, to).toFloatArray()
}

private fun audioSourceName(source: Int): String =
  when (source) {
    MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
    MediaRecorder.AudioSource.MIC -> "MIC"
    else -> source.toString()
  }
