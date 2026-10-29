package com.saad.omnichat.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saad.omnichat.ui.theme.LocalOmni
import com.saad.omnichat.ui.theme.glass
import com.saad.omnichat.ui.voiceglow.VoiceGlow
import com.saad.omnichat.ui.voiceglow.VoiceGlowType


/**
 * Live transcribe: keep talking and see it written down a few seconds behind, with nothing answered. It keeps
 * listening after leaving this page (and with the screen off) until stopped; the transcript stays until closed.
 */
@Composable
fun TranscribeScreen(vm: ChatViewModel, onBack: () -> Unit) {
  val state by vm.state.collectAsStateWithLifecycle()
  val live = state.voice.live
  val context = androidx.compose.ui.platform.LocalContext.current
  val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) vm.liveStart() }
  val start = { if (hasMic(context)) vm.liveStart() else micPermission.launch(android.Manifest.permission.RECORD_AUDIO) }
  var saveStamps by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }
  val saveTranscript = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { vm.liveSave(it, saveStamps) }

  VoiceGlow(
    level = vm::liveLevel,
    active = live?.running == true,
    processing = live?.writing == true,
    modifier = Modifier.fillMaxSize(),
    dark = LocalOmni.current.dark,
    type = VoiceGlowType.Mobile,
  ) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp)) {
      Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to chat") }
        Column(Modifier.weight(1f)) {
          Text("Live transcribe", style = MaterialTheme.typography.titleMedium)
          Text(
            "${VoiceLanguages[state.settings.voiceLang] ?: "English"} · written down on this phone",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      if (live != null) {
        LiveTranscriptPanel(live, Modifier.weight(1f).fillMaxWidth())
      } else Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Icon(Icons.Default.Subtitles, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
          Spacer(Modifier.height(20.dp))
          Text(
            when {
              state.phase !is Phase.Ready -> "The model is loading…"
              state.generating -> "Waiting for the reply to finish…"
              else -> "Tap the microphone to start"
            },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
          )
          Spacer(Modifier.height(8.dp))
          Text(
            "A lecture, a meeting or a call on speaker: everything said is written down, nothing is answered. " +
              "It keeps going with the screen off.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
          )
        }
      }

      Row(Modifier.fillMaxWidth().padding(bottom = 24.dp, top = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        when {
          live == null -> {
            Spacer(Modifier.size(64.dp))
            FilledIconButton(
              onClick = start,
              enabled = state.phase is Phase.Ready && !state.generating && state.voice.phase == VoicePhase.IDLE,
              modifier = Modifier.size(84.dp).shadow(14.dp, CircleShape, clip = false),
            ) {
              Icon(Icons.Default.Mic, "Start transcribing", Modifier.size(34.dp))
            }
            Spacer(Modifier.size(64.dp))
          }
          live.running -> {
            Spacer(Modifier.size(64.dp))
            FilledIconButton(onClick = vm::liveStop, modifier = Modifier.size(84.dp).shadow(14.dp, CircleShape, clip = false)) {
              Icon(Icons.Default.Stop, "Stop transcribing", Modifier.size(34.dp))
            }
            Spacer(Modifier.size(64.dp))
          }
          else -> {
            StampChoice(Icons.Default.ContentCopy, "Copy the transcript", live.lines.isNotEmpty()) { stamps -> copyText(context, live.export(stamps)) }
            FilledIconButton(onClick = vm::liveClose, modifier = Modifier.size(84.dp).shadow(14.dp, CircleShape, clip = false)) {
              Icon(Icons.Default.Check, "Done", Modifier.size(34.dp))
            }
            StampChoice(Icons.Default.Save, "Save as a text file", live.lines.isNotEmpty()) { stamps ->
              saveStamps = stamps
              val name = java.text.SimpleDateFormat("yyyy-MM-dd HHmm", java.util.Locale.US).format(java.util.Date(live.started))
              saveTranscript.launch("Transcript $name.txt")
            }
          }
        }
      }
    }
  }
}

/** The transcript so far, a line per piece with its time from the start; any of it can be selected and copied. */
@Composable
private fun LiveTranscriptPanel(live: LiveTranscript, modifier: Modifier) {
  val list = rememberLazyListState()
  LaunchedEffect(live.lines.size) { if (live.lines.isNotEmpty()) list.animateScrollToItem(live.lines.size - 1) }
  Column(modifier.padding(top = 12.dp)) {
    Text("Live transcript", style = MaterialTheme.typography.titleMedium)
    Text(
      when {
        live.running && live.writing -> "Listening · writing down…"
        live.running -> "Listening · text appears every few seconds"
        else -> "${live.lines.size} parts · copy or save it, with or without time stamps; ✓ clears it"
      },
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    if (live.lines.isEmpty()) {
      Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
        Text(
          if (live.running) "Start talking. Nothing is answered, only written down." else "Nothing was heard.",
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
        )
      }
    } else SelectionContainer(Modifier.weight(1f)) {
      LazyColumn(state = list, modifier = Modifier.fillMaxSize().glass(MaterialTheme.shapes.large, lift = 2.dp).padding(14.dp)) {
        items(live.lines) { l ->
          Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(
              LiveTranscript.stamp(l.at),
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.primary,
              modifier = Modifier.width(52.dp).padding(top = 3.dp),
            )
            Text(l.text, style = MaterialTheme.typography.bodyLarge)
          }
        }
      }
    }
  }
}

/** A round button that asks "with time stamps or text only?" before doing [action]. */
@Composable
private fun StampChoice(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, action: (Boolean) -> Unit) {
  var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
  Box {
    IconButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.size(56.dp).glass(CircleShape, lift = 6.dp)) { Icon(icon, label) }
    androidx.compose.material3.DropdownMenu(open, onDismissRequest = { open = false }) {
      Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
      androidx.compose.material3.DropdownMenuItem(text = { Text("With time stamps") }, onClick = { open = false; action(true) })
      androidx.compose.material3.DropdownMenuItem(text = { Text("Text only") }, onClick = { open = false; action(false) })
    }
  }
}

private fun copyText(context: android.content.Context, text: String) {
  context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("Transcript", text))
  android.widget.Toast.makeText(context, "Transcript copied", android.widget.Toast.LENGTH_SHORT).show()
}
