package com.saad.omnichat.ui.chat

import com.saad.omnichat.ui.theme.accentText
import android.Manifest
import android.content.pm.PackageManager
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material3.LocalContentColor
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.filled.Check
import androidx.activity.result.PickVisualMediaRequest
import com.saad.omnichat.ui.theme.BubbleGradient
import androidx.compose.ui.graphics.Brush
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.filled.Menu
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import kotlinx.coroutines.withContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.saad.omnichat.stats.RunRecord
import com.saad.omnichat.ui.theme.LocalOmni
import com.saad.omnichat.ui.theme.Numeric
import kotlinx.coroutines.delay
import com.saad.omnichat.ui.theme.veil
import com.saad.omnichat.ui.theme.glass
import com.saad.omnichat.ui.voiceglow.VoiceGlow
import com.saad.omnichat.ui.voiceglow.VoiceGlowType
import androidx.compose.ui.draw.shadow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel, onOpenStats: () -> Unit, onOpenInstructions: () -> Unit, onOpenLogs: () -> Unit, onOpenVoice: () -> Unit, onOpenTranscribe: () -> Unit) {
  val state by vm.state.collectAsStateWithLifecycle()
  var showSettings by rememberSaveable { mutableStateOf(false) }
  // the library's "mobile" glow: rises from the bottom edge of the screen with the mic, sweeps while the model listens
  val modelListening = state.messages.lastOrNull()?.activity == Activity.LISTENING
  VoiceGlow(
    level = vm::micLevel,
    active = state.recording || modelListening,
    processing = modelListening && !state.recording,
    cornerRadius = rememberScreenCornerRadius(),
    modifier = Modifier.fillMaxSize(),
    dark = LocalOmni.current.dark,
    type = VoiceGlowType.Mobile,
  ) {
  val drawer = rememberDrawerState(DrawerValue.Closed)
  // opening the chat list puts the keyboard away
  val focus = androidx.compose.ui.platform.LocalFocusManager.current
  LaunchedEffect(drawer.targetValue) { if (drawer.targetValue == DrawerValue.Open) focus.clearFocus() }
  val scope = rememberCoroutineScope()
  ModalNavigationDrawer(
    drawerState = drawer,
    scrimColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f),
    drawerContent = {
      HistoryDrawer(
        chats = state.chats,
        currentId = state.chatId,
        enabled = !state.generating,
        onNewChat = { vm.newChat(); scope.launch { drawer.close() } },
        onOpen = { vm.openChat(it); scope.launch { drawer.close() } },
        onDelete = vm::deleteChat,
      )
    },
  ) {
  Scaffold(
    containerColor = Color.Transparent,
    topBar = {
      TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent),
        navigationIcon = { IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Default.Menu, "Chats") } },
        title = { TitleBlock(state) },
        actions = {
          IconButton(onClick = onOpenVoice, enabled = !state.generating && state.voice.live?.running != true) { Icon(Icons.Outlined.RecordVoiceOver, "Voice chat") }
          // tinted while it is listening in the background
          IconButton(onClick = onOpenTranscribe) {
            Icon(Icons.Default.Subtitles, "Live transcribe", tint = if (state.voice.live?.running == true) MaterialTheme.colorScheme.error else LocalContentColor.current)
          }
          IconButton(onClick = onOpenStats) { Icon(Icons.Outlined.Insights, "Performance stats") }
          IconButton(onClick = { showSettings = true }) { Icon(Icons.Outlined.Tune, "Settings") }
        },
      )
    },
  ) { pad ->
    AnimatedContent(
      targetState = state.phase is Phase.Ready,
      transitionSpec = { fadeIn(tween(300, delayMillis = 90)) togetherWith fadeOut(tween(90)) },
      modifier = Modifier.padding(pad).fillMaxSize(),
      label = "phase",
    ) { ready ->
      if (ready) ChatBody(state, vm, onOpenTranscribe) else SetupPane(state.phase, vm)
    }
  }
  }
  }

  LaunchedEffect(Unit) { vm.onChatShown() }

  if (showSettings) {
    SettingsSheet(
      state,
      onDismiss = { showSettings = false },
      onSave = { vm.updateSettings(it); showSettings = false },
      onOpenInstructions = { showSettings = false; onOpenInstructions() },
      onOpenLogs = { showSettings = false; onOpenLogs() },
    )
  }
}

@Composable
private fun TitleBlock(state: UiState) {
  Column {
    Text("LFM3 Omni 2B", style = MaterialTheme.typography.titleMedium)
    Row(verticalAlignment = Alignment.CenterVertically) {
      StatusDot(
        when {
          state.generating -> accentText()
          state.phase is Phase.Ready -> MaterialTheme.colorScheme.secondary
          state.phase is Phase.Failed -> MaterialTheme.colorScheme.error
          else -> MaterialTheme.colorScheme.outline
        },
        // steady while generating: the orb already shows activity and every animation frame costs inference time
        pulsing = state.phase is Phase.Loading || state.phase is Phase.Downloading,
      )
      Spacer(Modifier.width(6.dp))
      val device =
        when (val p = state.phase) {
          // "HTP0 (Hexagon)" is the backend's own name; say what it is
          is Phase.Ready -> if (state.deviceInfo.startsWith("HTP")) "NPU · Hexagon" else state.deviceInfo
          is Phase.Loading -> "Loading on ${p.backend}"
          is Phase.Downloading -> "Downloading model"
          is Phase.Failed -> "Not loaded"
          Phase.NeedsDownload -> "Model not downloaded"
        }
      Text(
        device,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f, fill = false),
      )
      AnimatedVisibility(state.generating && state.liveTps != null, enter = fadeIn(), exit = fadeOut()) {
        Text(
          "  %.1f tok/s".format(state.liveTps ?: 0f),
          style = MaterialTheme.typography.labelMedium.merge(Numeric),
          color = accentText(),
        )
      }
    }
  }
}

@Composable
private fun StatusDot(color: Color, pulsing: Boolean) {
  val reduce = LocalOmni.current.reduceMotion
  val alpha =
    if (pulsing && !reduce) {
      rememberInfiniteTransition(label = "dot")
        .animateFloat(0.35f, 1f, infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "dotA")
        .value
    } else 1f
  Box(Modifier.size(8.dp).alpha(alpha).clip(CircleShape).background(color))
}

@Composable
private fun SetupPane(phase: Phase, vm: ChatViewModel) {
  Column(
    Modifier.fillMaxSize().padding(horizontal = 28.dp),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    val mode = if (phase is Phase.Loading || phase is Phase.Downloading) OrbMode.LOADING else OrbMode.IDLE
    ThinkingOrb(mode, Modifier.size(168.dp))
    Spacer(Modifier.height(20.dp))
    when (phase) {
      Phase.NeedsDownload -> {
        Text("Download the model", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
          "LFM3-Omni-2B (Q4_0, 1.05 GB) and its image and audio encoder (0.54 GB). After this, everything runs on the phone.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = vm::download, modifier = Modifier.heightIn(min = 48.dp)) { Text("Download 1.6 GB") }
        Spacer(Modifier.height(28.dp))
        Text("Or copy the GGUF files with adb to", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        SelectionContainer {
          Text(
            vm.modelDir,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
            modifier =
              Modifier.clip(RoundedCornerShape(10.dp)).background(veil()).padding(10.dp),
          )
        }
      }
      is Phase.Downloading -> {
        val frac = if (phase.total > 0) phase.done.toFloat() / phase.total else 0f
        Text("Downloading", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape))
        Spacer(Modifier.height(10.dp))
        Text(
          "%.0f of %.0f MB · %.0f%%".format(phase.done / 1e6, phase.total / 1e6, frac * 100),
          style = MaterialTheme.typography.bodyMedium.merge(Numeric),
        )
        Text(
          "Keep the app open. If it is interrupted, it resumes where it stopped.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
          modifier = Modifier.padding(top = 6.dp),
        )
      }
      is Phase.Loading -> {
        Text("Loading model", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
          if (phase.backend == "CPU") "On the CPU." else "On ${phase.backend}. The first GPU load compiles kernels, so it can take a minute.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
        )
      }
      is Phase.Failed -> {
        Text("Something went wrong", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(phase.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Button(onClick = { if (phase.message.startsWith("Download")) vm.download() else vm.loadModel() }) { Text("Try again") }
      }
      Phase.Ready -> {}
    }
  }
}

@Composable
private fun ChatBody(state: UiState, vm: ChatViewModel, onOpenTranscribe: () -> Unit) {
  val context = LocalContext.current
  val view = LocalView.current
  var input by rememberSaveable { mutableStateOf("") }
  val listState = rememberLazyListState()

  val picker =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> vm.pickerClosed(); if (uris.isNotEmpty()) vm.attach(uris) }
  // the system photo picker: no storage permission, opens straight on the gallery
  val photoPicker =
    rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(8)) { uris -> vm.pickerClosed(); if (uris.isNotEmpty()) vm.attach(uris) }
  var showAttach by rememberSaveable { mutableStateOf(false) }
  var askedMode by remember { mutableStateOf(MicMode.DICTATE) }
  val micPermission =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) vm.startRecording(askedMode) }
  val pickMedia = { showAttach = true }
  if (showAttach) {
    AttachSheet(
      onDismiss = { showAttach = false },
      onPhotos = {
        showAttach = false
        vm.pickerOpening()
        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
      },
      onAudio = {
        showAttach = false
        vm.pickerOpening()
        picker.launch(arrayOf("audio/*", "video/*"))
      },
      onFiles = {
        showAttach = false
        vm.pickerOpening()
        picker.launch(
          arrayOf(
            "application/pdf", "text/*", com.saad.omnichat.media.DocumentReader.XLSX, "application/csv", "application/json",
            "application/octet-stream", "image/*", "audio/*",
          )
        )
      },
    )
  }
  val onMic = { mode: MicMode ->
    when {
      state.recording -> vm.stopRecording()
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> vm.startRecording(mode)
      else -> {
        askedMode = mode
        micPermission.launch(Manifest.permission.RECORD_AUDIO)
      }
    }
  }
  // dictated words land in the message, after anything already typed
  LaunchedEffect(state.dictated) {
    state.dictated?.let { words ->
      input = if (input.isBlank()) words else input.trimEnd() + " " + words
      vm.dictationTaken()
    }
  }

  val last = state.messages.lastOrNull()
  // keep the newest content in view while the reader is at the bottom; scrolling up to read stops the following
  // (it used to pull back down on every token), and scrolling back to the end resumes it
  var follow by remember { mutableStateOf(true) }
  LaunchedEffect(listState) {
    snapshotFlow { listState.isScrollInProgress }.collect { scrolling -> if (!scrolling) follow = !listState.canScrollForward }
  }
  LaunchedEffect(state.messages.size) { follow = true }
  LaunchedEffect(state.messages.size, last?.text?.length, last?.activity) {
    // the offset is clamped, so this pins to the very bottom
    if (follow && state.messages.isNotEmpty()) listState.scrollToItem(state.messages.size - 1, Int.MAX_VALUE)
  }

  Column(Modifier.fillMaxSize().imePadding()) {
    Box(Modifier.weight(1f).fillMaxWidth()) {
      if (state.messages.isEmpty()) {
        EmptyState()
      } else {
        // a fresh list per conversation: swapping every item in one list made the old chat's rows linger,
        // drawn over the new ones, while their exit animation ran
        androidx.compose.runtime.key(state.conversation) {
          LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
          ) {
            items(state.messages, key = { it.id }) { msg ->
              Box(Modifier.animateItem(fadeInSpec = tween(220), fadeOutSpec = tween(120))) { MessageRow(msg, state.settings.animateWhileGenerating, vm) }
            }
          }
        }
      }
    }

    AnimatedVisibility(state.busyMessage != null) {
      Column {
        LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        // what is working right now (decoding, OCR, embeddings), beside a small orb
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
          ThinkingOrb(OrbMode.THINKING, Modifier.size(22.dp), small = true)
          Spacer(Modifier.width(8.dp))
          Text(state.busyMessage.orEmpty(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
      }
    }

    // live transcribe keeps going after leaving its page: a way back to it
    state.voice.live?.let { live ->
      Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primaryContainer).clickable(onClick = onOpenTranscribe).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        if (live.running) Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.error, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(
          (if (live.running) "Live transcribe is listening" else "Live transcript ready") + " · ${live.lines.size} parts",
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.onPrimaryContainer,
          modifier = Modifier.weight(1f),
        )
        Text("Open", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
      }
    }

    InputBar(
      state = state,
      input = input,
      onInput = { input = it },
      onAttach = pickMedia,
      onWeb = vm::toggleWebSearch,
      onMic = onMic,
      onRemove = vm::removePending,
      onSend = {
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        vm.send(input)
        input = ""
      },
      onStop = vm::stop,
    )
  }
}

@Composable
private fun EmptyState() {
  Column(
    Modifier.fillMaxSize().padding(horizontal = 28.dp),
    verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    ThinkingOrb(OrbMode.IDLE, Modifier.size(150.dp))
    Spacer(Modifier.height(20.dp))
    Text(
      "LFM3 Omni 2B reads text, images, audio and documents. It runs on this phone, so nothing you send leaves the device.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
  }
}

@Composable
private fun MessageRow(msg: ChatMessage, animate: Boolean, vm: ChatViewModel) {
  when (msg.role) {
    Role.NOTICE ->
      Text(
        msg.text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
      )
    Role.USER -> UserMessage(msg)
    Role.ASSISTANT -> AssistantMessage(msg, animate, vm)
  }
}

@Composable
private fun UserMessage(msg: ChatMessage) {
  Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
    Column(Modifier.widthIn(max = 300.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
      msg.attachments.forEach { att ->
        if (att.kind == AttachmentKind.IMAGE && att.uri != null) {
          AsyncImage(
            model = att.uri,
            contentDescription = att.label,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(18.dp)),
          )
        } else {
          AttachmentPill(att)
        }
      }
      if (msg.text.isNotEmpty()) {
        Box(Modifier.glass(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp), lift = 6.dp, fill = Brush.linearGradient(BubbleGradient))) {
          SelectionContainer {
            Text(
              msg.text,
              style = MaterialTheme.typography.bodyLarge,
              color = Color.White,
              modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun AttachmentPill(att: Attachment) = AudioPill(att.label, if (att.kind == AttachmentKind.DOCUMENT) Icons.Outlined.Description else Icons.Default.GraphicEq)

@Composable
private fun AudioPill(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Default.GraphicEq) {
  Row(
    Modifier.glass(CircleShape, lift = 4.dp).padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
      Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.secondary)
    }
    Spacer(Modifier.width(8.dp))
    Text(label, style = MaterialTheme.typography.labelLarge.merge(Numeric))
  }
}

@Composable
private fun AssistantMessage(msg: ChatMessage, animate: Boolean, vm: ChatViewModel) {
  Column(Modifier.fillMaxWidth()) {
    // header: the orb and what it is doing, then the final metrics once done
    AnimatedContent(
      targetState = msg.activity,
      transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
      contentKey = { it == Activity.DONE },
      label = "replyHeader",
    ) { activity ->
      if (activity != Activity.DONE) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
          val mode =
            when (activity) {
              Activity.SEARCHING, Activity.USING_PHONE, Activity.EMBEDDINGS -> OrbMode.THINKING
              Activity.READING_IMAGE, Activity.OCR -> OrbMode.READING_IMAGE
              Activity.LISTENING -> OrbMode.LISTENING
              Activity.THINKING -> OrbMode.THINKING
              else -> OrbMode.WRITING
            }
          ThinkingOrb(
            mode,
            Modifier.size(if (activity == Activity.WRITING) 26.dp else 56.dp),
            frozen = !animate,
            small = activity == Activity.WRITING,
          )
          Spacer(Modifier.width(8.dp))
          AnimatedContent(targetState = activity, transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(100)) }, label = "phaseLabel") { a ->
            val label =
              when (a) {
                Activity.SEARCHING -> if (msg.gallery.isNotEmpty() || msg.sources.isEmpty() && msg.text.isEmpty()) "Searching" else "Searching the web"
                Activity.EMBEDDINGS -> "Searching this phone · EmbeddingGemma running"
                Activity.OCR -> "Reading the text · MiniCPM-OCR running"
                Activity.USING_PHONE -> "Using the phone"
                Activity.READING_IMAGE -> "Reading the image"
                Activity.LISTENING -> "Listening to the audio"
                Activity.THINKING -> "Thinking"
                else -> "Writing"
              }
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
          if (activity == Activity.WRITING && msg.live != null) {
            Text(
              "  %.1f tok/s · %d tok".format(msg.live.tps, msg.live.genTokens),
              style = MaterialTheme.typography.labelLarge.merge(Numeric),
              color = accentText(),
            )
          }
        }
      } else {
        Spacer(Modifier.height(0.dp))
      }
    }

    if (msg.text.isNotEmpty()) {
      if (msg.error) {
        Row(verticalAlignment = Alignment.Top) {
          Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp).padding(top = 2.dp))
          Spacer(Modifier.width(8.dp))
          Text(msg.text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
        }
      } else {
        SelectionContainer { MarkdownText(msg.text) }
      }
    }

    AnimatedVisibility(msg.run != null, enter = fadeIn(tween(250)) + expandVertically(tween(250)), exit = fadeOut()) {
      msg.run?.let { run ->
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
          RunMetrics(run, Modifier.weight(1f))
          CopyButton(msg.text)
        }
      }
    }
    if (msg.gallery.isNotEmpty()) GalleryGrid(msg.gallery, vm)
    msg.texts.forEach { TextCard(it, onSend = { vm.sendTexts(msg.id, it.id) }, onCancel = { vm.cancelTexts(msg.id, it.id) }) }
    if (msg.sources.isNotEmpty() && msg.activity == Activity.DONE) SourcesList(msg.sources)
    // replies of a reopened chat have no metrics, but can still be copied
    if (msg.run == null && msg.activity == Activity.DONE && !msg.error && msg.text.isNotEmpty()) {
      Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { CopyButton(msg.text) }
    }
  }
}

/** The pages a web-search answer was given, numbered as the reply cites them; tap to open in the browser. */
@Composable
private fun SourcesList(sources: List<com.saad.omnichat.net.Source>) {
  val context = LocalContext.current
  Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text("Sources", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    sources.forEach { s ->
      Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
          .clickable { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(s.url))) }
          .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(veil(1.6f)), contentAlignment = Alignment.Center) {
          Text("${s.n}", style = MaterialTheme.typography.labelSmall.merge(Numeric))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
          Text(s.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
          Text(android.net.Uri.parse(s.url).host?.removePrefix("www.") ?: s.url, style = MaterialTheme.typography.labelSmall, color = accentText())
        }
      }
    }
  }
}

/** Copies the reply's Markdown source; the icon turns into a check for a moment (Android 13+ also confirms). */
@Composable
private fun CopyButton(text: String) {
  val context = LocalContext.current
  val view = LocalView.current
  var copied by remember { mutableStateOf(false) }
  LaunchedEffect(copied) {
    if (copied) {
      kotlinx.coroutines.delay(1500)
      copied = false
    }
  }
  IconButton(onClick = {
    context.getSystemService(android.content.ClipboardManager::class.java)
      .setPrimaryClip(android.content.ClipData.newPlainText("LFM3 reply", text))
    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    copied = true
  }) {
    AnimatedContent(
      targetState = copied,
      transitionSpec = { (fadeIn(tween(120, easing = ExpoOut)) + scaleIn(tween(120, easing = ExpoOut), 0.85f)) togetherWith fadeOut(tween(90)) },
      label = "copied",
    ) { done ->
      Icon(
        if (done) Icons.Default.Check else Icons.Outlined.ContentCopy,
        if (done) "Copied" else "Copy reply",
        Modifier.size(18.dp),
        tint = if (done) accentText() else MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/** Exponential ease-out from the impeccable motion guide: quick start, soft landing, no bounce. */
val ExpoOut = androidx.compose.animation.core.CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

@Composable
fun RunMetrics(run: RunRecord, modifier: Modifier = Modifier) {
  val strong = SpanStyle(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
  val text = buildAnnotatedString {
    withStyle(strong) { append("%.1f tok/s".format(run.genTps)) }
    append("  ·  ${run.genTokens} tok  ·  first token %.2f s  ·  total %.1f s  ·  ".format(run.ttftMs / 1000f, run.totalMs / 1000f))
    withStyle(strong) { append(formatMb(run.ramPeakMb)) }
    append(" RAM")
  }
  Text(text, modifier = modifier, style = MaterialTheme.typography.labelMedium.merge(Numeric), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

fun formatMb(mb: Int) = if (mb >= 1024) "%.2f GB".format(mb / 1024f) else "$mb MB"

@Composable
private fun InputBar(
  state: UiState,
  input: String,
  onInput: (String) -> Unit,
  onAttach: () -> Unit,
  onWeb: () -> Unit,
  onMic: (MicMode) -> Unit,
  onRemove: (Long) -> Unit,
  onSend: () -> Unit,
  onStop: () -> Unit,
) {
  Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 10.dp, top = 4.dp)) {
    AnimatedVisibility(state.pending.isNotEmpty(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
      LazyRow(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(state.pending, key = { it.id }) { att ->
          InputChip(
            modifier = Modifier.animateItem(fadeInSpec = tween(200, easing = ExpoOut), fadeOutSpec = tween(120)),
            selected = false,
            onClick = { onRemove(att.id) },
            label = { Text(att.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp)) },
            leadingIcon = { AttachmentThumb(att) },
            trailingIcon = { Icon(Icons.Default.Close, "Remove ${att.label}", Modifier.size(16.dp)) },
            colors = InputChipDefaults.inputChipColors(containerColor = veil(1.4f)),
            border = null,
            shape = CircleShape,
          )
        }
      }
    }

    Box(Modifier.glass(RoundedCornerShape(28.dp), lift = 10.dp)) {
      // buttons sit in 56dp slots: centred on a one-line message, pinned to the last line when it grows
      Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.Bottom) {
        InputSlot {
          IconButton(onClick = onAttach, enabled = !state.generating && !state.recording) { Icon(Icons.Default.Add, "Attach image, audio or document") }
        }
        InputSlot {
          IconButton(onClick = onWeb, enabled = !state.generating && !state.recording) {
            Icon(
              Icons.Outlined.Language,
              if (state.settings.webSearch) "Web search on" else "Web search off",
              tint = if (state.settings.webSearch) accentText() else MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }

        AnimatedContent(
          targetState = state.recording,
          transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(100)) },
          modifier = Modifier.weight(1f),
          label = "inputOrRecording",
        ) { recording ->
          if (recording) {
            RecordingStrip(state.recordingSince, state.micMode)
          } else {
            TextField(
              value = input,
              onValueChange = onInput,
              // one line only: a wrapped placeholder grows the field and knocks the buttons out of line
              placeholder = {
                Text(if (state.pending.isEmpty()) "Message" else "Ask about it", maxLines = 1, overflow = TextOverflow.Ellipsis)
              },
              maxLines = 6,
              textStyle = MaterialTheme.typography.bodyLarge,
              colors =
                TextFieldDefaults.colors(
                  focusedContainerColor = Color.Transparent,
                  unfocusedContainerColor = Color.Transparent,
                  disabledContainerColor = Color.Transparent,
                  focusedIndicatorColor = Color.Transparent,
                  unfocusedIndicatorColor = Color.Transparent,
                ),
              modifier = Modifier.fillMaxWidth(),
            )
          }
        }

        InputSlot { MicButton(state, onMic) }

        val canSend = !state.recording && (input.isNotBlank() || state.pending.isNotEmpty())
        InputSlot {
        AnimatedContent(
          targetState = state.generating,
          transitionSpec = { (fadeIn(tween(150, easing = ExpoOut)) + scaleIn(tween(150, easing = ExpoOut), 0.8f)) togetherWith (fadeOut(tween(100)) + scaleOut(tween(100), 0.8f)) },
          label = "sendStop",
        ) { generating ->
          if (generating) {
            FilledIconButton(onClick = onStop, modifier = Modifier.size(44.dp).shadow(8.dp, CircleShape, clip = false)) { Icon(Icons.Default.Stop, "Stop generating") }
          } else {
            FilledIconButton(onClick = onSend, enabled = canSend, modifier = Modifier.size(44.dp).shadow(if (canSend) 8.dp else 0.dp, CircleShape, clip = false)) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
          }
        }
        }
      }
    }
  }
}

@Composable
private fun InputSlot(content: @Composable () -> Unit) {
  Box(Modifier.height(56.dp), contentAlignment = Alignment.Center) { content() }
}

/**
 * Tap: dictate (your words are typed into the message). Slide up: record a voice note that keeps going until you
 * press stop, then is attached. While recording, a tap stops it.
 */
@Composable
private fun MicButton(state: UiState, onMic: (MicMode) -> Unit) {
  val view = LocalView.current
  val density = androidx.compose.ui.platform.LocalDensity.current
  val slide = with(density) { 48.dp.toPx() }
  var pressed by remember { mutableStateOf(false) }
  var lifted by remember { mutableStateOf(0f) }
  val enabled = !state.generating && state.busyMessage == null
  val recording = state.recording
  Box(contentAlignment = Alignment.Center) {
    // the hint above the button while it is held
    if (pressed && !recording) {
      Text(
        if (lifted >= 1f) "Release: voice note" else "Slide up for a voice note",
        style = MaterialTheme.typography.labelMedium,
        color = if (lifted >= 1f) accentText() else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier.wrapContentSize(unbounded = true).offset(y = (-58).dp).glass(RoundedCornerShape(12.dp), lift = 4.dp).padding(horizontal = 10.dp, vertical = 6.dp),
      )
    }
    Box(
      Modifier.size(44.dp)
        .offset { androidx.compose.ui.unit.IntOffset(0, -(lifted.coerceIn(0f, 1f) * slide * 0.6f).toInt()) }
        .clip(CircleShape)
        .background(if (recording) MaterialTheme.colorScheme.errorContainer else Color.Transparent)
        .pointerInput(enabled, recording) {
          if (!enabled) return@pointerInput
          awaitEachGesture {
            val down = awaitFirstDown()
            if (recording) {
              waitForUpOrCancellation()
              onMic(state.micMode)
              return@awaitEachGesture
            }
            pressed = true
            var dy = 0f
            var locked = false
            while (true) {
              val event = awaitPointerEvent()
              val change = event.changes.firstOrNull { it.id == down.id } ?: break
              dy = down.position.y - change.position.y
              val before = lifted
              lifted = (dy / slide).coerceAtLeast(0f)
              if (before < 1f && lifted >= 1f) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
              if (!change.pressed) break
              change.consume()
            }
            locked = lifted >= 1f
            pressed = false
            lifted = 0f
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            onMic(if (locked) MicMode.ATTACH else MicMode.DICTATE)
          }
        },
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        when {
          !recording -> Icons.Default.Mic
          state.micMode == MicMode.DICTATE -> Icons.Default.Check
          else -> Icons.Default.Stop
        },
        when {
          !recording -> "Tap to dictate, slide up to record a voice note"
          state.micMode == MicMode.DICTATE -> "Done: write down what I said"
          else -> "Stop and attach the voice note"
        },
        tint = when {
          recording -> MaterialTheme.colorScheme.onErrorContainer
          enabled -> MaterialTheme.colorScheme.onSurfaceVariant
          else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        },
      )
    }
  }
}

@Composable
private fun RecordingStrip(since: Long, mode: MicMode) {
  var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
  LaunchedEffect(since) {
    while (true) {
      now = System.currentTimeMillis()
      delay(250)
    }
  }
  val secs = ((now - since) / 1000).coerceAtLeast(0)
  val reduce = LocalOmni.current.reduceMotion
  val alpha =
    if (reduce) 1f
    else rememberInfiniteTransition(label = "rec").animateFloat(1f, 0.25f, infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Reverse), label = "recA").value
  Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(10.dp).alpha(alpha).clip(CircleShape).background(MaterialTheme.colorScheme.error))
    Spacer(Modifier.width(10.dp))
    Text("%d:%02d".format(secs / 60, secs % 60), style = MaterialTheme.typography.bodyLarge.merge(Numeric))
    Spacer(Modifier.width(10.dp))
    Text(
      if (mode == MicMode.DICTATE) "Listening · tap ✓ to type it" else "Voice note · tap ■ to attach",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun AttachmentThumb(att: Attachment) {
  if (att.kind == AttachmentKind.IMAGE && att.uri != null) {
    AsyncImage(model = att.uri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(24.dp).clip(CircleShape))
  } else {
    Box(Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
      Icon(if (att.kind == AttachmentKind.DOCUMENT) Icons.Outlined.Description else Icons.Default.GraphicEq, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.secondary)
    }
  }
}

/** The display's bottom corner radius (rounded screens report it on Android 12+), so the glow hugs the glass. */
@Composable
private fun rememberScreenCornerRadius(): androidx.compose.ui.unit.Dp {
  val view = LocalView.current
  val density = androidx.compose.ui.platform.LocalDensity.current
  return remember(view) {
    val px = view.rootWindowInsets?.getRoundedCorner(android.view.RoundedCorner.POSITION_BOTTOM_LEFT)?.radius ?: 0
    with(density) { px.toDp() }
  }
}

/** Photos and videos a gallery search found: tap one to open it in the phone's gallery app. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun GalleryGrid(items: List<com.saad.omnichat.memory.GalleryItem>, vm: ChatViewModel) {
  var around by remember { mutableStateOf<com.saad.omnichat.memory.GalleryItem?>(null) }
  androidx.compose.foundation.layout.FlowRow(
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
    modifier = Modifier.padding(top = 10.dp),
  ) {
    items.forEach { item -> GalleryTile(item, onNearby = { around = item }) }
  }
  Text(
    "Tap to open in your gallery app · hold for photos taken around the same time",
    style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(top = 6.dp),
  )
  around?.let { center ->
    val nearby by androidx.compose.runtime.produceState<List<com.saad.omnichat.memory.GalleryItem>?>(null, center) { value = vm.galleryNearby(center) }
    androidx.compose.material3.AlertDialog(
      onDismissRequest = { around = null },
      confirmButton = { TextButton(onClick = { around = null }) { Text("Close") } },
      title = { Text("Around ${galleryTime(center.taken)}") },
      text = {
        val list = nearby
        when {
          list == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
          list.isEmpty() -> Text("Nothing else was taken within a few hours.")
          else -> androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
            androidx.compose.foundation.lazy.grid.GridCells.Adaptive(96.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.heightIn(max = 460.dp),
          ) {
            items(list.size) { k -> GalleryTile(list[k], highlight = list[k] == center, onNearby = null) }
          }
        }
      },
    )
  }
}

/** A found photo or video: thumbnail, name and when it was taken. Tap opens it in the gallery app. */
@Composable
private fun GalleryTile(item: com.saad.omnichat.memory.GalleryItem, highlight: Boolean = false, onNearby: (() -> Unit)?) {
  val context = androidx.compose.ui.platform.LocalContext.current
  val thumb by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, item.id) {
    value =
      withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching { context.contentResolver.loadThumbnail(item.uri, android.util.Size(256, 256), null).asImageBitmap() }.getOrNull()
      }
  }
  Column(Modifier.width(96.dp)) {
    Box(
      Modifier.size(96.dp)
        .clip(RoundedCornerShape(12.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant)
        .then(if (highlight) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)) else Modifier)
        .combinedClickable(
          onClick = { openInGallery(context, item) },
          onLongClick = onNearby,
        ),
    ) {
      thumb?.let { androidx.compose.foundation.Image(it, item.name, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
      if (item.video)
        Icon(
          Icons.Default.PlayArrow,
          "Video",
          tint = Color.White,
          modifier = Modifier.align(Alignment.Center).size(28.dp).background(Color.Black.copy(alpha = 0.45f), CircleShape).padding(4.dp),
        )
    }
    Text(item.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
    Text(galleryTime(item.taken), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
  }
}

private fun galleryTime(ms: Long): String = java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ms))

/** Opens the item in the phone's gallery app (Samsung Gallery or Google Photos), where nearby shots are a swipe away. */
private fun openInGallery(context: android.content.Context, item: com.saad.omnichat.memory.GalleryItem) {
  val view = android.content.Intent(android.content.Intent.ACTION_VIEW)
    .setDataAndType(item.uri, if (item.video) "video/*" else "image/*")
    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
  // prefer the phone's own gallery over a plain viewer
  val gallery = listOf("com.sec.android.gallery3d", "com.google.android.apps.photos").firstOrNull { pkg ->
    view.setPackage(pkg).resolveActivity(context.packageManager) != null
  }
  view.setPackage(gallery)
  runCatching { context.startActivity(view) }.onFailure { runCatching { context.startActivity(view.setPackage(null)) } }
}

/** Texts the model prepared: who they go to and the message, sent only when the user taps Send. */
@Composable
private fun TextCard(batch: com.saad.omnichat.tools.TextBatch, onSend: () -> Unit, onCancel: () -> Unit) {
  val st = batch.state
  Column(Modifier.padding(top = 10.dp).fillMaxWidth().glass(RoundedCornerShape(16.dp), lift = 2.dp).padding(14.dp)) {
    Text(
      when (st) {
        com.saad.omnichat.tools.TextBatch.State.WAITING -> "Text to ${batch.to.size} ${if (batch.to.size == 1) "person" else "people"}, ready to send"
        com.saad.omnichat.tools.TextBatch.State.SENDING -> "Sending…"
        com.saad.omnichat.tools.TextBatch.State.DONE -> "Sent ${batch.to.count { it.ok }} of ${batch.to.size}"
        com.saad.omnichat.tools.TextBatch.State.CANCELLED -> "Not sent"
      },
      style = MaterialTheme.typography.titleSmall,
    )
    Spacer(Modifier.height(6.dp))
    Text("\u201C${batch.message}\u201D", style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(8.dp))
    batch.to.forEach { t ->
      Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${t.name} · ${t.number}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        t.status?.let { Text(if (t.ok) "✓ Sent" else "✗ $it", style = MaterialTheme.typography.labelSmall, color = if (t.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
      }
    }
    if (st == com.saad.omnichat.tools.TextBatch.State.WAITING) {
      Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onCancel) { Text("Cancel") }
        androidx.compose.material3.Button(onClick = onSend) { Text(if (batch.to.size == 1) "Send" else "Send to all ${batch.to.size}") }
      }
    }
  }
}
