package ai.openclaw.app.voice

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VoiceInputSelectionMode(
  val rawValue: String,
) {
  AutoUsb("auto_usb"),
  Default("default"),
  Device("device"),
  ;

  companion object {
    fun fromRawValue(value: String?): VoiceInputSelectionMode =
      entries.firstOrNull { it.rawValue == value?.trim() } ?: AutoUsb
  }
}

data class VoiceInputDevice(
  val key: String,
  val label: String,
  val isUsb: Boolean,
)

data class VoiceInputSelection(
  val mode: VoiceInputSelectionMode,
  val deviceKey: String? = null,
)

class VoiceAudioInputManager(
  context: Context,
  initialSelection: VoiceInputSelection,
  private val persistSelection: (VoiceInputSelection) -> Unit,
) {
  private val appContext = context.applicationContext
  private val audioManager = appContext.getSystemService(AudioManager::class.java)
  private val callbackHandler = Handler(Looper.getMainLooper())

  private val _devices = MutableStateFlow<List<VoiceInputDevice>>(emptyList())
  val devices: StateFlow<List<VoiceInputDevice>> = _devices.asStateFlow()

  private val _selection = MutableStateFlow(initialSelection)
  val selection: StateFlow<VoiceInputSelection> = _selection.asStateFlow()

  private val _activeInputLabel = MutableStateFlow("Mic: Default")
  val activeInputLabel: StateFlow<String> = _activeInputLabel.asStateFlow()

  private val deviceCallback =
    object : AudioDeviceCallback() {
      override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
        refreshDevices()
      }

      override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
        refreshDevices()
      }
    }

  init {
    refreshDevices()
    audioManager.registerAudioDeviceCallback(deviceCallback, callbackHandler)
  }

  fun setSelection(selection: VoiceInputSelection) {
    _selection.value = selection
    persistSelection(selection)
    refreshDevices()
  }

  fun refreshDevices() {
    val inputs =
      audioManager
        .getDevices(AudioManager.GET_DEVICES_INPUTS)
        .filter { it.isSource }
        .map { it.toVoiceInputDevice() }
        .distinctBy { it.key }
        .sortedWith(compareByDescending<VoiceInputDevice> { it.isUsb }.thenBy { it.label.lowercase() })
    _devices.value = inputs
    _activeInputLabel.value = "Mic: ${resolveDeviceLabel(resolveAudioDevice())}"
  }

  fun resolveAudioDevice(): AudioDeviceInfo? {
    val inputs =
      audioManager
        .getDevices(AudioManager.GET_DEVICES_INPUTS)
        .filter { it.isSource }
    val selected = _selection.value
    val resolved =
      when (selected.mode) {
        VoiceInputSelectionMode.Default -> null
        VoiceInputSelectionMode.Device ->
          inputs.firstOrNull { it.stableKey() == selected.deviceKey }
            ?.takeUnless { it.looksLikeCameraInput() }
            ?: inputs.bestVoiceInput()
        VoiceInputSelectionMode.AutoUsb -> inputs.bestVoiceInput()
      }
    Log.d(
      TAG,
      "resolve input mode=${selected.mode.rawValue} device=${resolveDeviceLabel(resolved)} candidates=${inputs.joinToString { it.debugLabel() }}",
    )
    return resolved
  }

  fun updateActiveDevice(device: AudioDeviceInfo?) {
    _activeInputLabel.value = "Mic: ${resolveDeviceLabel(device)}"
  }

  fun deviceLabel(device: AudioDeviceInfo?): String = resolveDeviceLabel(device)

  fun close() {
    runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
  }

  private fun resolveDeviceLabel(device: AudioDeviceInfo?): String {
    if (device == null) return "Default"
    val productName = device.productName?.toString()?.trim().orEmpty()
    val base = productName.ifEmpty { "Input ${device.id}" }
    return if (device.isUsbInput()) "$base USB" else base
  }
}

private fun AudioDeviceInfo.toVoiceInputDevice(): VoiceInputDevice =
  VoiceInputDevice(
    key = stableKey(),
    label =
      productName
        ?.toString()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: "Input $id",
    isUsb = isUsbInput(),
  )

private const val TAG = "VoiceAudioInput"

private fun AudioDeviceInfo.stableKey(): String =
  listOf(type.toString(), productName?.toString().orEmpty(), address.orEmpty())
    .joinToString("|")

private fun AudioDeviceInfo.isUsbInput(): Boolean =
  type == AudioDeviceInfo.TYPE_USB_DEVICE ||
    type == AudioDeviceInfo.TYPE_USB_HEADSET

private fun List<AudioDeviceInfo>.bestUsbVoiceInput(): AudioDeviceInfo? =
  filter { it.isUsbInput() && !it.looksLikeCameraInput() }
    .minWithOrNull(
      compareBy<AudioDeviceInfo> { it.voiceInputPriority() }
        .thenBy { it.productName?.toString()?.lowercase().orEmpty() }
        .thenBy { it.address.orEmpty() },
    )

private fun List<AudioDeviceInfo>.bestVoiceInput(): AudioDeviceInfo? =
  bestUsbVoiceInput()
    ?: filter { !it.isUsbInput() && !it.looksLikeCameraInput() }
      .minWithOrNull(
        compareBy<AudioDeviceInfo> { it.voiceInputPriority() }
          .thenBy { it.productName?.toString()?.lowercase().orEmpty() }
          .thenBy { it.address.orEmpty() },
      )

private fun AudioDeviceInfo.voiceInputPriority(): Int {
  val name = productName?.toString()?.trim()?.lowercase().orEmpty()
  return when {
    name.contains("q5") -> 0
    type == AudioDeviceInfo.TYPE_BUILTIN_MIC -> 1
    type == AudioDeviceInfo.TYPE_USB_HEADSET -> 1
    else -> 2
  }
}

private fun AudioDeviceInfo.debugLabel(): String =
  "${productName?.toString()?.trim().orEmpty()} type=$type addr=${address.orEmpty()} priority=${voiceInputPriority()} camera=${looksLikeCameraInput()}"

private fun AudioDeviceInfo.looksLikeCameraInput(): Boolean {
  val name = productName?.toString()?.trim()?.lowercase().orEmpty()
  return name.contains("camera") || name.contains("cam")
}
