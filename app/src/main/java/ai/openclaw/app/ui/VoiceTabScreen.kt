package ai.openclaw.app.ui

import ai.openclaw.app.MainViewModel
import ai.openclaw.app.voice.LocalMnnAsrStatus
import ai.openclaw.app.voice.VoiceConversationEntry
import ai.openclaw.app.voice.VoiceConversationRole
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.agenew.usbcamera.MouthCameraState
import com.agenew.usbcamera.MouthCameraStatus
import com.agenew.widget.SimpleUVCCameraTextureView

@Composable
fun VoiceTabScreen(viewModel: MainViewModel) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val activity = remember(context) { context.findActivity() }
  val listState = rememberLazyListState()

  val gatewayStatus by viewModel.statusText.collectAsState()
  val micLiveTranscript by viewModel.micLiveTranscript.collectAsState()
  val micQueuedMessages by viewModel.micQueuedMessages.collectAsState()
  val micConversation by viewModel.micConversation.collectAsState()
  val micIsSending by viewModel.micIsSending.collectAsState()
  val voiceInputLabel by viewModel.voiceInputLabel.collectAsState()
  val voiceMnnAsrStatus by viewModel.voiceMnnAsrStatus.collectAsState()
  val mouthAsrReadyText by viewModel.mouthAsrReadyText.collectAsState()
  val mouthCameraState by viewModel.mouthCameraState.collectAsState()

  val hasStreamingAssistant = micConversation.any { it.role == VoiceConversationRole.Assistant && it.isStreaming }
  val showThinkingBubble = micIsSending && !hasStreamingAssistant

  var hasMicPermission by remember { mutableStateOf(context.hasRecordAudioPermission()) }

  val requestMicPermission =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      hasMicPermission = granted
      if (granted) viewModel.setMouthAsrActive(true)
    }

  DisposableEffect(lifecycleOwner, context) {
    val observer =
      LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_RESUME) {
          hasMicPermission = context.hasRecordAudioPermission()
          if (hasMicPermission) viewModel.setMouthAsrActive(true)
        }
      }
    lifecycleOwner.lifecycle.addObserver(observer)
    if (hasMicPermission) {
      viewModel.setMouthAsrActive(true)
    } else {
      requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
      viewModel.setMouthAsrActive(false)
      viewModel.setVoiceScreenActive(false)
    }
  }

  LaunchedEffect(micConversation.size, showThinkingBubble) {
    val total = micConversation.size + if (showThinkingBubble) 1 else 0
    if (total > 0) {
      listState.animateScrollToItem(total - 1)
    }
  }

  Column(
    modifier =
      Modifier
        .fillMaxSize()
        .background(mobileBackgroundGradient)
        .imePadding()
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
        .padding(horizontal = 20.dp, vertical = 14.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.End,
    ) {
      MouthCameraPreview(
        state = mouthCameraState,
        attachPreview = viewModel::attachMouthCameraPreview,
        detachPreview = viewModel::detachMouthCameraPreview,
      )
    }

    LazyColumn(
      state = listState,
      modifier = Modifier.fillMaxWidth().weight(1f),
      contentPadding = PaddingValues(vertical = 4.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      if (micConversation.isEmpty() && !showThinkingBubble) {
        item {
          Box(
            modifier = Modifier.fillParentMaxHeight().fillMaxWidth(),
            contentAlignment = Alignment.Center,
          ) {
            Column(
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = mobileTextTertiary,
              )
              Text(
                if (mouthAsrReadyText == "Ready to speak") "Mouth ASR ready" else mouthAsrReadyText,
                style = mobileHeadline,
                color = mobileTextSecondary,
              )
              Text(
                if (mouthAsrReadyText == "Ready to speak") {
                  "Speak to the camera to send a voice turn."
                } else {
                  "Models are warming up. The mic will start when ready."
                },
                style = mobileCallout,
                color = mobileTextTertiary,
              )
            }
          }
        }
      }

      items(items = micConversation, key = { it.id }) { entry ->
        VoiceTurnBubble(entry = entry)
      }

      if (showThinkingBubble) {
        item {
          VoiceThinkingBubble()
        }
      }
    }

    Column(
      modifier = Modifier.fillMaxWidth(),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      if (!micLiveTranscript.isNullOrBlank()) {
        Surface(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(14.dp),
          color = mobileAccentSoft,
          border = BorderStroke(1.dp, mobileAccent.copy(alpha = 0.2f)),
        ) {
          Text(
            micLiveTranscript!!.trim(),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            style = mobileCallout,
            color = mobileText,
          )
        }
      }

      VoiceInputSelector(
        label = voiceInputLabel,
        mnnStatusText = voiceMnnAsrStatus.label,
        mnnAvailable = voiceMnnAsrStatus != LocalMnnAsrStatus.Unavailable,
      )

      val queueCount = micQueuedMessages.size
      val stateText =
        when {
          queueCount > 0 -> "$queueCount queued"
          micIsSending -> "Sending"
          !hasMicPermission -> "Mic permission required"
          mouthAsrReadyText != "Ready to speak" -> mouthAsrReadyText
          mouthCameraState.isSpeaking -> "Mouth speaking"
          else -> mouthCameraState.message
        }
      val stateColor =
        when {
          micIsSending -> mobileAccent
          mouthAsrReadyText != "Ready to speak" -> mobileWarning
          mouthCameraState.isSpeaking -> mobileSuccess
          mouthCameraState.status == MouthCameraStatus.NoCamera -> mobileWarning
          mouthCameraState.status == MouthCameraStatus.NoFace -> mobileWarning
          else -> mobileTextSecondary
        }
      Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (mouthCameraState.isSpeaking) mobileSuccessSoft else mobileSurface,
        border = BorderStroke(1.dp, if (mouthCameraState.isSpeaking) mobileSuccess.copy(alpha = 0.3f) else mobileBorder),
      ) {
        Text(
          "$gatewayStatus · $stateText",
          style = mobileCallout.copy(fontWeight = FontWeight.SemiBold),
          color = stateColor,
          modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )
      }

      if (!hasMicPermission) {
        val showRationale =
          if (activity == null) {
            false
          } else {
            ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)
          }
        Text(
          if (showRationale) {
            "Microphone permission is required for voice mode."
          } else {
            "Microphone blocked. Open app settings to enable it."
          },
          style = mobileCaption1,
          color = mobileWarning,
          textAlign = TextAlign.Center,
        )
        Button(
          onClick = { openAppSettings(context) },
          shape = RoundedCornerShape(12.dp),
          colors = ButtonDefaults.buttonColors(containerColor = mobileSurfaceStrong, contentColor = mobileText),
        ) {
          Text("Open settings", style = mobileCallout.copy(fontWeight = FontWeight.SemiBold))
        }
      }
    }
  }
}

@Composable
private fun VoiceInputSelector(
  label: String,
  mnnStatusText: String,
  mnnAvailable: Boolean,
) {
  Column(
    modifier = Modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(6.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.Center,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Surface(
        shape = RoundedCornerShape(999.dp),
        color = mobileSurface,
        border = BorderStroke(1.dp, mobileBorder),
      ) {
        Text(
          "$label · $mnnStatusText",
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
          style = mobileCaption1.copy(fontWeight = FontWeight.SemiBold),
          color = if (mnnAvailable) mobileTextSecondary else mobileWarning,
        )
      }
    }
  }
}

@Composable
private fun MouthCameraPreview(
  state: MouthCameraState,
  attachPreview: (SimpleUVCCameraTextureView) -> Unit,
  detachPreview: (SimpleUVCCameraTextureView) -> Unit,
) {
  val borderColor =
    when (state.status) {
      MouthCameraStatus.Speaking -> mobileSuccess
      MouthCameraStatus.NoCamera,
      MouthCameraStatus.NoFace,
      -> mobileWarning
      else -> mobileBorderStrong
    }
  Surface(
    modifier = Modifier.size(width = 132.dp, height = 96.dp),
    shape = RoundedCornerShape(8.dp),
    color = Color.Black,
    border = BorderStroke(1.dp, borderColor.copy(alpha = 0.75f)),
  ) {
    Box(modifier = Modifier.fillMaxSize()) {
      AndroidView(
        factory = { context ->
          SimpleUVCCameraTextureView(context).also(attachPreview)
        },
        modifier = Modifier.fillMaxSize(),
        onRelease = detachPreview,
      )
      MouthDetectionOverlay(state = state)
      Surface(
        modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
        shape = RoundedCornerShape(999.dp),
        color = Color.Black.copy(alpha = 0.55f),
      ) {
        Text(
          state.message,
          modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
          style = mobileCaption2.copy(fontWeight = FontWeight.SemiBold),
          color = Color.White,
          maxLines = 1,
        )
      }
    }
  }
}

@Composable
private fun MouthDetectionOverlay(state: MouthCameraState) {
  val faceColor = if (state.isSpeaking) mobileSuccess else Color.Yellow
  Canvas(modifier = Modifier.fillMaxSize()) {
    val faceBox = state.faceBox ?: return@Canvas
    val left = faceBox.left * size.width
    val top = faceBox.top * size.height
    val right = faceBox.right * size.width
    val bottom = faceBox.bottom * size.height
    drawRect(
      color = faceColor,
      topLeft = Offset(left, top),
      size = Size(maxOf(1f, right - left), maxOf(1f, bottom - top)),
      style = Stroke(width = 2.dp.toPx()),
    )

    val points = state.mouthPoints
    if (points.size < 12) return@Canvas
    for (index in points.indices step 4) {
      if (index + 3 >= points.size) break
      val x1 = points[index] * size.width
      val y1 = points[index + 1] * size.height
      val x2 = points[index + 2] * size.width
      val y2 = points[index + 3] * size.height
      drawLine(
        color = Color.Cyan,
        start = Offset(x1, y1),
        end = Offset(x2, y2),
        strokeWidth = 2.dp.toPx(),
      )
      drawCircle(color = Color.Cyan, radius = 3.dp.toPx(), center = Offset(x1, y1))
      drawCircle(color = Color.Cyan, radius = 3.dp.toPx(), center = Offset(x2, y2))
    }
  }
}

@Composable
private fun VoiceTurnBubble(entry: VoiceConversationEntry) {
  val isUser = entry.role == VoiceConversationRole.User
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
  ) {
    Surface(
      modifier = Modifier.fillMaxWidth(0.90f),
      shape = RoundedCornerShape(12.dp),
      color = if (isUser) mobileAccentSoft else mobileCardSurface,
      border = BorderStroke(1.dp, if (isUser) mobileAccent else mobileBorderStrong),
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
      ) {
        Text(
          if (isUser) "You" else "OpenClaw",
          style = mobileCaption2.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp),
          color = if (isUser) mobileAccent else mobileTextSecondary,
        )
        Text(
          if (entry.isStreaming && entry.text.isBlank()) "Listening response…" else entry.text,
          style = mobileCallout,
          color = mobileText,
        )
      }
    }
  }
}

@Composable
private fun VoiceThinkingBubble() {
  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
    Surface(
      modifier = Modifier.fillMaxWidth(0.68f),
      shape = RoundedCornerShape(12.dp),
      color = mobileCardSurface,
      border = BorderStroke(1.dp, mobileBorderStrong),
    ) {
      Row(
        modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        ThinkingDots(color = mobileTextSecondary)
        Text("OpenClaw is thinking…", style = mobileCallout, color = mobileTextSecondary)
      }
    }
  }
}

@Composable
private fun ThinkingDots(color: Color) {
  Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
    ThinkingDot(alpha = 0.38f, color = color)
    ThinkingDot(alpha = 0.62f, color = color)
    ThinkingDot(alpha = 0.90f, color = color)
  }
}

@Composable
private fun ThinkingDot(
  alpha: Float,
  color: Color,
) {
  Surface(
    modifier = Modifier.size(6.dp).alpha(alpha),
    shape = CircleShape,
    color = color,
  ) {}
}

private fun Context.hasRecordAudioPermission(): Boolean =
  (
    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
      PackageManager.PERMISSION_GRANTED
  )

private fun Context.findActivity(): Activity? =
  when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
  }

private fun openAppSettings(context: Context) {
  val intent =
    Intent(
      Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
      Uri.fromParts("package", context.packageName, null),
    )
  context.startActivity(intent)
}
