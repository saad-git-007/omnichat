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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.saad.omnichat.ui.theme.LocalOmni
import com.saad.omnichat.ui.theme.glass
import com.saad.omnichat.ui.voiceglow.VoiceGlow
import com.saad.omnichat.ui.voiceglow.VoiceGlowType
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Live transcribe: keep talking and see it written down a few seconds behind, with nothing answered. It keeps
 * listening after leaving this page (and with the screen off) until stopped; the transcript stays until closed.
 * Alongside it, meeting notes: action items, decisions, key points and, at the end, a summary.
 */
@Composable
fun TranscribeScreen(vm: ChatViewModel, onBack: () -> Unit) {
  val state by vm.state.collectAsStateWithLifecycle()
  val live = state.voice.live
  val context = androidx.compose.ui.platform.LocalContext.current
  val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) vm.liveStart() }
  val start = { if (hasMic(context)) vm.liveStart() else micPermission.launch(android.Manifest.permission.RECORD_AUDIO) }
  var saveFormat by remember { mutableStateOf(ChatViewModel.LiveFormat.STAMPED) }
  val saveText = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { vm.liveSave(it, saveFormat) }
  val saveMarkdown = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { vm.liveSave(it, saveFormat) }
  val saveSubtitles = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { vm.liveSave(it, saveFormat) }
  var tab by remember { mutableStateOf(0) }
  // leaving the page lets the chat model come back once listening and notes are done
  androidx.compose.runtime.DisposableEffect(Unit) {
    vm.liveShown(true)
    onDispose { vm.liveShown(false) }
  }
  var searching by remember { mutableStateOf(false) }
  var query by remember { mutableStateOf("") }

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
            "${VoiceLanguages[state.settings.voiceLang] ?: "English"} · " + (state.liveModel?.let { if (it.startsWith("loading")) "$it…" else "written down by $it" } ?: "written down on this phone"),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
          )
        }
        if (live != null && live.lines.isNotEmpty() && tab == 0) {
          IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
            Icon(if (searching) Icons.Default.Close else Icons.Default.Search, if (searching) "Close search" else "Search the transcript")
          }
        }
      }

      if (live != null) {
        val showNotes = state.settings.meetingNotes || !live.notes.isEmpty
        if (showNotes) {
          SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            val n = live.notes.items.size + live.notes.decisions.size
            listOf("Transcript", if (n > 0) "Notes · $n" else "Notes").forEachIndexed { i, label ->
              SegmentedButton(selected = tab == i, onClick = { tab = i }, shape = SegmentedButtonDefaults.itemShape(i, 2), label = { Text(label) })
            }
          }
        }
        if (tab == 1 && showNotes) {
          NotesPanel(live, state.settings.meetingNotes, afterStop = state.liveModel != null, onSummarize = vm::liveSummarize, modifier = Modifier.weight(1f).fillMaxWidth())
        } else {
          if (searching) {
            OutlinedTextField(
              value = query,
              onValueChange = { query = it },
              placeholder = { Text("Find in the transcript") },
              singleLine = true,
              modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
          }
          LiveTranscriptPanel(live, query.trim(), Modifier.weight(1f).fillMaxWidth())
        }
        if (!live.running && live.lines.isNotEmpty()) {
          OutlinedButton(onClick = { vm.liveAskInChat(); onBack() }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
            Text("Ask about this meeting in chat")
          }
        }
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
              "It keeps going with the screen off." +
              if (state.settings.meetingNotes) " Action items, decisions and key points are noted as it goes." else "",
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
            IconButton(onClick = vm::liveMark, modifier = Modifier.size(56.dp).glass(CircleShape, lift = 6.dp)) {
              Icon(Icons.Default.BookmarkAdd, "Bookmark this moment")
            }
            FilledIconButton(onClick = vm::liveStop, modifier = Modifier.size(84.dp).shadow(14.dp, CircleShape, clip = false)) {
              Icon(Icons.Default.Stop, "Stop transcribing", Modifier.size(34.dp))
            }
            Spacer(Modifier.size(56.dp))
          }
          else -> {
            ChoiceButton(
              Icons.Default.ContentCopy,
              "Copy the transcript",
              live.lines.isNotEmpty(),
              listOf("With time stamps" to ChatViewModel.LiveFormat.STAMPED, "Text only" to ChatViewModel.LiveFormat.PLAIN, "Notes and transcript" to ChatViewModel.LiveFormat.MARKDOWN),
            ) { f -> vm.liveText(f)?.let { copyText(context, it) } }
            FilledIconButton(onClick = vm::liveClose, modifier = Modifier.size(84.dp).shadow(14.dp, CircleShape, clip = false)) {
              Icon(Icons.Default.Check, "Done", Modifier.size(34.dp))
            }
            ChoiceButton(
              Icons.Default.Save,
              "Save as a file",
              live.lines.isNotEmpty(),
              listOf(
                "Text with time stamps" to ChatViewModel.LiveFormat.STAMPED,
                "Text only" to ChatViewModel.LiveFormat.PLAIN,
                "Notes and transcript (.md)" to ChatViewModel.LiveFormat.MARKDOWN,
                "Subtitles (.srt)" to ChatViewModel.LiveFormat.SUBTITLES,
              ),
            ) { f ->
              saveFormat = f
              val name = java.text.SimpleDateFormat("yyyy-MM-dd HHmm", java.util.Locale.US).format(java.util.Date(live.started))
              when (f) {
                ChatViewModel.LiveFormat.MARKDOWN -> saveMarkdown.launch("Meeting $name.md")
                ChatViewModel.LiveFormat.SUBTITLES -> saveSubtitles.launch("Transcript $name.srt")
                else -> saveText.launch("Transcript $name.txt")
              }
            }
          }
        }
      }
    }
  }
}

/**
 * The transcript so far, a line per piece with its time from the start (bookmarked ones marked); any of it can be
 * selected and copied. With a [query], only the lines that contain it, with it highlighted.
 */
@Composable
private fun LiveTranscriptPanel(live: LiveTranscript, query: String, modifier: Modifier) {
  // coming back to the page opens at the newest text, still following it
  val list = rememberLazyListState(initialFirstVisibleItemIndex = (live.lines.size - 1).coerceAtLeast(0))
  val scope = rememberCoroutineScope()
  // new text follows only while the reader is at the bottom; scrolling up to read stops it until they come back down
  var follow by remember { mutableStateOf(true) }
  LaunchedEffect(list) {
    // the first value is the list before its first layout, not the reader scrolling: it said "not at the bottom"
    snapshotFlow { list.isScrollInProgress }.drop(1).collect { scrolling -> if (!scrolling) follow = !list.canScrollForward }
  }
  val shown = remember(live.lines, query) { live.lines.indices.filter { query.isEmpty() || live.lines[it].text.contains(query, ignoreCase = true) } }
  // to the end of the newest piece, not its first line: a long piece is taller than the panel (as the chat does)
  LaunchedEffect(live.lines.size) {
    if (follow && query.isEmpty() && shown.isNotEmpty()) {
      list.toEnd(shown.size - 1)
      follow = true
    }
  }
  Column(modifier.padding(top = 12.dp)) {
    Text(
      when {
        query.isNotEmpty() -> "${shown.size} of ${live.lines.size} parts contain \"$query\""
        live.running && live.writing -> "Listening · writing down…"
        live.running -> "Listening · text appears every few seconds"
        else -> "${live.lines.size} parts · copy or save it, with or without time stamps; ✓ clears it"
      },
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    if (shown.isEmpty()) {
      Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
        Text(
          when {
            query.isNotEmpty() -> "Not found in the transcript."
            live.running -> "Start talking. Nothing is answered, only written down."
            else -> "Nothing was heard."
          },
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
        )
      }
    } else Box(Modifier.weight(1f)) {
      SelectionContainer(Modifier.fillMaxSize()) {
        LazyColumn(state = list, modifier = Modifier.fillMaxSize().glass(MaterialTheme.shapes.large, lift = 2.dp).padding(14.dp)) {
          items(shown, key = { live.lines[it].at }) { i ->
            val l = live.lines[i]
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
              Column(Modifier.width(52.dp).padding(top = 3.dp)) {
                Text(LiveTranscript.stamp(l.at), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                if (live.marked(i)) Icon(Icons.Default.Bookmark, "Bookmarked", Modifier.size(18.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.tertiary)
              }
              Text(highlight(l.text, query), style = MaterialTheme.typography.bodyLarge)
            }
          }
        }
      }
      if (!follow && query.isEmpty()) {
        androidx.compose.material3.SmallFloatingActionButton(
          onClick = { scope.launch { list.toEnd(shown.size - 1); follow = true } },
          modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
        ) { Icon(Icons.Default.KeyboardArrowDown, "Jump to the latest") }
      }
    }
  }
}

private fun highlight(text: String, query: String): AnnotatedString = buildAnnotatedString {
  if (query.isEmpty()) {
    append(text)
    return@buildAnnotatedString
  }
  var i = 0
  while (true) {
    val at = text.indexOf(query, i, ignoreCase = true)
    if (at < 0) {
      append(text.substring(i))
      break
    }
    append(text.substring(i, at))
    withStyle(SpanStyle(fontWeight = FontWeight.Bold, background = Color(0x66FFC857))) { append(text.substring(at, at + query.length)) }
    i = at + query.length
  }
}

/** Action items, decisions, key points by the minute and the summary, as they are written. */
@Composable
private fun NotesPanel(live: LiveTranscript, on: Boolean, afterStop: Boolean, onSummarize: () -> Unit, modifier: Modifier) {
  val n = live.notes
  Column(modifier.padding(top = 12.dp)) {
    Text(
      when {
        !on -> "Meeting notes are off in Settings; these are from before"
        live.noting -> if (live.running) "Writing notes between pieces…" else "Writing the notes…"
        live.running && afterStop -> "Written when you stop: LFM3-Audio transcribes, then the chat model writes the notes"
        live.running -> "Noted between pieces, without slowing the transcript"
        n.summary == null && n.points.size >= 2 -> "Writing a summary…"
        else -> "Saving or copying \"Notes and transcript\" includes these"
      },
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    if (n.isEmpty) {
      Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
        Text(
          if (live.running && afterStop) "Action items, decisions, the key points of each minute and a summary appear here when you stop."
          else "Action items, decisions and the key points of each minute appear here as the meeting goes on.",
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
        )
      }
      return@Column
    }
    SelectionContainer(Modifier.weight(1f)) {
      LazyColumn(Modifier.fillMaxSize().glass(MaterialTheme.shapes.large, lift = 2.dp).padding(14.dp)) {
        item {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Summary", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (n.points.size >= 2) TextButton(onClick = onSummarize) { Text(if (n.summary.isNullOrEmpty()) "Summarize so far" else "Update") }
          }
          Text(
            n.summary?.takeIf { it.isNotBlank() } ?: if (live.running) "Written when you stop, or now with Summarize." else "Not enough was said for a summary.",
            style = MaterialTheme.typography.bodyLarge,
            color = if (n.summary.isNullOrBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
          )
        }
        if (n.items.isNotEmpty()) {
          item { Section("Action items") }
          items(n.items) { NoteRow(it.at, buildAnnotatedString { withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(it.who) }; append(": " + it.task); it.due?.let { d -> append(" · by $d") } }) }
        }
        if (n.decisions.isNotEmpty()) {
          item { Section("Decisions") }
          items(n.decisions) { NoteRow(it.at, AnnotatedString(it.text)) }
        }
        if (n.points.isNotEmpty()) {
          item { Section("Key points") }
          items(n.points) { NoteRow(it.at, AnnotatedString(it.text)) }
        }
      }
    }
  }
}

@Composable
private fun Section(title: String) = Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, bottom = 2.dp))

@Composable
private fun NoteRow(at: Long, text: AnnotatedString) {
  Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
    Text(LiveTranscript.stamp(at), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(52.dp).padding(top = 3.dp))
    Text(text, style = MaterialTheme.typography.bodyLarge)
  }
}

/** A round button that asks which form ([choices]) before doing [action]. */
@Composable
private fun <T> ChoiceButton(icon: ImageVector, label: String, enabled: Boolean, choices: List<Pair<String, T>>, action: (T) -> Unit) {
  var open by remember { mutableStateOf(false) }
  Box {
    IconButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.size(56.dp).glass(CircleShape, lift = 6.dp)) { Icon(icon, label) }
    DropdownMenu(open, onDismissRequest = { open = false }) {
      Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
      choices.forEach { (name, v) -> DropdownMenuItem(text = { Text(name) }, onClick = { open = false; action(v) }) }
    }
  }
}

private fun copyText(context: android.content.Context, text: String) {
  context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("Transcript", text))
  android.widget.Toast.makeText(context, "Transcript copied", android.widget.Toast.LENGTH_SHORT).show()
}

/**
 * To the very end of item [last], which can be taller than the list (a long piece): once the list has laid it out,
 * to its start, then down by more than any piece is long; the list stops at the end. Asked for before the first
 * layout, a scroll to an offset inside the item landed on its first line.
 */
private suspend fun androidx.compose.foundation.lazy.LazyListState.toEnd(last: Int) {
  snapshotFlow { layoutInfo.totalItemsCount }.first { it > last }
  scrollToItem(last)
  scroll { scrollBy(1_000_000f) }
}
