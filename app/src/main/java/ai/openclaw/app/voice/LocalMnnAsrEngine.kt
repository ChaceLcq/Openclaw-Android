package ai.openclaw.app.voice

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.agenew.translate.metting.SafeAsrServiceWrapper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

enum class LocalMnnAsrStatus(
  val label: String,
) {
  Idle("ASR idle"),
  Warming("ASR warming"),
  Ready("MNN Voice ready"),
  Unavailable("System fallback"),
}

class LocalMnnAsrEngine(
  private val context: Context,
) {
  private val lock = Any()
  private var wrapper: SafeAsrServiceWrapper? = null
  private var initDeferred: CompletableDeferred<Boolean>? = null
  @Volatile private var unavailableReason: String? = null

  private val _status = MutableStateFlow(LocalMnnAsrStatus.Idle)
  val status: StateFlow<LocalMnnAsrStatus> = _status

  suspend fun preload(): Boolean = ensureReady()

  fun isReady(): Boolean = synchronized(lock) { wrapper?.isModelInitialized() == true }

  suspend fun ensureReady(): Boolean {
    var pending: CompletableDeferred<Boolean>? = null
    var owner: CompletableDeferred<Boolean>? = null
    synchronized(lock) {
      if (wrapper?.isModelInitialized() == true) {
        _status.value = LocalMnnAsrStatus.Ready
        return true
      }
      initDeferred?.let {
        pending = it
        _status.value = LocalMnnAsrStatus.Warming
      } ?: run {
        owner = CompletableDeferred()
        initDeferred = owner
        _status.value = LocalMnnAsrStatus.Warming
      }
    }
    pending?.let { return it.await() }
    val deferred = requireNotNull(owner)
    return withContext(Dispatchers.IO) {
      val started = SystemClock.elapsedRealtime()
      var candidate: SafeAsrServiceWrapper? = null
      val ok =
        try {
          candidate = SafeAsrServiceWrapper(context.applicationContext)
          val completed = CompletableDeferred<Boolean>()
          candidate.initializeModel { initialized ->
            completed.complete(initialized)
          }
          completed.await()
        } catch (err: Throwable) {
          unavailableReason = err.message ?: err::class.simpleName
          false
        }
      synchronized(lock) {
        val activeCandidate = candidate
        if (ok && activeCandidate != null) {
          wrapper?.release()
          wrapper = activeCandidate
          unavailableReason = null
          _status.value = LocalMnnAsrStatus.Ready
        } else {
          activeCandidate?.release()
          unavailableReason = unavailableReason ?: "MNN ASR init failed"
          _status.value = LocalMnnAsrStatus.Unavailable
        }
        if (initDeferred === deferred) initDeferred = null
      }
      Log.d(TAG, "ASR preload ok=$ok durMs=${SystemClock.elapsedRealtime() - started}")
      deferred.complete(ok)
      ok
    }
  }

  fun detectSpeech(samples: FloatArray): Boolean {
    if (samples.isEmpty()) return false
    val active = synchronized(lock) { wrapper } ?: return false
    return try {
      active.detectSpeechWithVad(samples)
    } catch (err: Throwable) {
      synchronized(lock) {
        unavailableReason = err.message ?: err::class.simpleName
        _status.value = LocalMnnAsrStatus.Unavailable
      }
      false
    }
  }

  fun resetVad() {
    runCatching {
      synchronized(lock) { wrapper }?.resetVad()
    }
  }

  fun recognizePartial(samples: FloatArray): String? = recognize(samples = samples, kind = "partial")

  fun recognizeFinal(samples: FloatArray): String? = recognize(samples = samples, kind = "final")

  fun recognize(samples: FloatArray): String? = recognize(samples = samples, kind = "decode")

  private fun recognize(
    samples: FloatArray,
    kind: String,
  ): String? {
    if (samples.isEmpty()) return ""
    val active = synchronized(lock) { wrapper } ?: return null
    val started = SystemClock.elapsedRealtime()
    return try {
      val text = active.recognize(samples, countForRecycle = kind != "partial")?.trim()
      Log.d(TAG, "ASR $kind samples=${samples.size} durMs=${SystemClock.elapsedRealtime() - started} text=${text.orEmpty()}")
      text
    } catch (err: Throwable) {
      synchronized(lock) {
        unavailableReason = err.message ?: err::class.simpleName
        _status.value = LocalMnnAsrStatus.Unavailable
      }
      null
    }
  }

  fun reason(): String? = unavailableReason

  fun release() {
    synchronized(lock) {
      wrapper?.release()
      wrapper = null
      initDeferred?.cancel()
      initDeferred = null
      _status.value = LocalMnnAsrStatus.Idle
    }
  }

  companion object {
    private const val TAG = "LocalMnnAsrEngine"
  }
}
