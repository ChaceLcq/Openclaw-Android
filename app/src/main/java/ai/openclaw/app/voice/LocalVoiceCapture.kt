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
  private val onSpeechStart: () -> Unit = {},
  private val onPartial: (String) -> Unit,
  private val onFinal: (String) -> Unit,
  private val onUnavailable: (String) -> Unit,
  private val externalGatePartialsEnabled: Boolean = false,
) {
  private var job: Job? = null
  private var recorder: AudioRecord? = null
  private var acousticEchoCanceler: AcousticEchoCanceler? = null
  private var noiseSuppressor: NoiseSuppressor? = null
  private var automaticGainControl: AutomaticGainControl? = null

  @Volatile
  private var stopRequested = false

  @Volatile
  private var recordingStarted = false

  @Volatile
  private var recordingStarting = false

  @Volatile
  private var stopShouldFinalize = true

  @Volatile
  private var externalGateMode = false

  @Volatile
  private var externalGateOpen = false

  @Volatile
  private var externalGateCloseSilenceMs = DEFAULT_EXTERNAL_GATE_CLOSE_SILENCE_MS

  @Volatile
  private var lastLoggedExternalGateOpen: Boolean? = null

  fun setExternalSpeechGateMode(
    enabled: Boolean,
    closeSilenceMs: Long = DEFAULT_EXTERNAL_GATE_CLOSE_SILENCE_MS,
  ) {
    externalGateMode = enabled
    externalGateCloseSilenceMs = closeSilenceMs.coerceAtLeast(0L)
    if (!enabled) externalGateOpen = false
    Log.d(TAG, "external gate mode enabled=$enabled closeSilenceMs=$externalGateCloseSilenceMs")
  }

  fun setExternalSpeechGate(open: Boolean) {
    externalGateOpen = open
    if (lastLoggedExternalGateOpen != open) {
      lastLoggedExternalGateOpen = open
      Log.d(TAG, "external gate set open=$open running=${isRunning()}")
    }
  }

  fun isRunning(): Boolean = job?.isActive == true && (recordingStarting || recordingStarted)

  fun start(): Boolean {
    job?.let { active ->
      if (active.isActive) {
        Log.d(TAG, "Local voice capture already active starting=$recordingStarting recording=$recordingStarted")
        return false
      }
    }
    recordingStarted = false
    recordingStarting = true
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
          recordingStarting = false
          onUnavailable(asrEngine.reason() ?: "MNN ASR unavailable")
          return@launch
        }
        Log.d(TAG, "Local voice capture ASR ready")
        val activeRecorder =
          try {
            createRecorder()
          } catch (err: Throwable) {
            recordingStarting = false
            onUnavailable("AudioRecord failed: ${err.message ?: err::class.simpleName}")
            return@launch
        }
        recorder = activeRecorder
        enableAudioEffects(activeRecorder)
        val preferredDevice = inputManager.resolveAudioDevice()
        var preferredSet = true
        if (preferredDevice != null) {
          preferredSet = runCatching { activeRecorder.setPreferredDevice(preferredDevice) }.getOrDefault(false)
        }
        inputManager.updateActiveDevice(preferredDevice)
        onStatus(inputManager.activeInputLabel.value)
        val startResult =
          runCatching {
            if (activeRecorder.state != AudioRecord.STATE_INITIALIZED) {
              error("AudioRecord became uninitialized before start")
            }
            activeRecorder.startRecording()
          }
        if (startResult.isFailure) {
          val err = startResult.exceptionOrNull()
          Log.w(TAG, "AudioRecord start failed: ${err?.message ?: err?.javaClass?.simpleName}")
          recordingStarting = false
          recordingStarted = false
          onListening(false)
          onInputLevel(0f)
          runCatching { activeRecorder.release() }
          if (recorder === activeRecorder) recorder = null
          onUnavailable("AudioRecord start failed: ${err?.message ?: err?.javaClass?.simpleName ?: "unknown"}")
          return@launch
        }
        recordingStarted = true
        recordingStarting = false
        onListening(true)
        val routedDevice = activeRecorder.routedDevice
        val activeDevice = routedDevice ?: preferredDevice
        inputManager.updateActiveDevice(activeDevice)
        onStatus(inputManager.activeInputLabel.value)
        Log.d(
          TAG,
          "Local voice capture recording started preferred=${inputManager.deviceLabel(preferredDevice)} preferredSet=$preferredSet routed=${inputManager.deviceLabel(routedDevice)} active=${inputManager.activeInputLabel.value}",
        )
        asrEngine.resetVad()
        try {
          readLoop(activeRecorder)
        } finally {
          recordingStarting = false
          recordingStarted = false
        }
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
    recordingStarting = false
    recordingStarted = false
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
      MediaRecorder.AudioSource.MIC,
      MediaRecorder.AudioSource.VOICE_COMMUNICATION,
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
    var segmentGeneration = 0L
    var partialJob: Job? = null
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
      segmentGeneration += 1
      partialJob?.cancel()
      partialJob = null
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

    fun schedulePartialIfNeeded(
      now: Long,
      speechSamples: Long,
    ) {
      if (
        partialUpdatesForSegment >= MAX_PARTIAL_UPDATES ||
        speechSamples < MIN_PARTIAL_SAMPLES ||
        now - lastPartialAt < PARTIAL_INTERVAL_MS ||
        partialJob?.isActive == true
      ) {
        return
      }
      lastPartialAt = now
      val generation = segmentGeneration
      val audio = partialAudio()
      partialJob =
        scope.launch(Dispatchers.IO) {
          val partial = recognizeAudio(audio, final = false)
          if (generation != segmentGeneration) return@launch
          if (isUsefulPartial(partial) && partial != lastPartialText) {
            val safePartial = requireNotNull(partial)
            onPartial(safePartial)
            partialUpdatesForSegment += 1
            lastPartialText = safePartial
          }
        }
    }

    try {
      for (samples in audioChannel) {
        if (stopRequested) {
          partialJob?.cancel()
          if (
            stopShouldFinalize &&
            isSpeechStarted &&
            currentOffset - speechDetectedStartOffset >= MIN_UTTERANCE_SAMPLES
          ) {
            val audio = segmentAudio()
            val finalText = recognizeFinalUtterance(audio)
            if (isUsefulLocalAsrFinal(finalText, audio.size)) onFinal(requireNotNull(finalText))
          }
          break
        }

        val now = SystemClock.elapsedRealtime()
        appendSlidingBuffer(buffer, samples, MAX_AUDIO_BUFFER_SAMPLES)
        currentOffset += samples.size
        val overflow = currentOffset - bufferStartOffset - buffer.size
        if (overflow > 0) bufferStartOffset += overflow

        if (externalGateMode) {
          val gateOpen = externalGateOpen
          if (gateOpen) {
            lastActivityAt = now
            lastSpeechDetectedAt = now
          }

          if (!isSpeechStarted && gateOpen) {
            speechDetectedStartOffset = max(bufferStartOffset, currentOffset - samples.size.toLong())
            speechStartOffset =
              externalGateSpeechStartOffset(
                bufferStartOffset = bufferStartOffset,
                currentOffset = currentOffset,
                lookbackSamples = SPEECH_START_LOOKBACK_SAMPLES.toLong(),
              )
            isSpeechStarted = true
            partialUpdatesForSegment = 0
            lastPartialText = ""
            lastPartialAt = 0L
            segmentGeneration += 1
            Log.d(
              TAG,
              "external gate speech start speechStartOffset=$speechStartOffset currentOffset=$currentOffset lookback=$SPEECH_START_LOOKBACK_SAMPLES",
            )
            onSpeechStart()
          }

          if (isSpeechStarted) {
            val segmentSamples = currentOffset - speechStartOffset
            val silenceElapsed = now - lastSpeechDetectedAt
            val shouldFinalize =
              (!gateOpen && silenceElapsed > externalGateCloseSilenceMs && segmentSamples >= MIN_UTTERANCE_SAMPLES) ||
              segmentSamples >= MAX_UTTERANCE_SAMPLES
            if (shouldFinalize) {
              val audio = segmentAudio()
              segmentGeneration += 1
              partialJob?.cancel()
              val finalText = recognizeFinalUtterance(audio)
              Log.d(
                TAG,
                "external gate speech end silenceMs=$silenceElapsed samples=${audio.size} start=$speechStartOffset current=$currentOffset",
              )
              resetSegmentState(clearBuffer = true, resetVad = true, reason = "external-gate-final")
              if (isUsefulLocalAsrFinal(finalText, audio.size)) onFinal(requireNotNull(finalText))
              continue
            }

            if (
              gateOpen &&
              shouldSchedulePartialRecognition(
                externalGateMode = true,
                externalGatePartialsEnabled = externalGatePartialsEnabled,
              )
            ) {
              schedulePartialIfNeeded(
                now = now,
                speechSamples = currentOffset - speechDetectedStartOffset,
              )
            }
          }
        } else {
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
            segmentGeneration += 1
            lastSpeechDetectedAt = now
            lastActivityAt = now
            Log.d(
              TAG,
              "speech start speechStartOffset=$speechStartOffset detectedOffset=$speechDetectedStartOffset currentOffset=$currentOffset lookback=$SPEECH_START_LOOKBACK_SAMPLES",
            )
            onSpeechStart()
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
              segmentGeneration += 1
              partialJob?.cancel()
              val finalText = recognizeFinalUtterance(audio)
              Log.d(
                TAG,
                "speech end silenceMs=$silenceElapsed samples=${audio.size} start=$speechStartOffset current=$currentOffset",
              )
              resetSegmentState(clearBuffer = true, resetVad = true, reason = "final")
              if (isUsefulLocalAsrFinal(finalText, audio.size)) onFinal(requireNotNull(finalText))
              continue
            }

            if (
              vadSpeech &&
              partialUpdatesForSegment < MAX_PARTIAL_UPDATES
            ) {
              schedulePartialIfNeeded(now = now, speechSamples = detectedSpeechSamples)
            }
          }
        }

        if (now - lastActivityAt > INACTIVITY_RESET_MS) {
          resetSegmentState(clearBuffer = true, resetVad = true, reason = "inactive")
          lastActivityAt = now
        }
      }
    } finally {
      partialJob?.cancel()
      readerJob.cancel()
      audioChannel.close()
      job = null
      recorder = null
      recordingStarted = false
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
    Log.d(TAG, "ASR ${if (final) "final" else "partial"} start samples=${audio.size}")
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
    private const val PARTIAL_INTERVAL_MS = 1_500L
    private const val MIN_PARTIAL_SAMPLES = SAMPLE_RATE * 8 / 10
    private const val PARTIAL_WINDOW_SAMPLES = SAMPLE_RATE * 2
    private const val FINAL_SILENCE_MS = 800L
    private const val DEFAULT_EXTERNAL_GATE_CLOSE_SILENCE_MS = 800L
    private const val MIN_UTTERANCE_SAMPLES = SAMPLE_RATE / 3
    private const val MAX_UTTERANCE_SAMPLES = SAMPLE_RATE * 60
    private const val MAX_AUDIO_BUFFER_SAMPLES = SAMPLE_RATE * 65
    private const val INACTIVITY_RESET_MS = 60_000L
    private const val MAX_PARTIAL_UPDATES = 2
    internal const val SHORT_FINAL_FALSE_POSITIVE_MAX_SAMPLES = SAMPLE_RATE * 4 / 5
    internal val fillerResults = setOf("\u55ef", "\u55ef\u55ef", "\u55ef\u55ef\u55ef", "\u554a", "N")
    internal val shortFinalFalsePositiveResults = setOf("\u6211")
  }
}

internal fun isUsefulLocalAsrFinal(
  text: String?,
  sampleCount: Int,
): Boolean {
  val normalized = text?.trim().orEmpty()
  if (normalized.isEmpty()) return false
  if (normalized.length < 2) return false
  if (normalized in LocalVoiceCapture.fillerResults) return false
  if (
    sampleCount < LocalVoiceCapture.SHORT_FINAL_FALSE_POSITIVE_MAX_SAMPLES &&
    normalized in LocalVoiceCapture.shortFinalFalsePositiveResults
  ) {
    return false
  }
  return true
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

internal fun externalGateSpeechStartOffset(
  bufferStartOffset: Long,
  currentOffset: Long,
  lookbackSamples: Long,
): Long = max(bufferStartOffset, currentOffset - lookbackSamples)

internal fun shouldSchedulePartialRecognition(
  externalGateMode: Boolean,
  externalGatePartialsEnabled: Boolean,
): Boolean = !externalGateMode || externalGatePartialsEnabled
