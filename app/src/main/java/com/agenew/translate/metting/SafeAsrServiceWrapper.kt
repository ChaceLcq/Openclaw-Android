package com.agenew.translate.metting

import android.content.Context
import android.util.Log
import com.agenew.translate.mnn.MnnRuntimeCoordinator
import com.k2fsa.sherpa.mnn.OfflineModelConfig
import com.k2fsa.sherpa.mnn.OfflineRecognizer
import com.k2fsa.sherpa.mnn.OfflineRecognizerConfig
import com.k2fsa.sherpa.mnn.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.mnn.SileroVadModelConfig
import com.k2fsa.sherpa.mnn.Vad
import com.k2fsa.sherpa.mnn.VadModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SafeAsrServiceWrapper(
    private val context: Context,
    private val modelDir: String? = null,
) {
    interface OnPartialResultListener {
        fun onPartialResult(partialText: String)
    }

    interface OnResultListener {
        fun onResult(text: String)
    }

    private data class RuntimeBundle(
        val recognizer: OfflineRecognizer,
        val vad: Vad,
    )

    companion object {
        private const val MAX_DECODES_BEFORE_RECYCLE = 64
        private const val MAX_VAD_CALLS_BEFORE_RESET = 400
        private const val VAD_WINDOW_SIZE = 512
    }

    private val tag = "SafeAsrServiceWrapper"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val initLock = Any()
    private val decodeLock = Any()
    private val streamingBuffer = ArrayList<Float>()

    @Volatile
    private var isInitialized = false

    @Volatile
    private var isStreaming = false

    @Volatile
    private var lastErrorMessage: String? = null

    private var offlineRecognizer: OfflineRecognizer? = null
    private var vad: Vad? = null
    private var partialResultListener: OnPartialResultListener? = null
    private var resultListener: OnResultListener? = null

    private var modelFilePath: String? = null
    private var tokensFilePath: String? = null
    private var vadFilePath: String? = null
    private var decodeCount = 0
    private var vadCallCount = 0
    private var vadPendingSamples = FloatArray(0)

    fun setOnPartialResultListener(listener: OnPartialResultListener?) {
        partialResultListener = listener
    }

    fun setOnResultListener(listener: OnResultListener?) {
        resultListener = listener
    }

    fun initializeModel(onComplete: (Boolean) -> Unit) {
        synchronized(initLock) {
            if (isInitialized) {
                onComplete(true)
                return
            }
        }

        Log.d(tag, "ASR initializeModel requested")
        scope.launch(Dispatchers.IO) {
            try {
                Log.d(tag, "ASR prepareModelFiles start")
                prepareModelFiles()
                Log.d(tag, "ASR createRuntimeBundle start model=$modelFilePath")
                val runtimeBundle = createRuntimeBundle()
                synchronized(decodeLock) {
                    replaceRuntime(runtimeBundle)
                    isInitialized = true
                }
                lastErrorMessage = null

                Log.d(tag, "ASR initialized from files: $modelFilePath")
                withContext(Dispatchers.Main) {
                    onComplete(true)
                }
            } catch (t: Throwable) {
                lastErrorMessage = t.message ?: t::class.simpleName
                Log.e(tag, "Failed to initialize ASR", t)
                synchronized(decodeLock) {
                    clearRuntime()
                    isInitialized = false
                }
                withContext(Dispatchers.Main) {
                    onComplete(false)
                }
            }
        }
    }

    fun isModelInitialized(): Boolean = isInitialized && offlineRecognizer != null && vad != null

    fun getLastErrorMessage(): String? = lastErrorMessage

    fun getVad(): Vad? = vad

    fun detectSpeechWithVad(audioData: FloatArray): Boolean {
        if (audioData.isEmpty()) {
            return false
        }

        synchronized(decodeLock) {
            val localVad = vad ?: return false
            try {
                val detected = feedVadAlignedLocked(localVad, audioData)
                return detected
            } catch (t: Throwable) {
                Log.e(tag, "VAD failed, rebuilding runtime", t)
                rebuildRuntimeLocked("vad failure")
                return false
            }
        }
    }

    fun resetVad() {
        synchronized(decodeLock) {
            vad?.reset()
            vadCallCount = 0
            vadPendingSamples = FloatArray(0)
            streamingBuffer.clear()
        }
    }

    fun processAudioWithVad(audioData: FloatArray): String? {
        return recognize(audioData)
    }

    fun startStreamingRecognition() {
        synchronized(decodeLock) {
            streamingBuffer.clear()
            isStreaming = true
        }
    }

    fun stopStreamingRecognition() {
        val audioToProcess = synchronized(decodeLock) {
            if (!isStreaming) {
                return
            }
            isStreaming = false
            if (streamingBuffer.isEmpty()) {
                return
            }
            val merged = streamingBuffer.toFloatArray()
            streamingBuffer.clear()
            merged
        }

        val result = recognize(audioToProcess)
        if (!result.isNullOrBlank()) {
            resultListener?.onResult(result)
        }
    }

    fun acceptAudioData(audioData: FloatArray) {
        val currentSize = synchronized(decodeLock) {
            if (!isStreaming) {
                return
            }
            audioData.forEach(streamingBuffer::add)
            streamingBuffer.size
        }

        if (currentSize % (16000 * 2) == 0) {
            val partial = recognize(snapshotStreamingBuffer())
            if (!partial.isNullOrBlank()) {
                partialResultListener?.onPartialResult(partial)
            }
        }
    }

    fun recognize(
        audioData: FloatArray,
        countForRecycle: Boolean = true,
    ): String? {
        if (!isModelInitialized()) {
            return null
        }
        if (audioData.isEmpty()) {
            return ""
        }

        synchronized(decodeLock) {
            val recognizer = offlineRecognizer ?: return null

            var recycleAfterDecode = false
            return try {
                synchronized(MnnRuntimeCoordinator.LOCK) {
                    val stream = recognizer.createStream()
                    try {
                        stream.acceptWaveform(audioData, 16000)
                        recognizer.decode(stream)
                        val text = recognizer.getResult(stream).text.trim()
                        if (countForRecycle) {
                            decodeCount += 1
                        }
                        recycleAfterDecode = decodeCount >= MAX_DECODES_BEFORE_RECYCLE
                        text
                    } finally {
                        try {
                            stream.release()
                        } catch (_: Throwable) {
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e(tag, "Failed to decode audio, rebuilding runtime", t)
                rebuildRuntimeLocked("decode failure")
                null
            } finally {
                if (recycleAfterDecode) {
                    rebuildRuntimeLocked("periodic decode recycle")
                }
            }
        }
    }

    fun release() {
        synchronized(decodeLock) {
            isInitialized = false
            isStreaming = false
            streamingBuffer.clear()
            clearRuntime()
        }
        scope.cancel()
    }

    private fun prepareModelFiles() {
        if (modelDir == null) {
            existingAsrModelFiles(
                appFilesDir = context.filesDir,
                externalFilesDir = context.getExternalFilesDir(null),
            )?.let { files ->
                modelFilePath = files.modelFile.absolutePath
                tokensFilePath = files.tokensFile.absolutePath
                vadFilePath = files.vadFile.absolutePath
                Log.d(tag, "ASR using existing local model files: $modelFilePath")
                return
            }
        }

        val targetDir = File(modelDir ?: File(context.filesDir, "asr_models/sense").absolutePath)
        val modelFile = ensurePreferredAssetFile(
            preferredAssetPath = "sense/sense_weight_quant.mnn",
            preferredDestination = File(targetDir, "sense_weight_quant.mnn"),
            fallbackAssetPath = "sense/sense.mnn",
            fallbackDestination = File(targetDir, "sense.mnn"),
        )
        val tokensFile = ensureAssetFile("sense/sense_tokens.txt", File(targetDir, "sense_tokens.txt"))
        val vadFile = ensureAssetFile("silero_vad_int8.mnn", File(context.filesDir, "asr_models/vad/silero_vad_int8.mnn"))

        modelFilePath = modelFile.absolutePath
        tokensFilePath = tokensFile.absolutePath
        vadFilePath = vadFile.absolutePath
    }

    private fun ensurePreferredAssetFile(
        preferredAssetPath: String,
        preferredDestination: File,
        fallbackAssetPath: String,
        fallbackDestination: File,
    ): File {
        existingPreferredAssetFile(preferredDestination, fallbackDestination)?.let { return it }
        return try {
            context.assets.open(preferredAssetPath).close()
            ensureAssetFile(preferredAssetPath, preferredDestination)
        } catch (_: Throwable) {
            ensureAssetFile(fallbackAssetPath, fallbackDestination)
        }
    }

    private fun createRuntimeBundle(): RuntimeBundle {
        val modelPath = requireNotNull(modelFilePath) { "modelFilePath is null" }
        val tokensPath = requireNotNull(tokensFilePath) { "tokensFilePath is null" }
        val vadPath = requireNotNull(vadFilePath) { "vadFilePath is null" }

        val recognizerConfig = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = modelPath,
                    language = "auto",
                    useInverseTextNormalization = true,
                ),
                numThreads = 4,
                provider = "cpu",
                debug = true,
                tokens = tokensPath,
            ),
        )

        synchronized(MnnRuntimeCoordinator.LOCK) {
            val recognizer = OfflineRecognizer(config = recognizerConfig)
            val localVad = Vad(
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = vadPath,
                        threshold = 0.5f,
                        minSilenceDuration = 0.4f,
                        minSpeechDuration = 0.15f,
                        windowSize = 512,
                    ),
                    sampleRate = 16000,
                    numThreads = 2,
                    provider = "cpu",
                    debug = true,
                ),
            )
            return RuntimeBundle(recognizer, localVad)
        }
    }

    private fun rebuildRuntimeLocked(reason: String) {
        if (modelFilePath == null || tokensFilePath == null || vadFilePath == null) {
            Log.w(tag, "Skipping runtime rebuild because model files are unavailable")
            return
        }

        try {
            Log.w(tag, "Rebuilding ASR runtime: $reason")
            val newRuntime = createRuntimeBundle()
            replaceRuntime(newRuntime)
        } catch (t: Throwable) {
            Log.e(tag, "Failed to rebuild ASR runtime", t)
            clearRuntime()
            isInitialized = false
        }
    }

    private fun replaceRuntime(bundle: RuntimeBundle) {
        synchronized(MnnRuntimeCoordinator.LOCK) {
            offlineRecognizer?.release()
            vad?.release()
        }
        offlineRecognizer = bundle.recognizer
        vad = bundle.vad
        decodeCount = 0
        vadCallCount = 0
        vadPendingSamples = FloatArray(0)
        streamingBuffer.clear()
        isInitialized = true
    }

    private fun clearRuntime() {
        synchronized(MnnRuntimeCoordinator.LOCK) {
            offlineRecognizer?.release()
            offlineRecognizer = null
            vad?.release()
            vad = null
        }
        decodeCount = 0
        vadCallCount = 0
        vadPendingSamples = FloatArray(0)
        streamingBuffer.clear()
    }

    private fun snapshotStreamingBuffer(): FloatArray =
        synchronized(decodeLock) {
            streamingBuffer.toFloatArray()
        }

    private fun feedVadAlignedLocked(
        localVad: Vad,
        audioData: FloatArray,
    ): Boolean {
        if (audioData.isEmpty()) {
            return false
        }

        val merged = FloatArray(vadPendingSamples.size + audioData.size)
        System.arraycopy(vadPendingSamples, 0, merged, 0, vadPendingSamples.size)
        System.arraycopy(audioData, 0, merged, vadPendingSamples.size, audioData.size)

        var offset = 0
        var detected = false
        while (offset + VAD_WINDOW_SIZE <= merged.size) {
            val window = FloatArray(VAD_WINDOW_SIZE)
            System.arraycopy(merged, offset, window, 0, VAD_WINDOW_SIZE)
            localVad.acceptWaveform(window)
            detected = detected || localVad.isSpeechDetected()
            offset += VAD_WINDOW_SIZE
            vadCallCount += 1
        }

        vadPendingSamples =
            if (offset < merged.size) {
                merged.copyOfRange(offset, merged.size)
            } else {
                FloatArray(0)
            }

        if (!localVad.empty()) {
            while (!localVad.empty()) {
                localVad.pop()
            }
        }

        if (vadCallCount >= MAX_VAD_CALLS_BEFORE_RESET) {
            localVad.reset()
            vadCallCount = 0
            vadPendingSamples = FloatArray(0)
        }

        return detected
    }

    private fun ensureAssetFile(assetPath: String, destination: File): File {
        destination.parentFile?.mkdirs()

        if (destination.exists() && destination.length() > 0L) {
            return destination
        }

        context.assets.open(assetPath).use { input ->
            destination.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return destination
    }
}

internal fun existingPreferredAssetFile(
    preferredDestination: File,
    fallbackDestination: File,
): File? =
    when {
        preferredDestination.exists() && preferredDestination.length() > 0L -> preferredDestination
        fallbackDestination.exists() && fallbackDestination.length() > 0L -> fallbackDestination
        else -> null
    }

internal data class ExistingAsrModelFiles(
    val modelFile: File,
    val tokensFile: File,
    val vadFile: File,
)

internal fun existingAsrModelFiles(
    appFilesDir: File,
    externalFilesDir: File?,
): ExistingAsrModelFiles? {
    val roots =
        listOfNotNull(
            File(appFilesDir, "asr_models"),
            File(appFilesDir, "models/asr"),
            externalFilesDir?.let { File(it, "models/asr") },
            externalFilesDir?.let { File(it, "asr_models") },
        )
    for (root in roots) {
        val senseDir = File(root, "sense")
        val model =
            existingPreferredAssetFile(
                preferredDestination = File(senseDir, "sense_weight_quant.mnn"),
                fallbackDestination = File(senseDir, "sense.mnn"),
            ) ?: continue
        val tokens = File(senseDir, "sense_tokens.txt").takeIf { it.exists() && it.length() > 0L } ?: continue
        val vad = File(root, "vad/silero_vad_int8.mnn").takeIf { it.exists() && it.length() > 0L } ?: continue
        return ExistingAsrModelFiles(modelFile = model, tokensFile = tokens, vadFile = vad)
    }
    return null
}
