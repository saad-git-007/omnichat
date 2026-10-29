package com.saad.omnichat.ui.logs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.material3.Switch
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saad.omnichat.AppLog
import com.saad.omnichat.CrashReports
import com.saad.omnichat.ui.theme.GlassButton
import com.saad.omnichat.ui.theme.glass
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Read at start-up by MainActivity, which sets GGML_HEXAGON_PROFILE before the backends load. */
const val NPU_PROFILE_KEY = "npuProfile"

private enum class LogTab(val label: String) { CRASH("Last crash"), PREVIOUS("Previous run"), CURRENT("This run") }

/** Copy/share is capped: the clipboard and share intents fail on very large text. */
private const val SHARE_MAX_CHARS = 400_000

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(onBack: () -> Unit) {
  val context = LocalContext.current
  val hasCrash = remember { CrashReports.file(context).exists() }
  var tab by remember { mutableStateOf(if (hasCrash) LogTab.CRASH else LogTab.CURRENT) }
  var reload by remember { mutableIntStateOf(0) }
  var text by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(tab, reload) {
    text = null
    text = withContext(Dispatchers.IO) { read(fileFor(context, tab)) }
  }

  Scaffold(
    containerColor = Color.Transparent,
    topBar = {
      TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent),
        title = { Text("Logs") },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        actions = { IconButton(onClick = { reload++ }) { Icon(Icons.Default.Refresh, "Reload") } },
      )
    },
  ) { pad ->
    Column(Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp)) {
      SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        LogTab.entries.forEachIndexed { i, t ->
          SegmentedButton(
            selected = tab == t,
            onClick = { tab = t },
            shape = SegmentedButtonDefaults.itemShape(i, LogTab.entries.size),
            label = { Text(t.label, maxLines = 1) },
          )
        }
      }
      Text(
        when (tab) {
          LogTab.CRASH -> "Where the app crashed, saved on the launch after a crash."
          LogTab.PREVIOUS -> "Everything logged by the last run. After a crash, this has the steps leading up to it."
          LogTab.CURRENT -> "Everything logged since the app opened."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 10.dp, bottom = 10.dp),
      )
      val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
      var profile by remember { mutableStateOf(prefs.getBoolean(NPU_PROFILE_KEY, false)) }
      Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text("Profile NPU ops", style = MaterialTheme.typography.bodyLarge)
          Text(
            "Adds a per-op timing table after each reply. Applies after you close and reopen the app; slows replies slightly.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = profile, onCheckedChange = { profile = it; prefs.edit().putBoolean(NPU_PROFILE_KEY, it).apply() })
      }
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        GlassButton(onClick = { text?.let { copy(context, it) } }) {
          Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp))
          Spacer(Modifier.width(8.dp))
          Text("Copy")
        }
        GlassButton(onClick = { text?.let { share(context, tab, it) } }) {
          Icon(Icons.Default.Share, null, Modifier.size(18.dp))
          Spacer(Modifier.width(8.dp))
          Text("Share")
        }
      }
      Spacer(Modifier.size(12.dp))

      val lines = remember(text) { text?.lines() ?: emptyList() }
      val list = rememberLazyListState()
      // logs read bottom-up: open at the newest line, the crash report at its top
      LaunchedEffect(lines) { if (lines.isNotEmpty() && tab != LogTab.CRASH) list.scrollToItem(lines.lastIndex) }
      SelectionContainer(Modifier.weight(1f).fillMaxWidth().glass(RoundedCornerShape(16.dp), lift = 4.dp)) {
        LazyColumn(state = list, contentPadding = PaddingValues(12.dp), modifier = Modifier.fillMaxSize()) {
          when {
            text == null -> item { Text("Loading…", style = MaterialTheme.typography.bodySmall) }
            lines.all { it.isBlank() } -> item { Text(emptyText(tab), style = MaterialTheme.typography.bodyMedium) }
            else ->
              itemsIndexed(lines) { _, line ->
                Text(
                  line,
                  fontFamily = FontFamily.Monospace,
                  fontSize = 11.sp,
                  lineHeight = 15.sp,
                  color =
                    when {
                      line.length > 13 && line[13] == 'E' -> MaterialTheme.colorScheme.error
                      line.length > 13 && line[13] == 'W' -> MaterialTheme.colorScheme.tertiary
                      else -> MaterialTheme.colorScheme.onSurface
                    },
                )
              }
          }
        }
      }
      Spacer(Modifier.size(16.dp))
    }
  }
}

private fun fileFor(context: Context, tab: LogTab): File =
  when (tab) {
    LogTab.CRASH -> CrashReports.file(context)
    LogTab.PREVIOUS -> AppLog.previous(context)
    LogTab.CURRENT -> AppLog.current(context)
  }

private fun emptyText(tab: LogTab) =
  when (tab) {
    LogTab.CRASH -> "No crash recorded."
    LogTab.PREVIOUS -> "No log from a previous run yet."
    LogTab.CURRENT -> "Nothing logged yet."
  }

/** The tail of the file, which is the part that matters for a crash. */
private fun read(f: File): String {
  if (!f.exists()) return ""
  val len = f.length()
  if (len <= SHARE_MAX_CHARS) return f.readText()
  f.inputStream().use { s ->
    s.skip(len - SHARE_MAX_CHARS)
    val tail = String(s.readBytes())
    return "[showing the last ${SHARE_MAX_CHARS / 1000} KB of ${len / 1000} KB]\n" + tail.substringAfter('\n')
  }
}

private fun copy(context: Context, text: String) {
  val cm = context.getSystemService(ClipboardManager::class.java)
  cm.setPrimaryClip(ClipData.newPlainText("OmniChat log", text))
  Toast.makeText(context, "Copied ${text.lines().size} lines", Toast.LENGTH_SHORT).show()
}

private fun share(context: Context, tab: LogTab, text: String) {
  val send =
    Intent(Intent.ACTION_SEND).apply {
      type = "text/plain"
      putExtra(Intent.EXTRA_SUBJECT, "OmniChat ${tab.label.lowercase()} log")
      putExtra(Intent.EXTRA_TEXT, text)
    }
  context.startActivity(Intent.createChooser(send, "Share log"))
}
