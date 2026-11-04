package com.saad.omnichat.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.VideocamOff
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.saad.omnichat.ui.theme.LocalOmni
import com.saad.omnichat.ui.theme.glass
import com.saad.omnichat.ui.voiceglow.VoiceGlow
import com.saad.omnichat.ui.voiceglow.VoiceGlowType

/**
 * Talk to the model and hear it answer in its own voice. One button: tap to talk, tap to send; while it thinks or
 * speaks, tap to interrupt and talk again. A photo can ride along with the next thing you say.
 */
@Composable
fun VoiceScreen(vm: ChatViewModel, onBack: () -> Unit) {
  val state by vm.state.collectAsStateWithLifecycle()
  val voice = state.voice
  val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { vm.voicePhoto(it) }
  val context = androidx.compose.ui.platform.LocalContext.current
  val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) vm.voiceTap() }
  val tap = {
    if (voice.phase == VoicePhase.IDLE && !hasMic(context)) micPermission.launch(android.Manifest.permission.RECORD_AUDIO) else vm.voiceTap()
  }
  DisposableEffect(Unit) { onDispose { vm.voiceClose() } }
  val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) vm.voiceCamera(true) }
  val toggleCamera = {
    when {
      voice.camera -> vm.voiceCamera(false)
      hasCamera(context) -> vm.voiceCamera(true)
      else -> cameraPermission.launch(android.Manifest.permission.CAMERA)
    }
  }

  VoiceGlow(
    level = vm::voiceLevel,
    active = voice.phase == VoicePhase.LISTENING || voice.phase == VoicePhase.SPEAKING,
    processing = voice.phase == VoicePhase.THINKING,
    modifier = Modifier.fillMaxSize(),
    dark = LocalOmni.current.dark,
    type = VoiceGlowType.Mobile,
  ) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp)) {
      Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to chat") }
        Column(Modifier.weight(1f)) {
          Text("Voice chat", style = MaterialTheme.typography.titleMedium)
          Text(
            "${VoiceLanguages[state.settings.voiceLang] ?: "English"} · ${state.settings.voiceGender} voice",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          if (voice.camera) {
            // what the model will see: the frame current when you finish asking goes with your question
            Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f, matchHeightConstraintsFirst = true).weight(1f, fill = false).clip(MaterialTheme.shapes.extraLarge)) {
              VoiceCamera(voice.frontCamera, vm::cameraFrame, Modifier.fillMaxSize())
              IconButton(
                onClick = vm::voiceFlipCamera,
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f)),
              ) {
                Icon(Icons.Outlined.Cameraswitch, "Switch camera", tint = androidx.compose.ui.graphics.Color.White)
              }
            }
            Spacer(Modifier.height(14.dp))
          }
          if (!voice.camera) ThinkingOrb(
            when (voice.phase) {
              VoicePhase.LISTENING -> OrbMode.LISTENING
              VoicePhase.THINKING -> OrbMode.THINKING
              VoicePhase.SPEAKING -> OrbMode.WRITING
              VoicePhase.IDLE -> OrbMode.IDLE
            },
            Modifier.size(190.dp),
          )
          Spacer(Modifier.height(20.dp))
          val status =
            when {
              state.phase !is Phase.Ready -> "The model is loading…"
              voice.phase == VoicePhase.IDLE && voice.camera -> "Show it something and tap the microphone: then just talk, it answers and listens again"
              voice.phase == VoicePhase.IDLE -> "Tap the microphone and talk"
              voice.phase == VoicePhase.LISTENING && voice.camera && voice.heard -> "Hearing you… it answers when you pause"
              voice.phase == VoicePhase.LISTENING && voice.camera -> "Listening… ask about what you're showing (tap to pause)"
              voice.phase == VoicePhase.LISTENING -> "Listening… tap again when you're done"
              voice.phase == VoicePhase.THINKING -> "Thinking…"
              else -> "Speaking · tap to interrupt"
            }
          AnimatedContent(status, transitionSpec = { fadeIn(tween(180, easing = ExpoOut)) togetherWith fadeOut(tween(100)) }, label = "voicePhase") { text ->
            Text(
              text,
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              textAlign = TextAlign.Center,
            )
          }
          val speed = voice.speed
          if (state.settings.voiceSpeed && speed != null) {
            Spacer(Modifier.height(6.dp))
            Text(
              speedLine(speed),
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
              textAlign = TextAlign.Center,
            )
          }
          if (voice.reply.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            val scroll = rememberScrollState()
            LaunchedEffect(voice.reply.length) { scroll.animateScrollTo(scroll.maxValue) }
            Text(
              voice.reply.trim(),
              style = MaterialTheme.typography.bodyLarge,
              textAlign = TextAlign.Center,
              modifier = Modifier.heightIn(max = 180.dp).verticalScroll(scroll),
            )
          }
        }
      }

      Row(Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        // a photo to talk about, sent with the next thing you say
        Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
          val photo = voice.photo
          if (voice.camera) {
            // the camera stands in for a picked photo
          } else if (photo != null) {
            AsyncImage(photo.uri, photo.label, contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp).clip(CircleShape))
            IconButton(onClick = vm::voiceClearPhoto, modifier = Modifier.align(Alignment.TopEnd).size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.inverseSurface)) {
              Icon(Icons.Default.Close, "Remove photo", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.inverseOnSurface)
            }
          } else {
            IconButton(
              onClick = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
              enabled = voice.phase == VoicePhase.IDLE || voice.phase == VoicePhase.LISTENING,
              modifier = Modifier.size(56.dp).glass(CircleShape, lift = 6.dp),
            ) {
              Icon(Icons.Outlined.AddPhotoAlternate, "Add a photo to talk about")
            }
          }
        }

        FilledIconButton(
          onClick = tap,
          enabled = state.phase is Phase.Ready,
          modifier = Modifier.size(84.dp).shadow(14.dp, CircleShape, clip = false),
        ) {
          AnimatedContent(voice.phase, transitionSpec = { (fadeIn(tween(150, easing = ExpoOut)) + scaleIn(tween(150, easing = ExpoOut), 0.8f)) togetherWith fadeOut(tween(100)) }, label = "voiceButton") { p ->
            Icon(
              when (p) {
                VoicePhase.IDLE -> Icons.Default.Mic
                VoicePhase.LISTENING -> if (voice.camera && !voice.heard) Icons.Default.Pause else Icons.Default.ArrowUpward
                else -> Icons.Default.Stop
              },
              when (p) {
                VoicePhase.IDLE -> "Talk"
                VoicePhase.LISTENING -> if (voice.camera && !voice.heard) "Pause the conversation" else "Send what you said"
                else -> "Interrupt"
              },
              Modifier.size(34.dp),
            )
          }
        }

        // the live camera: show the model what you're asking about
        Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
          IconButton(onClick = toggleCamera, modifier = Modifier.size(56.dp).glass(CircleShape, lift = 6.dp)) {
            Icon(if (voice.camera) Icons.Outlined.VideocamOff else Icons.Outlined.Videocam, if (voice.camera) "Turn the camera off" else "Show the model with the camera")
          }
        }
      }
    }
  }
}

internal fun hasCamera(context: android.content.Context) =
  androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED

internal fun hasMic(context: android.content.Context) =
  androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED

/** "Read 899 tokens in 2.1 s · 14.2 tok/s · voice 2.3× real time" */
internal fun speedLine(v: com.saad.omnichat.ui.chat.VoiceSpeed): String =
  listOfNotNull(
    "Read ${v.promptTokens} tokens in %.1f s".format(v.readMillis / 1000f),
    v.tokensPerSecond?.let { "%.1f tok/s".format(it) },
    v.realTime?.let { (if (it < 1f) "voice %.2f× real time" else "voice %.1f× real time").format(it) },
  ).joinToString(" · ")
