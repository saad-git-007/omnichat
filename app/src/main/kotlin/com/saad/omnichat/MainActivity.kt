package com.saad.omnichat

import android.os.Bundle
import android.system.Os
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.saad.omnichat.ui.chat.ChatScreen
import com.saad.omnichat.ui.chat.ChatViewModel
import com.saad.omnichat.ui.chat.InstructionsScreen
import com.saad.omnichat.ui.logs.LogsScreen
import com.saad.omnichat.ui.logs.NPU_PROFILE_KEY
import com.saad.omnichat.ui.stats.StatsScreen
import com.saad.omnichat.ui.theme.Backdrop
import com.saad.omnichat.ui.theme.LocalOmni
import com.saad.omnichat.ui.theme.OmniChatTheme

private enum class Screen { CHAT, STATS, INSTRUCTIONS, LOGS, VOICE, TRANSCRIBE }

class MainActivity : ComponentActivity() {
  override fun onStart() {
    super.onStart()
    GenerationService.activityStarted()
  }

  override fun onResume() {
    super.onResume()
    // the next draft the model prepared (several recipients: one compose screen each)
    window.decorView.postDelayed({ if (GenerationService.appVisible) com.saad.omnichat.tools.PhoneQueue.resume(this) }, 600)
  }

  override fun onStop() {
    GenerationService.activityStopped()
    super.onStop()
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // the background progress / "reply ready" notifications; replies still finish without it
    if (savedInstanceState == null && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
      requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
    }
    AppLog.start(applicationContext)
    if (getSharedPreferences("settings", MODE_PRIVATE).getBoolean(NPU_PROFILE_KEY, false)) {
      Os.setenv("GGML_HEXAGON_PROFILE", "1", true)
      AppLog.i("NPU op profiling on")
    }
    if (savedInstanceState == null && CrashReports.saveLastCrash(this)) {
      AppLog.w("the previous run crashed; report saved for Settings > Logs")
      // also in this run's log, so a pasted log carries the crash with it
      runCatching { CrashReports.file(this).readLines().take(40).forEach { AppLog.w("crash: $it") } }
      Toast.makeText(this, "The app crashed last time. Details are in Settings > Logs.", Toast.LENGTH_LONG).show()
    }

    enableEdgeToEdge()
    setContent {
      OmniChatTheme {
        Backdrop {
          val vm: ChatViewModel = viewModel()
          var screen by rememberSaveable { mutableStateOf(Screen.CHAT) }
          val reduce = LocalOmni.current.reduceMotion
          BackHandler(enabled = screen != Screen.CHAT) { screen = Screen.CHAT }

          // Material shared-axis X: stats sits one level deeper than chat
          AnimatedContent(
            targetState = screen,
            transitionSpec = {
              if (reduce) {
                fadeIn(tween(0)) togetherWith fadeOut(tween(0))
              } else {
                val dir = if (targetState != Screen.CHAT) 1 else -1
                (slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { dir * it / 8 } + fadeIn(tween(210, delayMillis = 90))) togetherWith
                  (slideOutHorizontally(tween(300, easing = FastOutSlowInEasing)) { -dir * it / 8 } + fadeOut(tween(90)))
              }
            },
            label = "screen",
          ) { target ->
            val state by vm.state.collectAsStateWithLifecycle()
            when (target) {
              Screen.STATS ->
                StatsScreen(state.runs, onBack = { screen = Screen.CHAT }, onClear = vm::clearStats, onExport = vm::exportStats)
              Screen.INSTRUCTIONS ->
                InstructionsScreen(
                  state.instructions,
                  onBack = { screen = Screen.CHAT },
                  onAdd = vm::addInstruction,
                  onUpdate = vm::updateInstruction,
                  onDelete = vm::deleteInstruction,
                )
              Screen.LOGS -> LogsScreen(onBack = { screen = Screen.CHAT })
              Screen.VOICE -> com.saad.omnichat.ui.chat.VoiceScreen(vm, onBack = { screen = Screen.CHAT })
              Screen.TRANSCRIBE -> com.saad.omnichat.ui.chat.TranscribeScreen(vm, onBack = { screen = Screen.CHAT })
              Screen.CHAT ->
                ChatScreen(
                  vm,
                  onOpenStats = { screen = Screen.STATS },
                  onOpenInstructions = { screen = Screen.INSTRUCTIONS },
                  onOpenLogs = { screen = Screen.LOGS },
                  onOpenVoice = { screen = Screen.VOICE },
                  onOpenTranscribe = { screen = Screen.TRANSCRIBE },
                )
            }
          }
        }
      }
    }
  }
}
