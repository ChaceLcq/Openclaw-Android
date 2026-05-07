package com.agenew.usbcamera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.TextureView
import com.agenew.usb.IFrameCallback
import com.agenew.usb.USBMonitor
import com.agenew.usb.USBMonitor.OnDeviceConnectListener
import com.agenew.usb.USBMonitor.UsbControlBlock
import com.agenew.usb.UVCCamera
import com.agenew.widget.SimpleUVCCameraTextureView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

enum class MouthCameraStatus {
  NoCamera,
  NoFace,
  Collecting,
  Silent,
  Speaking,
}

data class NormalizedMouthRect(
  val left: Float,
  val top: Float,
  val right: Float,
  val bottom: Float,
)

data class MouthCameraState(
  val status: MouthCameraStatus = MouthCameraStatus.NoCamera,
  val message: String = "USB camera idle",
  val mouthGap: Float = 0f,
  val speakingProb: Float = 0f,
  val rawSpeaking: Boolean = false,
  val historyCount: Int = 0,
  val windowSize: Int = 0,
  val faceBox: NormalizedMouthRect? = null,
  val mouthPoints: List<Float> = emptyList(),
) {
  val isSpeaking: Boolean = status == MouthCameraStatus.Speaking
}

class UsbMouthCameraManager(
  context: Context,
) {
  private val appContext = context.applicationContext
  private val mainHandler = Handler(Looper.getMainLooper())
  private val detectorExecutor = Executors.newSingleThreadExecutor()
  private val isProcessing = AtomicBoolean(false)
  private val monitor = USBMonitor(appContext, DeviceListener())
  private val detector: SpeakingDetector by lazy { SpeakingDetector(appContext) }

  private val _state = MutableStateFlow(MouthCameraState())
  val state: StateFlow<MouthCameraState> = _state.asStateFlow()

  @Volatile
  private var active = false

  @Volatile
  private var lastProcessMs = 0L
  private var camera: UVCCamera? = null
  private var previewView: SimpleUVCCameraTextureView? = null
  private var previewSurface: Surface? = null
  private var connectedDevice: UsbDevice? = null
  private var pendingPermissionDevice: UsbDevice? = null

  fun attachPreview(view: SimpleUVCCameraTextureView) {
    previewView = view
    view.setAspectRatio(PREVIEW_WIDTH.toDouble() / PREVIEW_HEIGHT.toDouble())
    view.surfaceTextureListener =
      object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(
          surface: SurfaceTexture,
          width: Int,
          height: Int,
        ) {
          startPreviewIfReady()
        }

        override fun onSurfaceTextureSizeChanged(
          surface: SurfaceTexture,
          width: Int,
          height: Int,
        ) = Unit

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
          stopPreviewSurface()
          return true
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
      }
    if (view.isAvailable) startPreviewIfReady()
  }

  fun detachPreview(view: SimpleUVCCameraTextureView) {
    if (previewView !== view) return
    view.surfaceTextureListener = null
    previewView = null
    stopPreviewSurface()
  }

  fun start() {
    if (active) return
    active = true
    _state.value = MouthCameraState(message = "Looking for USB camera")
    runCatching { monitor.register() }
      .onFailure { err -> publishError("USB monitor failed: ${err.message ?: err::class.simpleName}") }
    mainHandler.postDelayed({ requestFirstCamera() }, 350L)
  }

  fun stop() {
    active = false
    releaseCamera()
    runCatching { monitor.unregister() }
    _state.value = MouthCameraState(message = "USB camera idle")
  }

  fun close() {
    stop()
    runCatching { detector.close() }
    runCatching { monitor.destroy() }
    detectorExecutor.shutdownNow()
  }

  private fun requestFirstCamera() {
    if (!active) return
    val device = runCatching { monitor.deviceList.firstOrNull(::isUvcCamera) }.getOrNull()
    if (device == null) {
      _state.value = MouthCameraState(message = "No USB camera")
      return
    }
    val alreadyAllowed = runCatching { monitor.hasPermission(device) }.getOrDefault(false)
    if (!alreadyAllowed) pendingPermissionDevice = device
    val failed = runCatching { monitor.requestPermission(device) }.getOrDefault(true)
    if (failed) {
      pendingPermissionDevice = null
      publishError("USB camera permission request failed")
    } else {
      _state.value = MouthCameraState(message = "Requesting USB camera")
    }
  }

  private fun openCamera(ctrlBlock: UsbControlBlock) {
    releaseCamera()
    val nextCamera =
      try {
        UVCCamera().apply {
          open(ctrlBlock)
          try {
            setPreviewSize(
              PREVIEW_WIDTH,
              PREVIEW_HEIGHT,
              CAMERA_FPS,
              CAMERA_FPS,
              UVCCamera.FRAME_FORMAT_MJPEG,
              UVCCamera.DEFAULT_BANDWIDTH,
            )
          } catch (_: IllegalArgumentException) {
            setPreviewSize(
              PREVIEW_WIDTH,
              PREVIEW_HEIGHT,
              CAMERA_FPS,
              CAMERA_FPS,
              UVCCamera.DEFAULT_PREVIEW_MODE,
              UVCCamera.DEFAULT_BANDWIDTH,
            )
          }
        }
      } catch (err: Throwable) {
        publishError("USB camera open failed: ${err.message ?: err::class.simpleName}")
        return
      }
    camera = nextCamera
    _state.value = MouthCameraState(message = "Detecting face")
    startPreviewIfReady()
  }

  private fun startPreviewIfReady() {
    val view = previewView ?: return
    val activeCamera = camera ?: return
    val texture = view.surfaceTexture ?: return
    stopPreviewSurface()
    previewSurface = Surface(texture)
    try {
      activeCamera.setPreviewDisplay(previewSurface)
      activeCamera.setFrameCallback(frameCallback, UVCCamera.PIXEL_FORMAT_NV21)
      activeCamera.startPreview()
    } catch (err: Throwable) {
      publishError("USB preview failed: ${err.message ?: err::class.simpleName}")
    }
  }

  private fun stopPreviewSurface() {
    runCatching { camera?.stopPreview() }
    runCatching { previewSurface?.release() }
    previewSurface = null
  }

  private fun releaseCamera() {
    runCatching {
      camera?.setFrameCallback(null, 0)
      camera?.stopPreview()
      camera?.close()
      camera?.destroy()
    }
    camera = null
    runCatching { previewSurface?.release() }
    previewSurface = null
    isProcessing.set(false)
  }

  private val frameCallback =
    IFrameCallback { frame ->
      if (!active) return@IFrameCallback
      val now = SystemClock.elapsedRealtime()
      if (now - lastProcessMs < FRAME_INTERVAL_MS) return@IFrameCallback
      if (!isProcessing.compareAndSet(false, true)) return@IFrameCallback
      lastProcessMs = now
      val bytes = ByteArray(frame.remaining())
      frame.get(bytes)
      frame.rewind()
      detectorExecutor.execute {
        try {
          processFrame(bytes)
        } catch (err: Throwable) {
          Log.w(TAG, "mouth frame failed", err)
        } finally {
          isProcessing.set(false)
        }
      }
    }

  private fun processFrame(nv21Bytes: ByteArray) {
    val bitmap = nv21ToBitmap(nv21Bytes, PREVIEW_WIDTH, PREVIEW_HEIGHT) ?: return
    try {
      publishDetection(detector.detect(bitmap))
    } finally {
      bitmap.recycle()
    }
  }

  private fun publishDetection(detection: SpeakingDetector.Detection) {
    val status =
      when {
        !detection.hasFace -> MouthCameraStatus.NoFace
        detection.isSpeaking() -> MouthCameraStatus.Speaking
        !detection.ready -> MouthCameraStatus.Collecting
        else -> MouthCameraStatus.Silent
      }
    val message =
      when (status) {
        MouthCameraStatus.NoCamera -> "No USB camera"
        MouthCameraStatus.NoFace -> "No face"
        MouthCameraStatus.Collecting -> "Collecting ${detection.historyCount}/${detection.windowSize}"
        MouthCameraStatus.Silent -> "Mouth closed"
        MouthCameraStatus.Speaking -> "Speaking"
      }
    _state.value =
      MouthCameraState(
        status = status,
        message = message,
        mouthGap = detection.mouthGap,
        speakingProb = detection.speakingProb,
        rawSpeaking = detection.rawSpeaking,
        historyCount = detection.historyCount,
        windowSize = detection.windowSize,
        faceBox =
          detection.faceBox?.let { box ->
            NormalizedMouthRect(
              left = box.left,
              top = box.top,
              right = box.right,
              bottom = box.bottom,
            )
          },
        mouthPoints = detection.mouthPoints?.toList().orEmpty(),
      )
  }

  private fun publishError(message: String) {
    Log.w(TAG, message)
    _state.value = MouthCameraState(message = message)
  }

  private inner class DeviceListener : OnDeviceConnectListener {
    override fun onAttach(device: UsbDevice) {
      val canRetry =
        pendingPermissionDevice == null ||
          runCatching { monitor.hasPermission(device) }.getOrDefault(false)
      if (active && connectedDevice == null && isUvcCamera(device) && canRetry) {
        mainHandler.post { requestFirstCamera() }
      }
    }

    override fun onDettach(device: UsbDevice) {
      if (device == pendingPermissionDevice) pendingPermissionDevice = null
      if (device == connectedDevice) {
        connectedDevice = null
        releaseCamera()
        _state.value = MouthCameraState(message = "USB camera detached")
      }
    }

    override fun onConnect(
      device: UsbDevice,
      ctrlBlock: UsbControlBlock,
      createNew: Boolean,
    ) {
      if (!isUvcCamera(device)) {
        runCatching { ctrlBlock.close() }
        return
      }
      pendingPermissionDevice = null
      connectedDevice = device
      mainHandler.post { openCamera(ctrlBlock) }
    }

    override fun onDisconnect(
      device: UsbDevice,
      ctrlBlock: UsbControlBlock,
    ) {
      if (device == connectedDevice) {
        connectedDevice = null
        pendingPermissionDevice = null
        releaseCamera()
        _state.value = MouthCameraState(message = "USB camera disconnected")
      }
    }

    override fun onCancel(device: UsbDevice) {
      if (device == pendingPermissionDevice) pendingPermissionDevice = null
      publishError("USB camera permission denied")
    }
  }

  companion object {
    private const val TAG = "UsbMouthCamera"
    private const val PREVIEW_WIDTH = UVCCamera.DEFAULT_PREVIEW_WIDTH
    private const val PREVIEW_HEIGHT = UVCCamera.DEFAULT_PREVIEW_HEIGHT
    private const val CAMERA_FPS = 30
    private const val FRAME_INTERVAL_MS = 33L

    private fun isUvcCamera(device: UsbDevice): Boolean {
      if (device.deviceClass == UsbConstants.USB_CLASS_VIDEO) return true
      for (index in 0 until device.interfaceCount) {
        if (device.getInterface(index).interfaceClass == UsbConstants.USB_CLASS_VIDEO) return true
      }
      return false
    }

    private fun nv21ToBitmap(
      nv21Bytes: ByteArray,
      width: Int,
      height: Int,
    ): Bitmap? =
      try {
        val stream = ByteArrayOutputStream()
        val yuvImage = YuvImage(nv21Bytes, ImageFormat.NV21, width, height, null)
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 80, stream)
        BitmapFactory.decodeByteArray(stream.toByteArray(), 0, stream.size())
      } catch (err: Throwable) {
        Log.w(TAG, "NV21 conversion failed", err)
        null
      }
  }
}

private val USBMonitor.deviceList: List<UsbDevice>
  get() = getDeviceList()
