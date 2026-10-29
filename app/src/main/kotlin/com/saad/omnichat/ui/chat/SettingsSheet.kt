package com.saad.omnichat.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import com.saad.omnichat.llm.Backend
import kotlin.math.roundToInt
import com.saad.omnichat.ui.theme.sheetGlass

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(state: UiState, onDismiss: () -> Unit, onSave: (Settings) -> Unit, onOpenInstructions: () -> Unit, onOpenLogs: () -> Unit) {
  var s by remember { mutableStateOf(state.settings) }
  val backends = state.backends.ifEmpty { listOf(Backend.CPU) }
  val selected = backends.firstOrNull { it.device == s.backend }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor = sheetGlass(),
  ) {
    Column(
      Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text("Backend", style = MaterialTheme.typography.titleMedium)
      Text(
        "Changing backend, threads or context reloads the model and clears the chat. Compare tok/s to find the fastest.",
        style = MaterialTheme.typography.bodySmall,
      )
      Option("Auto (NPU if available, else CPU)", s.backend == null || selected == null && s.backend != "") { s = s.copy(backend = null) }
      backends.forEach { b -> Option(b.label, selected == b) { s = s.copy(backend = b.device) } }
      val ctx = LocalContext.current

      HorizontalDivider()
      Text("Temperature: ${"%.2f".format(s.temperature)}${if (s.temperature == 0f) " (greedy, model default)" else ""}")
      Slider(value = s.temperature, onValueChange = { s = s.copy(temperature = (it * 20).roundToInt() / 20f) }, valueRange = 0f..1.5f)

      Text("CPU threads: ${s.threads}")
      Slider(value = s.threads.toFloat(), onValueChange = { s = s.copy(threads = it.roundToInt()) }, valueRange = 1f..8f, steps = 6)

      val onNpu = (selected?.device ?: backends.firstOrNull { it.device.startsWith("HTP") }?.device ?: "").startsWith("HTP")
      Text("Context size: ${"%,d".format(s.nCtx)} tokens · ${kvMb(s.nCtx)} MB memory${if (onNpu && s.nCtx > NPU_MAX_CTX) " · CPU only (NPU uses 32k)" else ""}")
      Text(
        "How much the model can keep in mind: chat history, documents, images (~550 tokens each) and audio (~750 tokens a minute). Only memory grows with this setting; replies slow down only as a chat actually gets long.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Slider(
        value = CTX_SIZES.indexOf(s.nCtx).coerceAtLeast(0).toFloat(),
        onValueChange = { s = s.copy(nCtx = CTX_SIZES[it.roundToInt()]) },
        valueRange = 0f..(CTX_SIZES.size - 1).toFloat(),
        steps = CTX_SIZES.size - 2,
      )

      Text("Max reply tokens: ${s.maxTokens}")
      Slider(
        value = s.maxTokens.toFloat(),
        onValueChange = { s = s.copy(maxTokens = (it / 256).roundToInt() * 256) },
        valueRange = 256f..8192f,
      )

      Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text("Read images and audio on the NPU/GPU", style = MaterialTheme.typography.bodyLarge)
          Text(
            "About 8x faster images on the NPU. Turn off if attachments fail: the reply still runs on the NPU/GPU.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = s.mediaOnGpu, onCheckedChange = { s = s.copy(mediaOnGpu = it) })
      }

      Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text("Faster output layer", style = MaterialTheme.typography.bodyLarge)
          Text(
            "Stores the model's largest table in 4-bit (Q4_0) instead of 6-bit, so every token reads ~60 MB less. Made once on the phone; compare speed and answers on the Performance page.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = s.fastHead, onCheckedChange = { s = s.copy(fastHead = it) })
      }

      HorizontalDivider()
      Text("Voice chat", style = MaterialTheme.typography.titleMedium)
      Text(
        "The language and voice the model answers in out loud. It understands speech in any of these.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      var langOpen by remember { mutableStateOf(false) }
      Box {
        OutlinedButton(onClick = { langOpen = true }, modifier = Modifier.fillMaxWidth()) {
          Text("Speaks: ${VoiceLanguages[s.voiceLang] ?: s.voiceLang}", modifier = Modifier.weight(1f))
          Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded = langOpen, onDismissRequest = { langOpen = false }) {
          VoiceLanguages.forEach { (code, name) ->
            DropdownMenuItem(text = { Text(name) }, onClick = { s = s.copy(voiceLang = code); langOpen = false })
          }
        }
      }
      SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        listOf("female" to "Female voice", "male" to "Male voice").forEachIndexed { i, (v, label) ->
          SegmentedButton(
            selected = s.voiceGender == v,
            onClick = { s = s.copy(voiceGender = v) },
            shape = SegmentedButtonDefaults.itemShape(i, 2),
            label = { Text(label) },
          )
        }
      }

      HorizontalDivider()
      Text("Phone tools", style = MaterialTheme.typography.titleMedium)
      ToggleRow(
        "Let the model use the phone",
        "Alarms, timers, calendar events, texts, emails, calls, maps, web pages, a calculator, the clipboard and the battery. Each action opens the right app filled in: you save, send or call there.",
        s.phoneTools,
      ) { s = s.copy(phoneTools = it) }
      PermissionRow("Read contacts", "So \"text Sara\" finds Sara's number.", android.Manifest.permission.READ_CONTACTS)
      PermissionRow("Read calendar", "So the model can tell you what's coming up.", android.Manifest.permission.READ_CALENDAR)
      PermissionRow("Send texts", "Texts to several people become one card in the chat with a Send button, instead of opening Messages for each. Nothing is sent until you tap it.", android.Manifest.permission.SEND_SMS)

      HorizontalDivider()
      Text("Smart search", style = MaterialTheme.typography.titleMedium)
      Text(
        "Google's EmbeddingGemma 2 runs beside the chat model and searches by meaning, on this phone. Turning it on downloads it once (310 MB).",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      ToggleRow(
        "Search past chats and long documents",
        "The model can look things up in earlier conversations, and PDFs or files too long for its memory are searched for each question instead of cut off.",
        s.smartSearch,
      ) { s = s.copy(smartSearch = it) }
      val mediaPermissions = arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO)
      val galleryAsk = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { r ->
        val ok = r.values.any { it } || ctx.checkSelfPermission(android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (ok) s = s.copy(gallerySearch = true)
      }
      ToggleRow(
        "Search my photos and videos",
        "Each photo is looked at once in the background (it pauses while the model replies), then \"find my beach photos from last summer\" takes a moment. Downloads 555 MB more. Indexing thousands of photos takes a while; it resumes where it stopped.",
        s.gallerySearch,
      ) { on ->
        if (!on) s = s.copy(gallerySearch = false)
        else if (mediaPermissions.any { ctx.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }) s = s.copy(gallerySearch = true)
        else galleryAsk.launch(mediaPermissions)
      }
      if (s.gallerySearch) {
        Text("Index photos and videos from", style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          listOf(1 to "Last month", 3 to "3 months", 6 to "6 months", 12 to "1 year", 24 to "2 years", 0 to "All time").forEach { (m, label) ->
            androidx.compose.material3.FilterChip(selected = s.galleryMonths == m, onClick = { s = s.copy(galleryMonths = m) }, label = { Text(label) })
          }
        }
        Text(
          "Older photos aren't looked at, which saves time and battery. Ones already indexed stay searchable.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      state.searchStatus?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = com.saad.omnichat.ui.theme.accentText())
      }

      HorizontalDivider()
      Text("Read text (OCR)", style = MaterialTheme.typography.titleMedium)
      ToggleRow(
        "Read scanned PDFs and text in photos",
        "OpenBMB's MiniCPM-OCR writes down the text in PDF pages that are only pictures, and in photos you attach (signs, receipts, screenshots), so the chat model can work with it. Runs on this phone, loaded only while reading. Downloads 1.4 GB once. About 10 to 40 seconds a page.",
        s.ocr,
      ) { s = s.copy(ocr = it) }
      state.ocrStatus?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = com.saad.omnichat.ui.theme.accentText())
      }

      HorizontalDivider()
      Text("Web search", style = MaterialTheme.typography.titleMedium)
      Text(
        "Turn on with the globe in the message bar. Your question goes to Exa to find pages and to Firecrawl to read them; the answer is still written on this phone. Keys are optional: without them the free tiers are used, which allow a limited number of searches a day. Your own keys raise the limits and stay on this phone.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      OutlinedTextField(
        value = s.exaKey,
        onValueChange = { s = s.copy(exaKey = it) },
        label = { Text("Exa API key (optional)") },
        singleLine = true,
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
      )
      OutlinedTextField(
        value = s.firecrawlKey,
        onValueChange = { s = s.copy(firecrawlKey = it) },
        label = { Text("Firecrawl API key (optional)") },
        singleLine = true,
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
      )

      HorizontalDivider()
      val on = state.instructions.count { it.enabled }
      Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpenInstructions).heightIn(min = 56.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(Modifier.weight(1f)) {
          Text("Custom instructions", style = MaterialTheme.typography.titleMedium)
          Text(
            if (on == 0) "Tell the model how to respond" else "$on on",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
      }

      HorizontalDivider()
      Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpenLogs).heightIn(min = 56.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(Modifier.weight(1f)) {
          Text("Logs", style = MaterialTheme.typography.titleMedium)
          Text(
            "Detailed logs and crash reports, ready to copy",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
      }

      Button(onClick = { onSave(s) }, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) { Text("Apply") }
      val version = remember { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }
      Text(
        "OmniChat $version",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        textAlign = TextAlign.Center,
      )
    }
  }
}

// LFM3 was trained on 128k tokens
private val CTX_SIZES = listOf(4096, 8192, 16384, 32768, 65536, 131072)

/** KV cache: 6 full-attention layers x (K + V) x 512 dims x 2 bytes; the sliding-window layers add a few MB. */
private fun kvMb(nCtx: Int) = nCtx.toLong() * 6 * 2 * 512 * 2 / (1 shl 20)

@Composable
private fun ToggleRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
  Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.bodyLarge)
      Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.width(12.dp))
    Switch(checked = checked, onCheckedChange = onChange)
  }
}

/** An Android permission the phone tools can use: a button to allow it, or a note that it is allowed. */
@Composable
private fun PermissionRow(title: String, detail: String, permission: String) {
  val ctx = LocalContext.current
  var granted by remember { mutableStateOf(ctx.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED) }
  val ask = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted = it }
  Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.bodyLarge)
      Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.width(12.dp))
    if (granted) Text("Allowed", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    else TextButton(onClick = { ask.launch(permission) }) { Text("Allow") }
  }
}

@Composable
private fun Option(label: String, selected: Boolean, onClick: () -> Unit) {
  Row(Modifier.fillMaxWidth().selectable(selected, onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
    RadioButton(selected = selected, onClick = onClick)
    Text(label, style = MaterialTheme.typography.bodyMedium)
  }
}
