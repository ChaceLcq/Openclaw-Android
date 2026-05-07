package ai.openclaw.app.voice

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.agenew.translate.tts.TtsService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LocalMnnTtsEngine(
  private val context: Context,
) {
  private val lock = Any()
  private var service: TtsService? = null
  private var initDeferred: CompletableDeferred<Boolean>? = null
  @Volatile private var unavailableReason: String? = null

  suspend fun preload(): Boolean = ensureService() != null

  fun isReady(): Boolean = synchronized(lock) { service?.isLoaded == true }

  internal suspend fun synthesize(
    text: String,
    shouldContinue: () -> Boolean = { true },
  ): TalkSpeakAudio? =
    withContext(Dispatchers.IO) {
      val spoken = text.trim()
      if (spoken.isEmpty()) return@withContext null
      if (!shouldContinue()) {
        Log.d(TAG, "MNN TTS synth skipped before init chars=${spoken.length}")
        return@withContext null
      }
      val active = ensureService() ?: return@withContext null
      if (!shouldContinue()) {
        Log.d(TAG, "MNN TTS synth skipped before process chars=${spoken.length}")
        return@withContext null
      }
      val started = SystemClock.elapsedRealtime()
      val samples = active.process(spoken)
      if (!shouldContinue()) {
        Log.d(TAG, "MNN TTS synth discarded after cancel chars=${spoken.length} durMs=${SystemClock.elapsedRealtime() - started}")
        return@withContext null
      }
      if (samples.isEmpty()) {
        unavailableReason = active.getLastErrorMessage() ?: "MNN TTS returned empty audio"
        Log.w(TAG, "MNN TTS empty output reason=$unavailableReason")
        return@withContext null
      }
      Log.d(
        TAG,
        "MNN TTS synthesized chars=${spoken.length} samples=${samples.size} sampleRate=${active.getCurrentSampleRate()} durMs=${SystemClock.elapsedRealtime() - started}",
      )
      TalkSpeakAudio(
        bytes = samples.toLittleEndianBytes(),
        provider = "mnn-bert-vits2",
        outputFormat = "pcm_${active.getCurrentSampleRate()}",
        voiceCompatible = true,
        mimeType = "audio/pcm",
        fileExtension = null,
      )
    }

  fun reason(): String? = unavailableReason

  fun release() {
    synchronized(lock) {
      service?.destroy()
      service = null
    }
  }

  private suspend fun ensureService(): TtsService? {
    synchronized(lock) {
      service?.takeIf { it.isLoaded }?.let { return it }
    }
    var pending: CompletableDeferred<Boolean>? = null
    var owner: CompletableDeferred<Boolean>? = null
    synchronized(lock) {
      service?.takeIf { it.isLoaded }?.let { return it }
      initDeferred?.let {
        pending = it
      } ?: run {
        owner = CompletableDeferred()
        initDeferred = owner
      }
    }
    pending?.let { deferred ->
      return if (deferred.await()) {
        synchronized(lock) { service?.takeIf { it.isLoaded } }
      } else {
        null
      }
    }
    val deferred = requireNotNull(owner)
    return withContext(Dispatchers.IO) {
      val candidate =
        try {
          TtsService()
        } catch (err: Throwable) {
          unavailableReason = err.message ?: err::class.simpleName
          Log.w(TAG, "MNN TTS create failed: $unavailableReason")
          synchronized(lock) {
            if (initDeferred === deferred) initDeferred = null
          }
          deferred.complete(false)
          return@withContext null
        }
      val started = SystemClock.elapsedRealtime()
      val ok =
        try {
          candidate.init(context.applicationContext)
        } catch (err: Throwable) {
          unavailableReason = err.message ?: err::class.simpleName
          false
        }
      if (!ok) {
        unavailableReason = candidate.getLastErrorMessage() ?: unavailableReason ?: "MNN TTS init failed"
        Log.w(TAG, "MNN TTS init failed durMs=${SystemClock.elapsedRealtime() - started} reason=$unavailableReason")
        candidate.destroy()
        synchronized(lock) {
          if (initDeferred === deferred) initDeferred = null
        }
        deferred.complete(false)
        return@withContext null
      }
      synchronized(lock) {
        service?.destroy()
        service = candidate
        if (initDeferred === deferred) initDeferred = null
      }
      unavailableReason = null
      Log.d(TAG, "MNN TTS init ok durMs=${SystemClock.elapsedRealtime() - started}")
      deferred.complete(true)
      candidate
    }
  }

  private companion object {
    private const val TAG = "LocalMnnTtsEngine"
  }
}

private fun ShortArray.toLittleEndianBytes(): ByteArray {
  val out = ByteArray(size * 2)
  for (index in indices) {
    val value = this[index].toInt()
    out[index * 2] = (value and 0xFF).toByte()
    out[index * 2 + 1] = ((value ushr 8) and 0xFF).toByte()
  }
  return out
}
