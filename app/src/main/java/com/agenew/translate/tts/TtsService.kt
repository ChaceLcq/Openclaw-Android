package com.agenew.translate.tts

import android.content.Context
import android.util.Log
import com.agenew.translate.mnn.MnnRuntimeCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class TtsService {
  val isLoaded: Boolean
    get() = loaded

  @Volatile private var loaded = false
  @Volatile private var nativePtr: Long = 0L
  @Volatile private var lastErrorMessage: String? = null

  init {
    loadNativeLibraries()
  }

  suspend fun init(
    context: Context,
    backendType: TtsBackendType = TtsBackendType.BERT_VITS2,
  ): Boolean =
    withContext(Dispatchers.IO) {
      if (loaded && nativePtr != 0L) return@withContext true
      val appContext = context.applicationContext
      val modelDir = prepareBundledModel(appContext, backendType)
      if (modelDir == null) {
        lastErrorMessage = "No ${backendType.displayName} model assets found"
        return@withContext false
      }
      recreateNativeService()
      val ok =
        synchronized(MnnRuntimeCoordinator.LOCK) {
          nativeLoadResourcesFromFile(
            nativePtr,
            modelDir.absolutePath,
            "",
            "",
            JSONObject(emptyMap<String, String>()).toString(),
          )
        }
      loaded = ok
      if (!ok) {
        lastErrorMessage = "nativeLoadResourcesFromFile failed for ${backendType.displayName}"
      }
      ok
    }

  fun getLastErrorMessage(): String? = lastErrorMessage

  fun getCurrentSampleRate(): Int {
    return DEFAULT_SAMPLE_RATE
  }

  fun process(text: String): ShortArray {
    val spoken = text.trim()
    if (!loaded || nativePtr == 0L || spoken.isEmpty()) return ShortArray(0)
    return try {
      synchronized(MnnRuntimeCoordinator.LOCK) {
        nativeProcess(nativePtr, spoken, 0)
      }
    } catch (err: Throwable) {
      lastErrorMessage = err.message ?: err::class.simpleName
      Log.w(TAG, "MNN TTS processing failed", err)
      ShortArray(0)
    }
  }

  fun destroy() {
    loaded = false
    if (nativePtr == 0L) return
    try {
      synchronized(MnnRuntimeCoordinator.LOCK) {
        nativeDestroy(nativePtr)
      }
    } catch (err: Throwable) {
      Log.w(TAG, "MNN TTS destroy failed", err)
    } finally {
      nativePtr = 0L
    }
  }

  private fun recreateNativeService() {
    destroy()
    nativePtr = nativeCreateTTS("zh")
  }

  private fun prepareBundledModel(
    context: Context,
    backendType: TtsBackendType,
  ): File? {
    val targetRoot = File(context.filesDir, "tts_models")
    targetRoot.mkdirs()
    for (folderName in backendType.assetFolderCandidates) {
      val hasConfig =
        runCatching {
          context.assets.open("$folderName/config.json").close()
        }.isSuccess
      if (!hasConfig) continue
      val targetDir = File(targetRoot, folderName)
      copyAssetFolder(context, folderName, targetDir)
      return targetDir
    }
    return null
  }

  private fun copyAssetFolder(
    context: Context,
    assetPath: String,
    targetDir: File,
  ) {
    targetDir.mkdirs()
    val files = context.assets.list(assetPath).orEmpty()
    for (name in files) {
      val childAssetPath = "$assetPath/$name"
      val childTarget = File(targetDir, name)
      val children = context.assets.list(childAssetPath).orEmpty()
      if (children.isNotEmpty()) {
        copyAssetFolder(context, childAssetPath, childTarget)
      } else if (!childTarget.exists() || childTarget.length() == 0L) {
        childTarget.parentFile?.mkdirs()
        context.assets.open(childAssetPath).use { input ->
          childTarget.outputStream().use { output -> input.copyTo(output) }
        }
      }
    }
  }

  private fun loadNativeLibraries() {
    runCatching { System.loadLibrary("c++_shared") }
      .onFailure { err -> Log.d(TAG, "c++_shared not loaded explicitly: ${err.message}") }
    try {
      System.loadLibrary("mnn_tts_SDK")
      Log.d(TAG, "Successfully loaded mnn_tts_SDK library")
    } catch (err: UnsatisfiedLinkError) {
      Log.e(TAG, "Could not load mnn_tts_SDK library: ${err.message}")
      throw err
    }
    try {
      System.loadLibrary("taoavatar")
      Log.d(TAG, "Successfully loaded taoavatar library")
    } catch (err: UnsatisfiedLinkError) {
      Log.e(TAG, "Could not load taoavatar library: ${err.message}")
      throw err
    }
  }

  private external fun nativeCreateTTS(language: String): Long
  private external fun nativeDestroy(nativePtr: Long)
  private external fun nativeLoadResourcesFromFile(
    nativePtr: Long,
    resourceDir: String,
    modelName: String,
    mmapDir: String,
    paramsJson: String,
  ): Boolean
  private external fun nativeProcess(
    nativePtr: Long,
    text: String,
    id: Int,
  ): ShortArray

  companion object {
    private const val TAG = "TtsService"
    private const val DEFAULT_SAMPLE_RATE = 44_100
  }
}
