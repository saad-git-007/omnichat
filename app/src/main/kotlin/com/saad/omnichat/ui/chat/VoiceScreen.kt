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
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AddPhotoAlternate
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
          ThinkingOrb(
            when (voice.phase) {
              VoicePhase.LISTENING -> OrbMode.LISTENING
              VoicePhase.THINKING -> OrbMode.THINKING
              VoicePhase.SPEAKING -> OrbMode.WRITING
              VoicePhase.IDLE -> OrbMode.IDLE
            },
            Modifier.size(190.dp),
          )
          Spacer(Modifier.height(20.dp))
          AnimatedContent(voice.phase, transitionSpec = { fadeIn(tween(180, easing = ExpoOut)) togetherWith fadeOut(tween(100)) }, label = "voicePhase") { p ->
            Text(
              when {
                state.phase !is Phase.Ready -> "The model is loading…"
                p == VoicePhase.IDLE -> "Tap the microphone and talk"
                p == VoicePhase.LISTENING -> "Listening… tap again when you're done"
                p == VoicePhase.THINKING -> "Thinking…"
                else -> "Speaking · tap to interrupt"
              },
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
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
          if (photo != null) {
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
                VoicePhase.LISTENING -> Icons.Default.ArrowUpward
                else -> Icons.Default.Stop
              },
              when (p) {
                VoicePhase.IDLE -> "Talk"
                VoicePhase.LISTENING -> "Send what you said"
                else -> "Interrupt"
              },
              Modifier.size(34.dp),
            )
          }
        }

        // keeps the talk button centred
        Spacer(Modifier.size(64.dp))
      }
    }
  }
}

internal fun hasMic(context: android.content.Context) =
  androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
