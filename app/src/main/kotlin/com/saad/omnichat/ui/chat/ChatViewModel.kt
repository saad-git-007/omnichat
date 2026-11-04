package com.saad.omnichat.ui.chat

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.saad.omnichat.history.ChatStore
import com.saad.omnichat.history.ChatSummary
import com.saad.omnichat.history.SavedAttachment
import com.saad.omnichat.history.SavedChat
import com.saad.omnichat.history.SavedMessage
import com.saad.omnichat.llm.Backend
import com.saad.omnichat.llm.DOC_END
import com.saad.omnichat.llm.Embedder
import com.saad.omnichat.memory.GalleryHit
import com.saad.omnichat.memory.GalleryIndex
import com.saad.omnichat.memory.GalleryItem
import com.saad.omnichat.memory.SemanticIndex
import com.saad.omnichat.tools.PhoneTools
import com.saad.omnichat.tools.ToolCall
import com.saad.omnichat.tools.ToolCalls
import com.saad.omnichat.tools.ToolSpec
import com.saad.omnichat.AppLog
import com.saad.omnichat.GenerationService
import com.saad.omnichat.LiveTranscribeService
import com.saad.omnichat.llm.EngineException
import com.saad.omnichat.llm.VoiceEvent
import com.saad.omnichat.media.SpeechPlayer
import com.saad.omnichat.llm.GenEvent
import com.saad.omnichat.llm.Media
import com.saad.omnichat.llm.OmniEngine
import com.saad.omnichat.live.MeetingNotes
import com.saad.omnichat.media.DocumentReader
import com.saad.omnichat.media.TableHints
import com.saad.omnichat.media.AudioStream
import com.saad.omnichat.net.Source
import com.saad.omnichat.net.WebSearch
import com.saad.omnichat.media.MediaDecoder
import com.saad.omnichat.media.MicRecorder
import com.saad.omnichat.model.ModelStore
import com.saad.omnichat.model.Models
import com.saad.omnichat.stats.BackendKind
import com.saad.omnichat.stats.ProcMem
import com.saad.omnichat.stats.RunRecord
import com.saad.omnichat.stats.StatsStore
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class AttachmentKind { IMAGE, AUDIO, DOCUMENT }

data class Attachment(val id: Long, val kind: AttachmentKind, val label: String, val uri: Uri?, val media: Media)

enum class Role { USER, ASSISTANT, NOTICE }

/** What the model is truthfully doing for the reply in progress. */
enum class Activity { SEARCHING, EMBEDDINGS, OCR, USING_PHONE, READING_IMAGE, LISTENING, THINKING, WRITING, DONE }

data class LiveStats(val genTokens: Int = 0, val tps: Float = 0f, val prefillMs: Long = 0, val promptTokens: Int = 0)

data class ChatMessage(
  val id: Long,
  val role: Role,
  val text: String,
  val attachments: List<Attachment> = emptyList(),
  val activity: Activity = Activity.DONE,
  val live: LiveStats? = null,
  val run: RunRecord? = null,
  val error: Boolean = false,
  val sources: List<Source> = emptyList(), // web pages the reply was given, numbered as cited
  val gallery: List<GalleryItem> = emptyList(), // photos and videos the reply found on the phone
  val texts: List<com.saad.omnichat.tools.TextBatch> = emptyList(), // texts waiting for the user's Send tap
)

sealed interface Phase {
  data object NeedsDownload : Phase

  data class Downloading(val done: Long, val total: Long) : Phase

  data class Loading(val backend: String) : Phase

  data object Ready : Phase

  data class Failed(val message: String) : Phase
}

data class Settings(
  val backend: String? = null, // ggml device name, "" = CPU, null = auto
  val temperature: Float = 0f,
  val maxTokens: Int = 2048,
  val nCtx: Int = 16384,
  val threads: Int = 4,
  val animateWhileGenerating: Boolean = true,
  // image/audio encoder on the GPU/NPU too: 8x faster on the S23's NPU (1.3 s vs 10.5 s per image)
  val mediaOnGpu: Boolean = true,
  // Q4_0 copy of the 205 MB embedding/output table that every token reads (made once on the phone)
  val fastHead: Boolean = false,
  // web search: the question goes to Exa (search) and Firecrawl (page reading) with the user's own keys
  val webSearch: Boolean = false,
  val exaKey: String = "",
  val firecrawlKey: String = "",
  // voice chat: the language and voice the model speaks in (codes from its "tts" template)
  val voiceLang: String = "en",
  val voiceGender: String = "female",
  // live transcribe writes down this many seconds at a time: longer blocks read whole sentences, shorter ones show sooner
  val liveSeconds: Int = LIVE_SECONDS_DEFAULT,
  // ...and meanwhile notes action items, decisions and key points, between pieces (see MeetingNotes)
  val meetingNotes: Boolean = true,
  // voice chat shows how fast it reads, writes and speaks
  val voiceSpeed: Boolean = true,
  // ...with LFM3-Audio, swapped in for the chat model while it listens (downloads 1.35 GB once)
  val speechModel: Boolean = true,
  // the model may use the phone: alarms, calendar, messages, maps, calculator... (each opens the app to confirm)
  val phoneTools: Boolean = true,
  // EmbeddingGemma 2: search past chats and long documents by meaning (downloads 310 MB once)
  val smartSearch: Boolean = false,
  // ...and photos/videos in the gallery (downloads its 555 MB image tower, indexes in the background)
  val gallerySearch: Boolean = false,
  // only photos and videos from the last this many months are indexed (0 = all)
  val galleryMonths: Int = 0,
  // Xiaomi-OCR-0: reads the text in scanned PDF pages and photos (downloads 0.75 GB once)
  val ocr: Boolean = false,
  val systemPrompt: String = "You are a helpful assistant.",
)

/** The languages LFM3 can speak, from its "tts" chat template. */
val VoiceLanguages =
  linkedMapOf(
    "en" to "English (American)", "en-gb" to "English (British)", "ar" to "Arabic", "zh" to "Chinese", "da" to "Danish",
    "nl" to "Dutch", "fi" to "Finnish", "fr" to "French", "de" to "German", "el" to "Greek", "he" to "Hebrew",
    "it" to "Italian", "ja" to "Japanese", "ko" to "Korean", "pl" to "Polish", "pt" to "Portuguese", "es" to "Spanish",
    "sv" to "Swedish", "tr" to "Turkish",
  )

enum class VoicePhase { IDLE, LISTENING, THINKING, SPEAKING }

/** The speed line of voice chat: [readMillis] to read the picture and question, then writing and voice speed. */
data class VoiceSpeed(val promptTokens: Int, val readMillis: Long, val tokensPerSecond: Float? = null, val realTime: Float? = null)

/** Voice chat screen state; its turns are also added to the chat. */
data class VoiceUi(
  val phase: VoicePhase = VoicePhase.IDLE,
  val reply: String = "",
  val photo: Attachment? = null,
  val live: LiveTranscript? = null,
  // live camera: each question goes with what the camera sees as you finish asking ([frontCamera]: the selfie one)
  val camera: Boolean = false,
  val frontCamera: Boolean = false,
  // with the camera on, the conversation is hands-free: true once your voice is heard (it sends when you pause)
  val heard: Boolean = false,
  val speed: VoiceSpeed? = null,
)

/** A saved custom instruction, applied to the system prompt of every new chat while enabled. */
data class Instruction(val id: Long, val text: String, val enabled: Boolean = true)

val SuggestedInstructions =
  listOf(
    "Keep responses concise.",
    "Use bullet points for lists and steps.",
    "Explain things simply, without jargon.",
    "Reply in the language I write in.",
  )

data class UiState(
  val phase: Phase = Phase.NeedsDownload,
  val backends: List<Backend> = emptyList(),
  val deviceInfo: String = "",
  val messages: List<ChatMessage> = emptyList(),
  val pending: List<Attachment> = emptyList(),
  val generating: Boolean = false,
  val liveTps: Float? = null,
  val recording: Boolean = false,
  val recordingSince: Long = 0,
  val runs: List<RunRecord> = emptyList(),
  val instructions: List<Instruction> = emptyList(),
  val busyMessage: String? = null,
  val settings: Settings = Settings(),
  val chats: List<ChatSummary> = emptyList(),
  val voice: VoiceUi = VoiceUi(),
  val chatId: Long? = null, // saved conversation shown, null until the first reply of a new chat
  val searchStatus: String? = null, // smart search: download / indexing progress, shown in Settings
  val ocrStatus: String? = null, // OCR model download progress, shown in Settings
  val speechStatus: String? = null, // LFM3-Audio download progress, shown in Settings
  val liveModel: String? = null, // the model live transcribe writes with ("LFM3-Audio", or loading it)
  val conversation: Int = 0, // bumped when another chat is shown, so the message list starts fresh
  val micMode: MicMode = MicMode.DICTATE, // what the running recording is for
  val dictated: String? = null, // words from the last dictation, for the input field to take
)

/** Tap the mic: speak, and your words are typed into the message. Slide it up: record a voice note to attach. */
enum class MicMode { DICTATE, ATTACH }

/** ~12.5 audio tokens per second: 10 minutes is ~7.5k tokens, which leaves room in a 16k context. */
private const val MAX_AUDIO_SECONDS = 600

/** Longer recordings are transcribed in pieces of this length (~3.75k audio tokens each). */
private const val SEGMENT_SECONDS = 60 // one encoder pass each; see OmniEngine.attach
private const val NEARBY_MS = 6 * 3_600_000L // "around the same time" for gallery results
// live transcribe writes down this much at a time (Settings), or more when the phone lags. 30 s is ~400 audio tokens,
// well inside the 2k side context it is transcribed in
const val LIVE_SECONDS_DEFAULT = 5
val LIVE_SECONDS_RANGE = 3..30
private const val STAMP_EVERY_SECONDS = 300

private const val MAX_TOOL_CALLS = 4

// a camera frame for the model: the image encoder sees it in 512 px tiles, so larger only costs time
private const val CAMERA_MAX_SIDE = 1024
private const val CAMERA_KEEP = 20
private const val SPEECH_BUSY = "Live transcribe is using the speech model. Stop it (and let its notes finish) to chat."
private const val MIC_BUSY = "The microphone couldn't be opened. Another app (or a call) may be using it, or the permission was turned off."

/** Largest context that fits the Hexagon NPU's ~3.1 GB address space next to the model and its encoders. */
const val NPU_MAX_CTX = 32768
// the speech model's own chat context: unused (live transcribe and its notes run in the 2k side context)
private const val SPEECH_CTX = 2048

// hands-free listening (camera conversations): a 1.2 s pause ends a question, and 30 s of talking sends it anyway
private const val PAUSE_MS = 1200
private const val MAX_UTTERANCE_MS = 30_000

/** Room kept free for a camera turn: the picture (~1k tokens), the spoken question and the answer. */
private const val CAMERA_TURN_ROOM = 3000

/** Asking for the words themselves ("read it out", "transcribe this", "extract the text"): the OCR model reads exactly. */
private val WANTS_EXACT_TEXT = Regex("""\b(read (it|this|out|all|the text|everything)|transcribe|extract|copy (the|all|out)|word for word|exact (text|words|wording)|all the text|what does (it|this) say|type (it|this) out)""", RegexOption.IGNORE_CASE)

/** The photo check reads a copy this size: enough to see rows and columns, ~250 tokens. */
private const val PHOTO_CHECK_SIDE = 512

/** While a reply streams, the screen gets its text at most this often (ms). */
private const val TEXT_FRAME_MS = 60L

/** Meeting summaries take this many key points at a time (~600 tokens, well inside the 2k side context). */
private const val SUMMARY_GROUP = 30

/** Tokens of the context kept free of documents: the system turn (~1,300 with tools), the question and the answer. */
private const val CHAT_RESERVE_TOKENS = 4096

/** The table facts put next to a question are left out when fewer tokens than this would be left for the answer. */
private const val ANSWER_ROOM_TOKENS = 1024

/** A long document is searched by meaning up to this size; its opening goes into the prompt as-is. */
private const val MAX_INDEXED_CHARS = 4_000_000
private const val DOC_OPENING_CHARS = 6000
private const val WEB_TOOL_DESCRIPTION =
  "Search the web for current information: news, weather, prices, scores, schedules, opening hours, or a page the user asks you to read. Not for general knowledge you already have. Results come back numbered; cite them as [1], [2]."

/**
 * Ends each user turn while tools are offered. The 2B model calls a tool for almost anything when it only has the
 * system turn's rules (a haiku opened Wikipedia, "say OK" set an alarm); this reminder, read right before it answers,
 * took a 64-message test from 44 to 62 right with every real request still calling its tool.
 */
/**
 * Words an English message uses when it really wants a phone action that opens another app. The model sometimes
 * calls one confidently for an unrelated message; such a call runs only if the typed message has one of these.
 * Messages that aren't mostly English, and voice turns (no typed text), are left to the model.
 */
private val ACTION_WORDS = mapOf(
  "set_alarm" to Regex("""alarm|wake|\d"""),
  "set_timer" to Regex("""timer|remind|count ?down|minute|second|hour|\d"""),
  "add_calendar_event" to Regex("""calendar|event|remind|schedul|appointment|meeting|book|add|\d"""),
  "draft_text_message" to Regex("""text|message|sms|tell|send|let .+ know|whatsapp|reply"""),
  "draft_email" to Regex("""e-?mail|mail|@|send|write to|reply"""),
  "dial" to Regex("""call|dial|phone|ring|\d{3}"""),
  "open_map" to Regex("""map|direction|navigat|route|where|take me|drive|walk|get to|how far|near"""),
  "open_website" to Regex("""open|website|site|page|link|browser|url|go to|visit|\w\.[a-z]{2,}"""),
)

internal fun asksFor(tool: String, typed: String): Boolean {
  val words = ACTION_WORDS[tool] ?: return true
  val letters = typed.count { it.isLetter() }
  if (letters == 0 || typed.count { it in 'a'..'z' || it in 'A'..'Z' } < letters * 0.8) return true
  return words.containsMatchIn(typed.lowercase())
}

private val STRAY_CITE = Regex(""" ?\[(\d{1,3})\](?!\()""")

private const val TOOL_HINT = "\n\n(Answer directly unless this message needs live information, something stored on the phone, or a phone action.)"
private const val WEB_TOOL_PARAMETERS =
  """{"type":"object","properties":{"query":{"type":"string","description":"A short web search query, or a URL to read"}},"required":["query"]}"""

private const val TRANSCRIBE_SYSTEM = "You are a careful transcriber. You write down exactly what is said, in the language it is said in."
private const val TRANSCRIBE_PROMPT = "Transcribe this audio word for word. Output only the transcript, with no comments."

class ChatViewModel(app: Application) : AndroidViewModel(app) {
  private val engine = OmniEngine(app)
  private val phone = PhoneTools(app)
  private val embedder = Embedder(app)
  private val semantic = SemanticIndex(app, embedder)
  private val gallery = GalleryIndex(app, embedder)
  private var galleryJob: Job? = null
  private val store = ModelStore(app)
  private val ocr = com.saad.omnichat.llm.Ocr(app, store)
  private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
  private val statsStore = StatsStore(app)
  private val chatStore = ChatStore(app)
  private val ids = AtomicLong()
  private var genJob: Job? = null
  private var mic: MicRecorder? = null

  private val _state = MutableStateFlow(UiState(settings = readSettings(), instructions = readInstructions()))
  val state: StateFlow<UiState> = _state

  val modelDir: String = store.dir.absolutePath

  init {
    if (store.allReady()) loadModel() else _state.update { it.copy(phase = Phase.NeedsDownload) }
    viewModelScope.launch { runCatching { statsStore.load() }.onSuccess { r -> _state.update { it.copy(runs = r) } }.onFailure { AppLog.e("reading stats failed", it) } }
    viewModelScope.launch { _state.update { it.copy(chats = chatStore.list()) } }
    restoreLive()
  }

  /** Reopens a saved conversation; its text goes back to the model as history with the next message. */
  fun openChat(id: Long) {
    if (_state.value.generating || id == _state.value.chatId) return
    viewModelScope.launch {
      val chat = chatStore.load(id) ?: return@launch notice("That conversation could not be read.")
      val messages =
        chat.messages.map { m ->
          ChatMessage(
            ids.incrementAndGet(),
            if (m.role == "user") Role.USER else Role.ASSISTANT,
            m.text,
            m.attachments.map { a ->
              val kind = runCatching { AttachmentKind.valueOf(a.kind) }.getOrDefault(AttachmentKind.DOCUMENT)
              Attachment(ids.incrementAndGet(), kind, a.label, null, Media.Document(a.label, a.text ?: "", index = a.index))
            },
          )
        }
      if (_state.value.phase == Phase.Ready && !speechLoaded) engine.reset(systemPrompt(), modelHistory(messages))
      _state.update { it.copy(messages = messages, chatId = chat.id, liveTps = null, conversation = it.conversation + 1) }
    }
  }

  fun deleteChat(id: Long) {
    viewModelScope.launch {
      chatStore.delete(id)
      semantic.deleteChat(id)
      if (_state.value.chatId == id) newChat()
      _state.update { st -> st.copy(chats = st.chats.filterNot { it.id == id }) }
    }
  }

  private fun modelHistory(messages: List<ChatMessage>) =
    messages.filter { (it.role == Role.USER || it.role == Role.ASSISTANT) && !it.error && it.activity == Activity.DONE }.map { modelRole(it) to modelText(it) }

  private fun modelRole(m: ChatMessage) = if (m.role == Role.USER) "user" else "assistant"

  /** What the model sees for a message: documents inline; images and audio from a reopened chat only by name. */
  private fun modelText(m: ChatMessage): String {
    if (m.role != Role.USER) return m.text
    val docs = m.attachments.filter { it.kind == AttachmentKind.DOCUMENT }.map { it.media }.filterIsInstance<Media.Document>()
    // restored from a saved chat: the pixels/samples are gone, only a Document placeholder remains
    val earlierMedia = m.attachments.filter { it.kind != AttachmentKind.DOCUMENT && it.media is Media.Document }
    return buildString {
      docs.forEach {
        append("<document name=\"").append(it.name).append("\">\n").append(TableHints.forModel(it.name, it.text, partial = it.truncated || it.index != null))
        if (it.index != null) append("\n[… the document continues; the passages that matter for each question are given with it]")
        append("\n</document>\n\n")
      }
      earlierMedia.forEach { append("[").append(if (it.kind == AttachmentKind.IMAGE) "image" else "audio").append(" shared earlier: ").append(it.label).append("]\n") }
      append(m.text)
    }
  }

  private suspend fun saveChat() {
    val st = _state.value
    val turns = st.messages.filter { (it.role == Role.USER || it.role == Role.ASSISTANT) && !it.error }
    if (turns.none { it.role == Role.ASSISTANT && it.text.isNotEmpty() }) return
    val id = st.chatId ?: System.currentTimeMillis()
    val first = turns.firstOrNull { it.role == Role.USER }
    val title = (first?.text?.takeIf { it.isNotBlank() } ?: first?.attachments?.firstOrNull()?.label ?: "Chat").lines().first().take(60)
    val chat =
      SavedChat(
        id,
        title,
        System.currentTimeMillis(),
        turns.map { m ->
          SavedMessage(
            modelRole(m),
            m.text,
            m.attachments.map { a ->
              val doc = (a.media as? Media.Document)?.takeIf { a.kind == AttachmentKind.DOCUMENT }
              SavedAttachment(a.kind.name, a.label, doc?.text, doc?.index)
            },
          )
        },
      )
    chatStore.save(chat)
    _state.update { s -> s.copy(chatId = id, chats = listOf(ChatSummary(id, title, chat.updated)) + s.chats.filterNot { it.id == id }) }
    if (embedder.loaded) viewModelScope.launch { runCatching { semantic.indexChat(chat) }.onFailure { AppLog.w("indexing chat failed: ${it.message}") } }
  }

  fun clearStats() {
    viewModelScope.launch {
      statsStore.clear()
      _state.update { it.copy(runs = emptyList()) }
    }
  }

  /** Returns the saved file name, or an error message prefixed with "!" */
  suspend fun exportStats(): String =
    runCatching { statsStore.exportCsv(_state.value.runs) }.getOrElse { "!" + (it.message ?: "export failed") }

  fun download() {
    viewModelScope.launch {
      _state.update { it.copy(phase = Phase.Downloading(0, Models.ALL.sumOf { m -> m.size })) }
      try {
        store.downloadAll { done, total -> _state.update { it.copy(phase = Phase.Downloading(done, total)) } }
        loadModel()
      } catch (e: Exception) {
        AppLog.e("download failed", e)
        _state.update { it.copy(phase = Phase.Failed("Download failed: ${e.message}")) }
      }
    }
  }

  fun loadModel() {
    viewModelScope.launch {
      val settings = _state.value.settings
      val backends = engine.backends().map { if (vulkanBroken(it)) it.copy(label = it.label + " · fails on this phone") else it }
      _state.update { it.copy(backends = backends) }
      val wanted = backends.firstOrNull { it.device == settings.backend } ?: pickDefault(backends)
      if (!tryLoad(wanted) && !wanted.isCpu) {
        notice("${wanted.label} failed to load the model, falling back to CPU")
        tryLoad(Backend.CPU)
      }
    }
  }

  // Measured on an S23 Ultra: Hexagon NPU 28.5 tok/s and ~1300 tok/s prompt, CPU ~24, OpenCL ~14,
  // Vulkan can't build its kernels. So the NPU when there is one, else the CPU.
  private fun pickDefault(backends: List<Backend>) = backends.firstOrNull { it.device.startsWith("HTP") } ?: Backend.CPU

  private var switchToCpu = false

  private fun vulkanBroken(b: Backend) = b.device.startsWith("Vulkan") && prefs.getBoolean("vulkanBroken", false)

  /**
   * The Adreno 740 Vulkan driver can't build ggml's matmul kernels at any precision (createComputePipeline fails for
   * matmul_quant_f32[_f16acc]), so any prompt past 8 tokens fails. Remember that, fall back to the CPU and reload.
   */
  private fun vulkanAdvice(e: EngineException): String? {
    if (e.message?.startsWith("The GPU driver") != true || engine.loadedBackend?.device?.startsWith("Vulkan") != true) return null
    prefs.edit().putBoolean("vulkanBroken", true).apply()
    _state.update { st -> st.copy(backends = st.backends.map { if (vulkanBroken(it) && !it.label.endsWith("phone")) it.copy(label = it.label + " · fails on this phone") else it }) }
    AppLog.w("Vulkan marked as not working on this device; switching to CPU")
    switchToCpu = true
    return "This phone's Vulkan driver can't run this model, so the app switched to CPU. Send your message again once the model has reloaded."
  }

  private suspend fun tryLoad(backend: Backend): Boolean {
    val s = _state.value.settings
    _state.update { it.copy(phase = Phase.Loading(backend.label)) }
    return try {
      val model =
        if (!s.fastHead) store.file(Models.MODEL)
        else runCatching { fastHeadModel(backend, s.threads) }.getOrElse { e ->
          AppLog.e("output layer conversion failed", e)
          notice("Couldn't make the faster model (${e.message}); using the original.")
          store.file(Models.MODEL)
        }
      _state.update { it.copy(phase = Phase.Loading(backend.label)) }
      // The NPU session can map ~3.1 GB: model 0.93 + image/audio encoders ~1.1 + buffers ~0.3 leave room for a 32k
      // context (384 MB); at 64k (768 MB) long prompts failed on the NPU mid-way. Larger contexts run on the CPU.
      val nCtx = if (backend.device.startsWith("HTP")) minOf(s.nCtx, NPU_MAX_CTX) else s.nCtx
      if (nCtx < s.nCtx) notice("The NPU has room for a 32k context, so ${s.nCtx / 1024}k is used on the CPU only. On the NPU the context is 32k (about 70,000 characters of document).")
      val info = engine.load(model.path, store.file(Models.MMPROJ).path, backend, nCtx, s.threads, s.mediaOnGpu)
      chatModelPath = model.path
      engine.reset(systemPrompt())
      _state.update { it.copy(phase = Phase.Ready, deviceInfo = info, liveModel = null) }
      startSearch()
      startOcr()
      startSpeech()
      true
    } catch (e: EngineException) {
      AppLog.e("load failed: ${e.message}")
      _state.update { it.copy(phase = Phase.Failed(e.message ?: "load failed")) }
      false
    }
  }

  /** The Q4_0-head variant of the model, converted on first use (about a minute, needs ~1 GB free). */
  private suspend fun fastHeadModel(backend: Backend, threads: Int): java.io.File {
    val out = java.io.File(store.dir, Models.MODEL.name.removeSuffix(".gguf") + "-q4head.gguf")
    if (out.length() > 0) return out
    _state.update { it.copy(phase = Phase.Loading("${backend.label} (optimizing the model once, about a minute)")) }
    val tmp = java.io.File(out.path + ".part")
    engine.quantizeHead(store.file(Models.MODEL).path, tmp.path, threads)
    check(tmp.renameTo(out)) { "could not save ${out.name}" }
    return out
  }

  /** The context the model was actually loaded with (the NPU caps it at [NPU_MAX_CTX]). */
  private fun effectiveCtx(): Int {
    val s = _state.value.settings
    return if (engine.loadedBackend?.device?.startsWith("HTP") == true) minOf(s.nCtx, NPU_MAX_CTX) else s.nCtx
  }

  private fun systemPrompt(): String {
    val active = _state.value.instructions.filter { it.enabled && it.text.isNotBlank() }
    var p = _state.value.settings.systemPrompt
    if (offered.isNotEmpty()) {
      val today = java.text.SimpleDateFormat("EEEE d MMMM yyyy", java.util.Locale.US).format(java.util.Date())
      // the date only: a clock time would change the system turn every minute and defeat the saved document state
      p += "\n\nToday is $today.\n\nTool rules:\n- Answer from your own knowledge whenever you can: writing, jokes, explanations, " +
        "translations, recipes, advice, history, books, simple maths and small talk need no tool."
      if ("web_search" in offered)
        p += "\n- Use web_search only for things that change or that you can't know: news, weather, prices, scores, schedules, " +
          "or when the user asks you to look something up. Cite what it found as [1], [2], and only that: no numbers in brackets for attached files or your own knowledge."
      if ("set_alarm" in offered)
        p += "\n- Phone actions (set_alarm, set_timer, add_calendar_event, draft_text_message, draft_email, dial, open_map, open_website) " +
          "open another app, where the user confirms. Use one only when the user's latest message asks for that exact action, like " +
          "\"wake me at 7\", \"text Sara I'm late\" or \"open youtube.com\". Never use them to show or illustrate an answer."
      if ("search_past_chats" in offered) p += "\n- Use search_past_chats when the user refers to an earlier conversation."
      if ("search_gallery" in offered) p += "\n- Use search_gallery to find the user's photos and videos; the app shows the results, so just say what you found."
      p += "\n- If no rule above applies, answer directly without a tool."
    }
    if (active.isEmpty()) return p
    return p +
      "\n\nThe user set these instructions. Follow them in every reply unless the user asks otherwise:\n" +
      active.joinToString("\n") { "- " + it.text.trim() }
  }

  fun addInstruction(text: String) {
    if (text.isBlank()) return
    setInstructions(_state.value.instructions + Instruction(System.currentTimeMillis(), text.trim()))
  }

  fun updateInstruction(i: Instruction) = setInstructions(_state.value.instructions.map { if (it.id == i.id) i else it })

  fun deleteInstruction(id: Long) = setInstructions(_state.value.instructions.filterNot { it.id == id })

  private fun setInstructions(list: List<Instruction>) {
    _state.update { it.copy(instructions = list) }
    val arr = org.json.JSONArray()
    list.forEach { arr.put(org.json.JSONObject().put("id", it.id).put("text", it.text).put("enabled", it.enabled)) }
    prefs.edit().putString("instructions", arr.toString()).apply()
    // the system prompt lives at the start of the KV cache: an empty chat picks it up now, a running one from the next chat
    val s = _state.value
    if (s.phase == Phase.Ready && !s.generating) {
      if (s.messages.isEmpty()) viewModelScope.launch { if (!speechLoaded) engine.reset(systemPrompt()) }
      else instructionsChanged = true
    }
  }

  private var instructionsChanged = false

  /** Called when returning to the chat: tell the user once that edited instructions start with the next chat. */
  fun onChatShown() {
    if (instructionsChanged && _state.value.messages.isNotEmpty()) notice("Updated instructions apply from your next chat. Tap New chat to start one.")
    instructionsChanged = false
  }

  private fun readInstructions(): List<Instruction> =
    runCatching {
        val arr = org.json.JSONArray(prefs.getString("instructions", "[]"))
        (0 until arr.length()).map { i ->
          val o = arr.getJSONObject(i)
          Instruction(o.getLong("id"), o.getString("text"), o.optBoolean("enabled", true))
        }
      }
      .getOrDefault(emptyList())

  fun updateSettings(new: Settings) {
    val old = _state.value.settings
    _state.update { it.copy(settings = new) }
    writeSettings(new)
    if (new.galleryMonths != old.galleryMonths) galleryJob?.cancel()
    if (new.smartSearch != old.smartSearch || new.gallerySearch != old.gallerySearch || new.galleryMonths != old.galleryMonths) startSearch()
    if (new.ocr && !old.ocr) startOcr()
    if (new.speechModel && !old.speechModel) startSpeech()
    val needsReload = new.backend != old.backend || new.nCtx != old.nCtx || new.threads != old.threads || new.mediaOnGpu != old.mediaOnGpu || new.fastHead != old.fastHead
    if (needsReload && store.allReady() && !_state.value.generating) {
      _state.update { it.copy(messages = emptyList(), chatId = null) }
      loadModel()
    } else if (new.systemPrompt != old.systemPrompt) {
      newChat()
    }
  }

  fun newChat() {
    if (_state.value.generating) return
    viewModelScope.launch {
      if (!speechLoaded) engine.reset(systemPrompt())
      _state.update { it.copy(messages = emptyList(), liveTps = null, chatId = null, conversation = it.conversation + 1) }
    }
  }

  fun attach(uris: List<Uri>) {
    val ctx = getApplication<Application>()
    viewModelScope.launch {
      _state.update { it.copy(busyMessage = "Decoding attachment…") }
      for (uri in uris) {
        try {
          val type = ctx.contentResolver.getType(uri) ?: ""
          val name = displayName(uri)
          val att =
            withContext(Dispatchers.Default) {
              when {
                type.startsWith("image/") -> {
                  val img = MediaDecoder.image(ctx.contentResolver, uri)
                  Attachment(ids.incrementAndGet(), AttachmentKind.IMAGE, "$name · ${img.width}x${img.height}", uri, img)
                }
                DocumentReader.isDocument(type, name) -> {
                  // ~3 characters per token, leaving 4k tokens of the context for the chat, question and answer
                  val maxChars = ((effectiveCtx() - CHAT_RESERVE_TOKENS) * 3).coerceAtLeast(4000)
                  val reader: com.saad.omnichat.media.PageReader? =
                    if (_state.value.settings.ocr && ocr.ready) { bmp, what ->
                      _state.update { it.copy(busyMessage = "Xiaomi-OCR running: reading $what in $name") }
                      ocr.read(bmp) { msg -> _state.update { it.copy(busyMessage = msg) } }
                    } else null
                  var scans = 0
                  var pictures = 0
                  val full = DocumentReader.read(ctx, uri, type, name, if (embedder.loaded) MAX_INDEXED_CHARS else maxChars, reader) { s, p -> scans = s; pictures = p }
                  if (scans > 0 && reader == null) notice("$scans page(s) of $name are pictures with no text layer. Turn on Read text (OCR) in Settings to read them.")
                  else if (pictures > 0 && reader == null) notice("$name has $pictures picture(s) that may hold text the model can't see. Turn on Read text (OCR) in Settings to read them.")
                  // counted in real tokens: digits and symbols take more per character than prose (a sheet of
                  // numbers is ~2.6 characters a token), so a sheet within maxChars can still fill the context
                  val budget = effectiveCtx() - CHAT_RESERVE_TOKENS
                  val tooLong = full.text.length > maxChars || (engine.tokenCount(TableHints.forModel(name, full.text, partial = false)) ?: 0) > budget
                  val doc =
                    if (embedder.loaded && tooLong) {
                      // too long for the context: keep its opening, and search the rest by meaning for each question
                      val key = semantic.documentKey(name, full.text)
                      semantic.indexDocument(key, name, full.text) { done, total -> _state.update { it.copy(busyMessage = "EmbeddingGemma running: indexing $name for search, $done of $total passages") } }
                      Media.Document(name, full.text.take(minOf(maxChars / 2, DOC_OPENING_CHARS)), truncated = true, index = key, table = wholeSheet(name, full))
                    } else if (tooLong) {
                      Media.Document(name, fitTokens(name, full.text.take(maxChars), budget), truncated = true, table = wholeSheet(name, full))
                    } else full
                  if (doc.truncated && doc.index == null) notice("$name is long: only the first ${doc.text.length / 1000}k characters fit in the model's context. Turn on Smart search in Settings to search all of it.")
                  if (full.truncated && doc.index != null) notice("$name is very long: only its first ${MAX_INDEXED_CHARS / 1_000_000}M characters are searchable.")
                  Attachment(ids.incrementAndGet(), AttachmentKind.DOCUMENT, "$name · ${"%,d".format(full.text.length)} chars${if (doc.index != null) " · searchable" else ""}", uri, doc)
                }
                (type.startsWith("audio/") || type.startsWith("video/")) &&
                  MediaDecoder.audioSeconds(ctx.contentResolver, uri) > MAX_AUDIO_SECONDS -> {
                  // too long to hear at once: transcribed piece by piece on Send, decoded lazily
                  val secs = MediaDecoder.audioSeconds(ctx.contentResolver, uri)
                  Attachment(ids.incrementAndGet(), AttachmentKind.AUDIO, "$name · ${hms(secs)} · transcribed in parts", uri, Media.LongAudio(uri, name, secs))
                }
                type.startsWith("audio/") || type.startsWith("video/") -> {
                  val a = MediaDecoder.audio(ctx.contentResolver, uri, engine.audioSampleRate, MAX_AUDIO_SECONDS)
                  if (a.truncated) notice("Only the first ${MAX_AUDIO_SECONDS / 60} minutes of $name are used.")
                  Attachment(ids.incrementAndGet(), AttachmentKind.AUDIO, "$name · ${seconds(a.pcm.size)}", uri, a)
                }
                else -> throw IllegalArgumentException("Unsupported file type: ${type.ifEmpty { "unknown" }}")
              }
            }
          _state.update { it.copy(pending = it.pending + att) }
        } catch (e: Throwable) {
          // OutOfMemoryError is an Error, not an Exception
          if (e is kotlinx.coroutines.CancellationException) throw e
          AppLog.e("attach failed", e)
          notice("Could not attach file: ${e.message ?: e.javaClass.simpleName}")
        }
      }
      _state.update { it.copy(busyMessage = null) }
    }
  }

  /**
   * A system picker covers the app: with the model loaded it is a multi-GB process in the background, the first one
   * Android kills when memory runs short. A short foreground notification keeps it alive until the picker returns.
   */
  fun pickerOpening() {
    if (!_state.value.generating && _state.value.phase == Phase.Ready) GenerationService.start(getApplication(), "Keeping the model ready while you choose a file")
  }

  fun pickerClosed() {
    if (!_state.value.generating) GenerationService.stop(getApplication(), null)
  }

  fun dictationTaken() = _state.update { it.copy(dictated = null) }

  fun removePending(id: Long) = _state.update { s -> s.copy(pending = s.pending.filterNot { it.id == id }) }

  /** Live mic level 0..1 while recording, else 0. Polled by the voice glow each frame. */
  fun micLevel(): Float = mic?.level ?: 0f

  fun startRecording(mode: MicMode) {
    if (mic?.isRecording == true) return
    if (_state.value.voice.live?.running == true) return notice("Live transcribe is using the microphone; stop it first.")
    val rec = MicRecorder(engine.audioSampleRate)
    if (!rec.start()) return notice(MIC_BUSY)
    mic = rec
    _state.update { it.copy(recording = true, recordingSince = System.currentTimeMillis(), micMode = mode) }
  }

  fun stopRecording() {
    val rec = mic ?: return
    mic = null
    val mode = _state.value.micMode
    viewModelScope.launch {
      val pcm = withContext(Dispatchers.IO) { rec.stop() }
      _state.update { it.copy(recording = false) }
      if (pcm.size < engine.audioSampleRate / 4) {
        notice("Recording too short")
        return@launch
      }
      if (mode == MicMode.DICTATE) {
        if (_state.value.phase != Phase.Ready) return@launch notice("The model is still loading; try again in a moment.")
        // near-silence makes the model invent filler ("uh, uh, uh"): only transcribe something actually said
        if (peakLevel(pcm) < 0.01) return@launch notice("Didn't hear any words.")
        _state.update { it.copy(busyMessage = "Writing down what you said…") }
        val lang = (VoiceLanguages[_state.value.settings.voiceLang] ?: "English").substringBefore(" (")
        val text = runCatching { engine.transcribe(pcm, lang) }.onFailure { AppLog.e("dictation failed", it) }.getOrDefault("").let(::dropFiller)
        _state.update { it.copy(busyMessage = null, dictated = text.ifBlank { null }) }
        if (text.isBlank()) notice("Couldn't make out any words.")
        return@launch
      }
      val att = Attachment(ids.incrementAndGet(), AttachmentKind.AUDIO, "Voice note · ${seconds(pcm.size)}", null, Media.Audio(pcm))
      _state.update { it.copy(pending = it.pending + att) }
    }
  }

  fun send(text: String) {
    val s = _state.value
    if (s.generating || s.phase != Phase.Ready) return
    if (text.isBlank() && s.pending.isEmpty()) return
    if (speechInUse) return notice(SPEECH_BUSY)
    val atts = s.pending
    atts.firstOrNull { it.media is Media.LongAudio }?.let { return transcribeLong(text.trim(), atts, it) }
    val firstActivity =
      when {
        atts.any { it.kind == AttachmentKind.IMAGE } -> Activity.READING_IMAGE
        atts.any { it.kind == AttachmentKind.AUDIO } -> Activity.LISTENING
        else -> Activity.THINKING
      }
    val user = ChatMessage(ids.incrementAndGet(), Role.USER, text.trim(), atts)
    val reply = ChatMessage(ids.incrementAndGet(), Role.ASSISTANT, "", activity = firstActivity, live = LiveStats())
    // a Stop pressed during an earlier reply must not carry over (it turned off tools and photo OCR for good)
    stopRequested = false
    _state.update { it.copy(messages = it.messages + user + reply, pending = emptyList(), generating = true, liveTps = null) }

    val app = getApplication<Application>()
    GenerationService.start(app, "Reading your message…")
    var lastNotify = 0L
    var lastShown = 0L
    genJob =
      viewModelScope.launch {
        useChatModel()
        val backend = engine.loadedBackend ?: Backend.CPU
        val t0 = System.currentTimeMillis()
        val ramBefore = withContext(Dispatchers.IO) { ProcMem.rssMb() }
        var ramPeak = ramBefore
        val sampler = launch(Dispatchers.IO) {
          while (isActive) {
            ramPeak = maxOf(ramPeak, ProcMem.rssMb())
            delay(200)
          }
        }
        var promptTokens = 0
        var prefillMs = 0L
        var ttft = 0L
        var genTokens = 0
        var genMs = 0L
        var ctxUsed = 0
        var ctxSize = 0
        var finished = false
        val sb = StringBuilder()
        try {
          // tools live in the system turn: when the set changes, the chat is re-primed with its history
          val tools = wantedTools(s.settings)
          if (tools.map { it.name } != offered) {
            offered = tools.map { it.name }
            engine.setTools(tools)
            engine.reset(systemPrompt(), withContext(Dispatchers.Default) { modelHistory(_state.value.messages.filterNot { it.id == user.id || it.id == reply.id }) })
          }
          if (embedder.loaded && _state.value.messages.any { m -> m.attachments.any { (it.media as? Media.Document)?.index != null } }) updateReply(reply.id) { it.copy(activity = Activity.EMBEDDINGS) }
          // a document's text is parsed (sheets into rows) and later hashed: not on the main thread
          var turnText = withExcerpts(withContext(Dispatchers.Default) { modelText(user) }, user.text)
          var turnMedia = atts.map { it.media }
          val photoTables = ArrayList<TableHints.Table>()
          // photos: the chat model sees the picture and reads most text in it itself, in seconds on the NPU. It loses
          // track in tables, receipts and forms (which price is on which line), so only those, or a request for the
          // exact text, also go through the OCR model (40 s or more on the CPU)
          val photos = atts.filter { it.kind == AttachmentKind.IMAGE && it.uri != null }
          if (s.settings.ocr && ocr.ready && photos.isNotEmpty()) {
            updateReply(reply.id) { it.copy(activity = Activity.OCR) }
            val exact = WANTS_EXACT_TEXT.containsMatchIn(user.text)
            for ((i, p) in photos.withIndex()) {
              if (stopRequested) break
              if (!exact) {
                val small = withContext(Dispatchers.IO) { runCatching { MediaDecoder.image(app.contentResolver, p.uri!!, PHOTO_CHECK_SIDE) }.getOrNull() }
                val t0 = System.currentTimeMillis()
                val table = small?.let { engine.looksLikeTable(it) }
                AppLog.i("photo ${i + 1} checked in ${System.currentTimeMillis() - t0} ms: ${if (table == false) "no table, read by the chat model alone" else "table, receipt or form: reading it with Xiaomi-OCR"}")
                if (table == false) continue
              }
              GenerationService.progress(app, "Xiaomi-OCR reading the text in photo ${i + 1} of ${photos.size}")
              val bmp = withContext(Dispatchers.IO) { runCatching { MediaDecoder.bitmap(app.contentResolver, p.uri!!, 2500) }.getOrNull() } ?: continue
              val read = runCatching { ocr.read(bmp) { msg -> GenerationService.progress(app, msg) } }.onFailure { AppLog.e("OCR failed", it) }.getOrNull()
              if (!read.isNullOrBlank()) {
                turnText += "\n\n<image_text image=\"${i + 1}\" read_by=\"OCR\">\n$read\n</image_text>"
                photoTables += TableHints.tables("photo ${i + 1}", read)
              }
            }
            updateReply(reply.id) { it.copy(activity = Activity.READING_IMAGE) }
          }
          // a question about a table: the rows, counts and columns it names, worked out here. After the documents, so
          // their saved state still matches
          tableHints(user.text, photoTables)?.let { h ->
            val at = turnText.lastIndexOf(DOC_END).let { if (it < 0) 0 else it + DOC_END.length }
            val with = turnText.substring(0, at) + h + "\n\n" + turnText.substring(at)
            // only when the answer still has room: they help, but a full context means no answer at all
            val (used, size) = engine.context()
            val need = engine.tokenCount(with)
            if (need == null || size == 0 || used + need + ANSWER_ROOM_TOKENS <= size) turnText = with
            else AppLog.w("table hints left out: ${need} tokens with them, ${size - used} free")
          }
          if (offered.isNotEmpty()) turnText += TOOL_HINT
          val cacheKey = withContext(Dispatchers.Default) { docCacheKey(user, turnText, s.settings) }
          var role = "user"
          val sources = ArrayList<Source>()
          var calls = 0
          val tried = HashSet<ToolCall>()
          var nudged = false
          while (true) {
            // Stop pressed while a tool ran (a web search takes up to 45 s) or photos were read: no further pass
            if (stopRequested) {
              if (sb.isEmpty()) updateReply(reply.id) { it.copy(text = "Stopped.") }
              break
            }
            var passTokens = 0
            val raw = StringBuilder()
            engine.generate(turnText, turnMedia, s.settings.temperature, s.settings.maxTokens, role, cacheKey.takeIf { role == "user" }).collect { ev ->
              when (ev) {
                is GenEvent.Prefilled -> {
                  if (promptTokens == 0) {
                    promptTokens = ev.promptTokens
                    prefillMs = ev.millis
                  }
                  updateReply(reply.id) { it.copy(activity = Activity.THINKING, live = LiveStats(prefillMs = ev.millis, promptTokens = ev.promptTokens)) }
                }
                is GenEvent.Piece -> {
                  raw.append(ev.text)
                  if (ttft == 0L && (sb.isNotBlank() || raw.toString().substringBefore("<tool_call>").isNotBlank())) ttft = System.currentTimeMillis() - t0
                  genTokens = ev.genTokens
                  genMs = ev.genMillis
                  val tps = if (ev.genMillis > 0) ev.genTokens * 1000f / ev.genMillis else 0f
                  val now = System.currentTimeMillis()
                  if (now - lastNotify > 2000) {
                    lastNotify = now
                    GenerationService.progress(app, "Writing the reply · ${ev.genTokens} tokens · ${"%.1f".format(tps)} tok/s")
                  }
                  // the screen gets the text ~16 times a second, not per token: every update rebuilds the reply and the
                  // page re-reads its Markdown, work that grows with the reply (the pass's end shows the rest)
                  if (now - lastShown >= TEXT_FRAME_MS) {
                    lastShown = now
                    // a tool call is for the app, not the reader: show only what comes before it
                    val visible = uncited(sb.toString() + raw.toString().substringBefore("<tool_call>"), sources)
                    _state.update { st ->
                      st.copy(
                        liveTps = tps,
                        messages = st.messages.map { m ->
                          if (m.id == reply.id) m.copy(text = visible, activity = Activity.WRITING, live = m.live?.copy(genTokens = ev.genTokens, tps = tps)) else m
                        },
                      )
                    }
                  }
                }
                is GenEvent.Done -> {
                  genTokens = ev.genTokens
                  genMs = ev.genMillis
                  ctxUsed = ev.ctxUsed
                  ctxSize = ev.ctxSize
                  passTokens = ev.genTokens
                  finished = true
                }
              }
            }
            // whatever the last throttled frame didn't show
            if (raw.isNotEmpty()) {
              val visible = uncited(sb.toString() + raw.toString().substringBefore("<tool_call>"), sources)
              updateReply(reply.id) { it.copy(text = visible, live = it.live?.copy(genTokens = genTokens)) }
            }
            sb.append(raw.toString().substringBefore("<tool_call>"))
            val cutOff = passTokens >= s.settings.maxTokens
            val call =
              if (offered.isNotEmpty() && calls < MAX_TOOL_CALLS && !stopRequested && !cutOff) ToolCalls.parse(raw.toString())?.takeIf { it.name in offered && it !in tried }
              else null
            if (call == null) {
              if (cutOff && "<tool_call>" in raw) AppLog.w("reply hit the ${s.settings.maxTokens}-token limit inside a tool call; not running it")
              // a refused call (a repeat of one that failed) would leave an empty reply: ask for a plain answer once
              val refused = !cutOff && !stopRequested && "<tool_call>" in raw && ToolCalls.parse(raw.toString()) != null
              if (refused && !nudged && sb.isBlank()) {
                nudged = true
                AppLog.i("tool call repeated; asking for an answer without tools")
                turnText = "Not run: that exact call already failed. Don't call tools again; answer the user directly with what you know, and say what you couldn't do."
                turnMedia = emptyList()
                role = "tool"
                finished = false
                continue
              }
              break
            }
            tried += call
            calls++
            if (!asksFor(call.name, user.text)) {
              // an app-opening action the message never asked for (a poem about an alarm clock opened a website)
              AppLog.i("model called ${call.name} but the message doesn't ask for it; not running it")
              turnText = "Not run: the user's message doesn't ask for this. Don't call tools; answer the user's message directly."
              turnMedia = emptyList()
              role = "tool"
              finished = false
              continue
            }

            // the model called a tool: run it, then hand the result back as the tool's response
            AppLog.i("model called ${call.name}: ${call.args}")
            turnText = runTool(call, reply.id, sources)
            turnMedia = emptyList()
            role = "tool"
            finished = false
          }
        } catch (e: EngineException) {
          AppLog.e("generation failed: ${e.message}")
          val message = vulkanAdvice(e) ?: e.message ?: "Generation failed"
          updateReply(reply.id) { it.copy(text = sb.toString().ifEmpty { message }, error = sb.isEmpty()) }
          if (e.needsReset) {
            // the KV cache is unknown after a failed prompt: rebuild it from the turns before this one
            engine.reset(systemPrompt(), withContext(Dispatchers.Default) { modelHistory(_state.value.messages.filterNot { it.id == user.id || it.id == reply.id }) })
          }
        } catch (e: kotlinx.coroutines.CancellationException) {
          throw e
        } catch (e: Throwable) {
          // anything else (a full disk, a tool that threw): the reply fails, the app stays up
          AppLog.e("reply failed", e)
          updateReply(reply.id) { it.copy(text = sb.toString().ifEmpty { "Something went wrong: ${e.message ?: e.javaClass.simpleName}" }, error = sb.isEmpty()) }
        } finally {
          sampler.cancel()
          val run =
            if (promptTokens > 0)
              RunRecord(
                timestamp = t0,
                backend = BackendKind.of(backend.device),
                device = _state.value.deviceInfo,
                model = Models.MODEL.name.removeSuffix(".gguf") + if (s.settings.fastHead) " +Q4_0 head" else "",
                prompt = user.text.take(200),
                images = atts.count { it.kind == AttachmentKind.IMAGE },
                audios = atts.count { it.kind == AttachmentKind.AUDIO },
                promptTokens = promptTokens,
                prefillMs = prefillMs,
                genTokens = genTokens,
                genMs = genMs,
                ttftMs = if (ttft > 0) ttft else System.currentTimeMillis() - t0,
                totalMs = System.currentTimeMillis() - t0,
                ramBeforeMb = ramBefore,
                ramPeakMb = maxOf(ramPeak, ProcMem.rssMb()),
                ctxUsed = ctxUsed,
                ctxSize = ctxSize,
                threads = s.settings.threads,
                animated = s.settings.animateWhileGenerating,
                stopped = !finished || (genTokens >= s.settings.maxTokens),
              )
            else null
          updateReply(reply.id) { it.copy(activity = Activity.DONE, run = run) }
          dropMedia(user.id)
          GenerationService.stop(app, sb.toString().trim().lineSequence().firstOrNull()?.take(80)?.let { "Reply ready: $it" })
          _state.update { it.copy(generating = false, runs = if (run != null) it.runs + run else it.runs) }
          runCatching { saveChat() }.onFailure { AppLog.e("saving chat failed", it) }
          if (switchToCpu) {
            switchToCpu = false
            // keep the chat so the explanation stays visible; the KV cache is reset by the reload
            val s = _state.value.settings.copy(backend = "")
            _state.update { it.copy(settings = s) }
            writeSettings(s)
            loadModel()
          }
          if (run != null) runCatching { statsStore.append(run) }.onFailure { AppLog.e("saving the run's stats failed", it) }
        }
      }
  }

  /**
   * With web search on, the model also cites attached files or its own knowledge as [1]: drop numbers that match no
   * search result of this reply. Markdown links ([1](…)) and replies without web search are left alone.
   */
  private fun uncited(text: String, sources: List<Source>) =
    if ("web_search" !in offered) text else STRAY_CITE.replace(text) { m -> if (m.groupValues[1].toInt() in 1..sources.size) m.value else "" }

  /** Names of the tools in the model's system turn right now. */
  private var offered: List<String> = emptyList()

  private fun wantedTools(st: Settings): List<ToolSpec> = buildList {
    if (st.webSearch && WebSearch.isOnline(getApplication())) add(ToolSpec("web_search", WEB_TOOL_DESCRIPTION, WEB_TOOL_PARAMETERS))
    if (st.phoneTools) addAll(phone.specs())
    if (st.smartSearch && embedder.loaded && semantic.indexedChats().any { it != _state.value.chatId })
      add(ToolSpec("search_past_chats", "Search the user's earlier conversations with you by meaning.", ToolCalls.params("query" to "What to look for", required = listOf("query"))))
    if (st.gallerySearch && embedder.seesImages)
      add(
        ToolSpec(
          "search_gallery",
          "Find photos and videos in the user's gallery by what they show (e.g. 'dog on the beach', 'receipts', 'birthday cake'), optionally within dates.",
          ToolCalls.params(
            "query" to "What the photos show; empty for any",
            "from_date" to "Optional start, YYYY-MM-DD",
            "to_date" to "Optional end (inclusive), YYYY-MM-DD",
            "type" to "photo, video or any",
            "limit" to "How many to show, default 12",
            types = mapOf("limit" to "integer"),
          ),
        )
      )
  }

  /** Runs one tool call and returns what the model reads back. */
  private suspend fun runTool(call: ToolCall, replyId: Long, sources: MutableList<Source>): String {
    val app = getApplication<Application>()
    val s = _state.value.settings
    return when (call.name) {
      "web_search" -> {
        val query = call.args["query"].orEmpty()
        updateReply(replyId) { it.copy(activity = Activity.SEARCHING) }
        GenerationService.progress(app, "Searching the web: $query")
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        runCatching { WebSearch.search(query, s.exaKey, s.firecrawlKey.ifBlank { null }) }
          .map { found ->
            val numbered = found.mapIndexed { i, src -> src.copy(n = sources.size + i + 1) }
            sources += numbered
            updateReply(replyId) { it.copy(sources = sources.toList()) }
            if (numbered.isEmpty()) "No results for \"$query\"." else WebSearch.promptBlock(numbered, today)
          }
          .getOrElse { err ->
            AppLog.e("web search failed", err)
            "The search failed (${err.message}). Answer from what you know and say that the search failed."
          }
      }
      "search_past_chats" -> {
        updateReply(replyId) { it.copy(activity = Activity.EMBEDDINGS) }
        GenerationService.progress(app, "EmbeddingGemma searching earlier chats")
        val titles = _state.value.chats.associateBy { it.id }
        val found = semantic.searchChats(call.args["query"].orEmpty(), _state.value.chatId)
        if (found.isEmpty()) "Nothing in earlier chats matches."
        else found.joinToString("\n\n") { f ->
          val chat = titles[f.source.toLongOrNull()]
          val date = chat?.let { java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.US).format(java.util.Date(it.updated)) } ?: "?"
          "From the chat \"${chat?.title ?: "?"}\" ($date):\n${f.text}"
        }
      }
      "search_gallery" -> {
        updateReply(replyId) { it.copy(activity = Activity.EMBEDDINGS) }
        GenerationService.progress(app, "EmbeddingGemma searching your photos and videos")
        val a = call.args
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val from = a["from_date"]?.let { runCatching { day.parse(it)?.time }.getOrNull() }
        val to = a["to_date"]?.let { runCatching { day.parse(it)?.time?.plus(86_400_000L) }.getOrNull() }
        val video = when (a["type"]?.lowercase()) { "photo", "photos", "image" -> false; "video", "videos" -> true; else -> null }
        val limit = a["limit"]?.toDoubleOrNull()?.toInt()?.coerceIn(1, 40) ?: 12
        val hits: List<GalleryHit> = gallery.search(a["query"].orEmpty(), from, to, video, limit)
        updateReply(replyId) { it.copy(gallery = hits.map { h -> h.item }) }
        val note = galleryProgress?.let { (d, t) -> if (d < t) " (only $d of $t photos and videos are indexed so far)" else "" }.orEmpty()
        if (hits.isEmpty()) "No matching photos or videos$note." else "Found ${hits.size}, shown to the user as thumbnails they can tap to open$note. List each with its file name and the date and time it was taken:\n" + GalleryIndex.describe(hits)
      }
      else -> {
        updateReply(replyId) { it.copy(activity = Activity.USING_PHONE) }
        GenerationService.progress(app, "Using the phone: ${call.name.replace('_', ' ')}")
        withContext(Dispatchers.IO) { phone.run(call) }.also {
          phone.prepared?.let { batch -> updateReply(replyId) { m -> m.copy(texts = m.texts + batch) } }
          phone.prepared = null
        }
      }
    }
  }

  /** The longest opening of [text], cut at a line, that the model reads in at most [budget] tokens. */
  private suspend fun fitTokens(name: String, text: String, budget: Int): String {
    var t = text
    repeat(4) {
      val n = engine.tokenCount(TableHints.forModel(name, t, partial = t.length < text.length)) ?: return t
      if (n <= budget) return t
      t = t.take((t.length.toLong() * budget / n * 95 / 100).toInt()).let { c -> c.substringBeforeLast('\n', c) }
    }
    return t
  }

  /** What [question] names in the tables attached to this chat and read from this message's photos; see [TableHints]. */
  private suspend fun tableHints(question: String, photoTables: List<TableHints.Table>): String? {
    val docs = _state.value.messages.flatMap { it.attachments }.mapNotNull { it.media as? Media.Document }.distinctBy { it.name to it.text }
    return withContext(Dispatchers.Default) {
      runCatching { TableHints.hints(docs.flatMap(::tablesOf) + photoTables, question) }.onFailure { AppLog.e("table hints failed", it) }.getOrNull()
    }
  }

  // a document's tables, parsed once rather than for every question (keyed by the document object; weakly held)
  private val tableCache = java.util.WeakHashMap<Media.Document, List<TableHints.Table>>()

  private fun tablesOf(d: Media.Document): List<TableHints.Table> =
    synchronized(tableCache) { tableCache[d] }
      ?: (if (d.table != null) TableHints.tables(d.name, d.table) else TableHints.tables(d.name, d.text, partial = d.truncated || d.index != null))
        .also { synchronized(tableCache) { tableCache[d] = it } }

  /** All of a spreadsheet too big for the context, for finding the rows a question names (null: not a sheet, or cut). */
  private fun wholeSheet(name: String, full: Media.Document) = full.text.takeIf { !full.truncated && TableHints.isSheet(name) }

  /** For a long document searched by meaning, the passages that answer [question], appended to the turn. */
  private suspend fun withExcerpts(turn: String, question: String): String {
    if (!embedder.loaded || question.isBlank()) return turn
    val docs = _state.value.messages.flatMap { it.attachments }.mapNotNull { (it.media as? Media.Document)?.takeIf { d -> d.index != null } }.distinctBy { it.index }
    if (docs.isEmpty()) return turn
    val q = embedder.query(question) ?: return turn // once, for every document
    val block = buildString {
      for (d in docs) {
        val found = runCatching { semantic.searchDocument(d.index!!, q) }.getOrDefault(emptyList())
        if (found.isEmpty()) continue
        append("<document_excerpts name=\"").append(d.name).append("\">\n")
        found.forEach { append(it.text).append("\n…\n") }
        append("</document_excerpts>\n\n")
      }
    }
    return if (block.isEmpty()) turn else block + turn
  }

  /**
   * A name for the state after reading this message's documents, so attaching the same file in a new chat loads it
   * instead of reading it again. Only for plain documents (no photos/audio, no question-dependent excerpts); the
   * native side also checks the saved tokens match exactly, and uses it only at the start of a chat.
   */
  private fun docCacheKey(user: ChatMessage, turn: String, st: Settings): String? {
    val docs = user.attachments.mapNotNull { it.media as? Media.Document }
    if (docs.isEmpty() || docs.any { it.index != null } || user.attachments.any { it.media !is Media.Document } || DOC_END !in turn) return null
    val md = java.security.MessageDigest.getInstance("SHA-1")
    listOf(Models.MODEL.name, st.fastHead.toString(), engine.loadedBackend?.device.orEmpty(), systemPrompt(), offered.joinToString(","))
      .forEach { md.update(it.toByteArray()); md.update(0) }
    md.update(turn.substringBeforeLast(DOC_END).toByteArray())
    return md.digest().joinToString("") { "%02x".format(it) }.take(24)
  }

  /** (indexed, total) while the gallery is being indexed, for Settings and the search note. */
  @Volatile private var galleryProgress: Pair<Int, Int>? = null

  /**
   * Brings smart search in line with Settings: downloads EmbeddingGemma 2 (and its image tower for the gallery) once,
   * loads it, indexes saved chats, and indexes the gallery in the background, pausing while a reply is generated.
   */
  private fun startSearch() {
    val st = _state.value.settings
    if (!st.smartSearch && !st.gallerySearch) {
      galleryJob?.cancel()
      _state.update { it.copy(searchStatus = null) }
      return
    }
    if (_state.value.phase != Phase.Ready) return
    viewModelScope.launch {
      try {
        val needs = listOfNotNull(Models.EMBED, Models.EMBED_VISION.takeIf { st.gallerySearch }).filterNot(store::isReady)
        for (m in needs) {
          val mb = m.size shr 20
          _state.update { it.copy(searchStatus = "Downloading ${m.name.removeSuffix(".gguf")} (0 of $mb MB)") }
          store.downloadOne(m) { done -> _state.update { it.copy(searchStatus = "Downloading ${m.name.removeSuffix(".gguf")} (${done shr 20} of $mb MB)") } }
        }
        _state.update { it.copy(searchStatus = if (st.gallerySearch) "Loading the search model (the first time it also times the GPU, about a minute)…" else "Loading the search model…") }
        if (!embedder.load(store.file(Models.EMBED), store.file(Models.EMBED_VISION).takeIf { st.gallerySearch })) {
          _state.update { it.copy(searchStatus = "The search model failed to load; see Settings > Logs") }
          return@launch
        }
        // chats saved before search was on
        val have = semantic.indexedChats()
        val todo = _state.value.chats.filter { it.id !in have }
        for ((i, c) in todo.withIndex()) {
          _state.update { it.copy(searchStatus = "Indexing chats ${i + 1} of ${todo.size}") }
          chatStore.load(c.id)?.let { runCatching { semantic.indexChat(it) } }
        }
        _state.update { it.copy(searchStatus = if (st.gallerySearch) "Indexing the gallery…" else "Ready: chats and long documents are searchable") }
        val old = galleryJob
        if (st.gallerySearch && embedder.seesImages && old?.isActive != true) {
          // a cancelled run finishes the photo it is on first; two runs at once would both append to the index
          old?.join()
          galleryJob =
            viewModelScope.launch {
              runCatching {
                  gallery.indexAll(since = st.galleryMonths.takeIf { it > 0 }?.let { m -> java.util.Calendar.getInstance().apply { add(java.util.Calendar.MONTH, -m) }.timeInMillis }, paused = { _state.value.generating || _state.value.busyMessage != null || _state.value.voice.live?.running == true }) { done, total ->
                    galleryProgress = done to total
                    val on = if (embedder.towerDevice == "GPUOpenCL") "on the GPU" else "on the CPU"
                    _state.update { it.copy(searchStatus = if (done < total) "Indexing the gallery $on: $done of $total (pauses while replying)" else "Ready: $total photos and videos searchable") }
                  }
                }
                .onFailure { if (it !is kotlinx.coroutines.CancellationException) AppLog.e("gallery indexing failed", it) }
            }
        }
      } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        AppLog.e("smart search setup failed", e)
        _state.update { it.copy(searchStatus = "Couldn't set up search: ${e.message}") }
      }
    }
  }

  /** Downloads Xiaomi-OCR-0 once when it is turned on; it loads only while reading. */
  private fun startOcr() {
    if (!_state.value.settings.ocr) return
    viewModelScope.launch {
      try {
        for (m in listOf(Models.OCR, Models.OCR_VISION).filterNot(store::isReady)) {
          val mb = m.size shr 20
          _state.update { it.copy(ocrStatus = "Downloading ${m.name.removeSuffix(".gguf")} (0 of $mb MB)") }
          store.downloadOne(m) { done -> _state.update { it.copy(ocrStatus = "Downloading ${m.name.removeSuffix(".gguf")} (${done shr 20} of $mb MB)") } }
        }
        _state.update { it.copy(ocrStatus = "Ready: scanned PDF pages and text in photos are read") }
      } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        AppLog.e("OCR download failed", e)
        _state.update { it.copy(ocrStatus = "Couldn't download the OCR model: ${e.message}") }
      }
    }
  }

  /** Downloads LFM3-Audio once when live transcribe is set to use it; it loads only while live transcribe listens. */
  private fun startSpeech() {
    if (!_state.value.settings.speechModel || speechJob?.isActive == true) return
    speechJob =
      viewModelScope.launch {
        try {
          for (m in listOf(Models.SPEECH, Models.SPEECH_MMPROJ).filterNot(store::isReady)) {
            val mb = m.size shr 20
            _state.update { it.copy(speechStatus = "Downloading ${m.name.removeSuffix(".gguf")} (0 of $mb MB)") }
            store.downloadOne(m) { done -> _state.update { it.copy(speechStatus = "Downloading ${m.name.removeSuffix(".gguf")} (${done shr 20} of $mb MB)") } }
          }
          _state.update { it.copy(speechStatus = "Ready: live transcribe writes with LFM3-Audio") }
        } catch (e: Exception) {
          if (e is kotlinx.coroutines.CancellationException) throw e
          AppLog.e("LFM3-Audio download failed", e)
          _state.update { it.copy(speechStatus = "Couldn't download LFM3-Audio: ${e.message}. Live transcribe uses the chat model.") }
        }
      }
  }

  private var speechJob: Job? = null

  // One model fits the NPU at a time (its session maps ~3.1 GB), so live transcribe swaps LFM3-Audio in for the chat
  // model and the chat model comes back when something needs it. Swaps run one at a time.
  private val modelSwap = kotlinx.coroutines.sync.Mutex()
  private var chatModelPath: String? = null
  private val speechLoaded get() = engine.loadedModel.isNotEmpty() && engine.loadedModel == store.file(Models.SPEECH).path
  private val liveBusy get() = liveJob?.isActive == true || notesJob?.isActive == true
  // a swap under way counts: a chat message must not pull the chat model back while live transcribe is loading its own
  private val speechInUse get() = liveBusy && (speechLoaded || modelSwap.isLocked)

  /** Loads LFM3-Audio for live transcribe. False, with the chat model kept, when it is off, not downloaded or fails. */
  private suspend fun useSpeechModel(): Boolean =
    modelSwap.withLock {
      if (speechLoaded) return@withLock true
      val s = _state.value.settings
      if (!s.speechModel || !store.isReady(Models.SPEECH) || !store.isReady(Models.SPEECH_MMPROJ)) return@withLock false
      val backend = engine.loadedBackend ?: return@withLock false
      _state.update { it.copy(liveModel = "loading LFM3-Audio") }
      val t0 = System.currentTimeMillis()
      try {
        engine.load(store.file(Models.SPEECH).path, store.file(Models.SPEECH_MMPROJ).path, backend, SPEECH_CTX, s.threads, s.mediaOnGpu)
        AppLog.i("live transcribe: LFM3-Audio loaded on ${backend.label} in ${System.currentTimeMillis() - t0} ms")
        _state.update { it.copy(liveModel = "LFM3-Audio") }
        true
      } catch (e: EngineException) {
        AppLog.e("LFM3-Audio failed to load: ${e.message}")
        notice("The speech model couldn't load, so live transcribe uses the chat model. Details are in Settings > Logs.")
        reloadChat(backend)
        false
      }
    }

  /** Puts the chat model back after live transcribe; the open conversation is read in again. */
  private suspend fun useChatModel() = modelSwap.withLock { if (speechLoaded) reloadChat(engine.loadedBackend ?: Backend.CPU) }

  private suspend fun reloadChat(backend: Backend) {
    val s = _state.value.settings
    val t0 = System.currentTimeMillis()
    _state.update { it.copy(liveModel = null, busyMessage = "Loading the chat model…") }
    try {
      val nCtx = if (backend.device.startsWith("HTP")) minOf(s.nCtx, NPU_MAX_CTX) else s.nCtx
      engine.load(chatModelPath ?: store.file(Models.MODEL).path, store.file(Models.MMPROJ).path, backend, nCtx, s.threads, s.mediaOnGpu)
      // the tools go back into the system turn with the next message
      offered = listOf("")
      engine.reset(systemPrompt(), withContext(Dispatchers.Default) { modelHistory(_state.value.messages.filter { it.activity == Activity.DONE }) })
      AppLog.i("chat model back on ${backend.label} in ${System.currentTimeMillis() - t0} ms")
    } catch (e: EngineException) {
      AppLog.e("chat model reload failed: ${e.message}")
      _state.update { it.copy(phase = Phase.Failed(e.message ?: "load failed")) }
    } finally {
      _state.update { it.copy(busyMessage = null) }
    }
  }

  @Volatile private var transcribeOpen = false

  /** The live transcribe page is shown ([open]) or left: once it has stopped (notes included), the chat model comes back. */
  fun liveShown(open: Boolean) {
    transcribeOpen = open
    if (open) return
    viewModelScope.launch {
      liveJob?.join()
      notesJob?.join()
      if (!transcribeOpen) useChatModel()
    }
  }

  /** The globe button: search the web for the next messages. Keys are optional (free tiers are used without them). */
  fun toggleWebSearch() {
    val s = _state.value.settings
    if (!s.webSearch && !WebSearch.isOnline(getApplication())) {
      notice("No internet connection, so web search is unavailable right now.")
      return
    }
    val new = s.copy(webSearch = !s.webSearch)
    _state.update { it.copy(settings = new) }
    writeSettings(new)
    if (new.webSearch) notice("Web search on: your questions are sent to Exa/Firecrawl to find sources. The model still runs on this phone.")
  }

  // ---- voice chat ----------------------------------------------------------------------------------------------

  private val player = SpeechPlayer(viewModelScope)
  private var voiceMic: MicRecorder? = null

  fun voiceLevel(): Float = voiceMic?.level ?: player.level

  /** The big button: listen, then send; while the model thinks or speaks, it interrupts and listens again. */
  fun voiceTap() {
    when (_state.value.voice.phase) {
      VoicePhase.IDLE -> voiceListen()
      // hands-free (camera) listening that hasn't heard anyone yet: the tap pauses the conversation
      VoicePhase.LISTENING -> if (_state.value.voice.camera && !_state.value.voice.heard) voicePause() else voiceSend()
      VoicePhase.THINKING, VoicePhase.SPEAKING -> {
        stop()
        player.stop()
        viewModelScope.launch {
          genJob?.join()
          voiceListen()
        }
      }
    }
  }

  fun voicePhoto(uri: android.net.Uri?) {
    if (uri == null) return
    viewModelScope.launch {
      runCatching {
          val img = withContext(Dispatchers.Default) { MediaDecoder.image(getApplication<Application>().contentResolver, uri) }
          Attachment(ids.incrementAndGet(), AttachmentKind.IMAGE, "${displayName(uri)} · ${img.width}x${img.height}", uri, img)
        }
        .onSuccess { att -> _state.update { it.copy(voice = it.voice.copy(photo = att)) } }
        .onFailure { AppLog.e("voice photo failed", it) }
    }
  }

  fun voiceClearPhoto() = _state.update { it.copy(voice = it.voice.copy(photo = null)) }

  // the newest camera frame, kept twice a second by the camera view while the camera is on
  @Volatile private var cameraFrame: android.graphics.Bitmap? = null

  /** Turns the live camera on or off; a picked photo gives way to it. */
  fun voiceCamera(on: Boolean) {
    cameraFrame = null
    _state.update { it.copy(voice = it.voice.copy(camera = on, photo = if (on) null else it.voice.photo)) }
  }

  fun voiceFlipCamera() {
    cameraFrame = null
    _state.update { it.copy(voice = it.voice.copy(frontCamera = !it.voice.frontCamera)) }
  }

  fun cameraFrame(frame: android.graphics.Bitmap) {
    cameraFrame = frame
  }

  /** The camera frame current now, as a picture for the model (and a small JPEG for the chat to show). */
  private suspend fun cameraShot(): Attachment? {
    val frame = cameraFrame ?: return null
    val app = getApplication<Application>()
    return withContext(Dispatchers.Default) {
      runCatching {
          val scale = minOf(1f, CAMERA_MAX_SIDE.toFloat() / maxOf(frame.width, frame.height))
          val bmp = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(frame, (frame.width * scale).toInt(), (frame.height * scale).toInt(), true) else frame
          val dir = java.io.File(app.cacheDir, "camera").apply { mkdirs() }
          // the chat shows the picture each question was asked about; only the newest few are kept
          dir.listFiles().orEmpty().sortedByDescending { it.lastModified() }.drop(CAMERA_KEEP).forEach { it.delete() }
          val file = java.io.File(dir, "camera-${System.currentTimeMillis()}.jpg")
          file.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }
          val px = IntArray(bmp.width * bmp.height)
          bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
          val rgb = ByteArray(px.size * 3)
          for (i in px.indices) {
            val c = px[i]
            rgb[i * 3] = (c shr 16).toByte()
            rgb[i * 3 + 1] = (c shr 8).toByte()
            rgb[i * 3 + 2] = c.toByte()
          }
          val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
          Attachment(ids.incrementAndGet(), AttachmentKind.IMAGE, "Camera · $stamp · ${bmp.width}x${bmp.height}", Uri.fromFile(file), Media.Image(rgb, bmp.width, bmp.height))
        }
        .onFailure { AppLog.e("camera frame failed", it) }
        .getOrNull()
    }
  }

  private fun voicePause() {
    voiceMic?.let { m -> voiceMic = null; viewModelScope.launch(Dispatchers.IO) { m.stop() } }
    _state.update { it.copy(voice = it.voice.copy(phase = VoicePhase.IDLE, heard = false)) }
  }

  /**
   * Hands-free listening for camera conversations: the mic detects speech ([MicRecorder.heardAt]); this sends once you
   * pause for [PAUSE_MS] (or have talked for [MAX_UTTERANCE_MS]), timed in recorded audio.
   */
  private fun sendOnPause(rec: MicRecorder) =
    viewModelScope.launch {
      val rate = engine.audioSampleRate
      while (voiceMic === rec && _state.value.voice.phase == VoicePhase.LISTENING) {
        delay(100)
        if (rec.heardAt < 0) continue
        if (!_state.value.voice.heard) _state.update { it.copy(voice = it.voice.copy(heard = true)) }
        val pos = rec.position
        if (pos - rec.lastVoiceAt >= rate.toLong() * PAUSE_MS / 1000 || pos - rec.heardAt >= rate.toLong() * MAX_UTTERANCE_MS / 1000) {
          voiceSend()
          break
        }
      }
    }

  /** Leaving the voice screen: stop listening and speaking. */
  fun voiceClose() {
    // a live transcript outlives the screen: it keeps listening in the background until stopped, and stays to be saved
    voiceMic?.let { m -> voiceMic = null; viewModelScope.launch(Dispatchers.IO) { m.stop() } }
    if (_state.value.voice.phase != VoicePhase.IDLE && _state.value.voice.phase != VoicePhase.LISTENING) stop()
    player.stop()
    cameraFrame = null
    _state.update { it.copy(voice = it.voice.copy(phase = VoicePhase.IDLE, camera = false)) }
    // the speech context holds ~350 MB of NPU memory; give it back until voice chat is used again
    viewModelScope.launch {
      genJob?.join()
      engine.releaseSpeech()
    }
  }

  /** Photos and videos taken within a few hours of [item], in time order (for browsing around a search result). */
  suspend fun galleryNearby(item: GalleryItem): List<GalleryItem> =
    gallery.listMedia().filter { kotlin.math.abs(it.taken - item.taken) <= NEARBY_MS }.sortedBy { it.taken }.let { list ->
      // at most 60, centred on the item
      val at = list.indexOf(item).coerceAtLeast(0)
      list.subList(maxOf(0, at - 30), minOf(list.size, at + 30))
    }

  // ---- texts prepared by the model ----

  /** The Send button on a text card: sends to everyone on it, one after another, and shows each result. */
  fun sendTexts(msgId: Long, batchId: Long) {
    val app = getApplication<Application>()
    fun set(f: (com.saad.omnichat.tools.TextBatch) -> com.saad.omnichat.tools.TextBatch) =
      _state.update { st -> st.copy(messages = st.messages.map { m -> if (m.id != msgId) m else m.copy(texts = m.texts.map { if (it.id == batchId) f(it) else it }) }) }
    val batch = _state.value.messages.firstOrNull { it.id == msgId }?.texts?.firstOrNull { it.id == batchId } ?: return
    if (batch.state != com.saad.omnichat.tools.TextBatch.State.WAITING) return
    set { it.copy(state = com.saad.omnichat.tools.TextBatch.State.SENDING) }
    viewModelScope.launch {
      for ((i, t) in batch.to.withIndex()) {
        val err = com.saad.omnichat.tools.TextSender.send(app, t.number, batch.message)
        AppLog.i("text to recipient ${i + 1} of ${batch.to.size}: ${err ?: "sent"}")
        set { b -> b.copy(to = b.to.mapIndexed { j, x -> if (j == i) x.copy(status = err ?: "Sent", ok = err == null) else x }) }
      }
      set { it.copy(state = com.saad.omnichat.tools.TextBatch.State.DONE) }
    }
  }

  fun cancelTexts(msgId: Long, batchId: Long) =
    _state.update { st -> st.copy(messages = st.messages.map { m -> if (m.id != msgId) m else m.copy(texts = m.texts.map { if (it.id == batchId && it.state == com.saad.omnichat.tools.TextBatch.State.WAITING) it.copy(state = com.saad.omnichat.tools.TextBatch.State.CANCELLED) else it }) }) }

  // ---- live transcribe ----

  private var liveMic: MicRecorder? = null
  private var liveJob: Job? = null

  /**
   * Starts live transcribe: the mic keeps recording and every [Settings.liveSeconds] or so (longer when the phone is
   * slower than that) the new audio is written down. Cuts are moved to the quietest moment near the end of each piece,
   * so words aren't split in half. The model only transcribes; it doesn't answer.
   */
  fun liveStart() {
    val s = _state.value
    if (s.phase != Phase.Ready || s.generating || s.voice.phase != VoicePhase.IDLE || liveJob?.isActive == true) return
    val rate = engine.audioSampleRate
    val rec = MicRecorder(rate)
    if (!rec.start()) return notice(MIC_BUSY)
    liveMic = rec
    _state.update { it.copy(voice = it.voice.copy(live = LiveTranscript(System.currentTimeMillis()))) }
    val app = getApplication<Application>()
    runCatching { LiveTranscribeService.start(app) }.onFailure { AppLog.w("live transcribe service: ${it.message}") }
    val lang = (VoiceLanguages[s.settings.voiceLang] ?: "English").substringBefore(" (")
    val seconds = s.settings.liveSeconds
    AppLog.i("live transcribe: $seconds s blocks")
    liveJob =
      viewModelScope.launch {
        // the mic is already recording: what is said while LFM3-Audio loads is written down once it is in
        if (!useSpeechModel()) _state.update { it.copy(liveModel = null) }
        var buf = FloatArray(0)
        var consumed = 0L
        while (true) {
          val recording = rec.isRecording
          buf += withContext(Dispatchers.IO) { rec.take() }
          if (recording && buf.size < rate * seconds) {
            liveWaiting = true
            delay(200)
            continue
          }
          // a piece is ready: notes stop for it (they redo their step later)
          liveWaiting = false
          livePieces++
          engine.stopNotes(true)
          if (buf.size < rate / 4) break
          val cut = if (recording) quietCut(buf, rate) else buf.size
          val piece = buf.copyOfRange(0, cut)
          buf = buf.copyOfRange(cut, buf.size)
          val at = consumed * 1000 / rate
          consumed += cut
          // a quiet room still gets something written ("Thank you." every block): only blocks with speech are sent
          val share = com.saad.omnichat.media.SpeechGate.speechShare(piece, rate)
          if (share >= com.saad.omnichat.media.SpeechGate.MIN_SHARE) {
            _state.update { it.copy(voice = it.voice.copy(live = it.voice.live?.copy(writing = true))) }
            val text = runCatching { engine.transcribe(piece, lang) }.onFailure { AppLog.e("live transcribe failed", it) }.getOrDefault("").let(::dropFiller)
              .let { t -> if (share < 0.2 && com.saad.omnichat.media.SpeechGate.isSilencePhrase(t)) "" else t }
            _state.update { st ->
              val l = st.voice.live ?: return@update st
              st.copy(voice = st.voice.copy(live = l.copy(writing = false, lines = if (text.isBlank()) l.lines else l.lines + LiveLine(at, text, cut * 1000L / rate))))
            }
            if (text.isNotBlank()) saveLive()
            _state.value.voice.live?.let { l -> runCatching { LiveTranscribeService.progress(app, "${LiveTranscript.stamp(consumed * 1000 / rate)} · ${l.lines.size} parts · ${l.lines.lastOrNull()?.text?.takeLast(60).orEmpty()}") } }
          }
          engine.stopNotes(false)
          if (!recording && buf.size < rate / 4) break
        }
        liveWaiting = true
        engine.stopNotes(false)
        _state.update { st -> st.copy(voice = st.voice.copy(live = st.voice.live?.copy(running = false, writing = false))) }
        saveLive()
        LiveTranscribeService.stop(app)
      }
    liveJob?.invokeOnCompletion { LiveTranscribeService.stop(app) }
    if (s.settings.meetingNotes) startNotes()
  }

  // true while the live loop waits for audio to build up: the time notes may use the model
  @Volatile private var liveWaiting = true
  @Volatile private var livePieces = 0L // pieces taken: a notes step that saw this change was stopped, not failed
  private var notesJob: Job? = null
  private var summaryWanted = false

  /**
   * Meeting notes while live transcribe listens (see [MeetingNotes]): each new line is checked for action items and
   * decisions, each finished minute gets a key point or two, and when listening stops a summary is written. A step
   * runs only while the live loop waits for audio, and a piece that becomes ready stops it at once; the step is then
   * done again later. So transcription is never kept waiting.
   */
  private fun startNotes() {
    notesJob?.cancel()
    notesJob =
      viewModelScope.launch {
        var fails = 0
        while (isActive) {
          val l = _state.value.voice.live ?: break
          val step = nextNoteStep(l)
          if (step == null) {
            if (!l.running && notesJob?.isActive == true && liveJob?.isActive != true) break
            delay(500)
            continue
          }
          // LFM3-Audio only transcribes (asked for notes it found 0 of 5 action items and looped): while it listens the
          // notes wait, and once listening is over the chat model comes back and writes them for the whole meeting
          if (speechLoaded || modelSwap.isLocked) {
            if (l.running || liveJob?.isActive == true) {
              delay(500)
              continue
            }
            useChatModel()
            continue
          }
          if (l.running && !liveWaiting) {
            delay(150)
            continue
          }
          _state.update { st -> st.copy(voice = st.voice.copy(live = st.voice.live?.copy(noting = true))) }
          val t0 = System.currentTimeMillis()
          val pieces = livePieces
          val done = runCatching { step() }.onFailure { AppLog.e("meeting notes failed", it) }.getOrDefault(false)
          val stopped = !done && livePieces != pieces
          _state.update { st -> st.copy(voice = st.voice.copy(live = st.voice.live?.copy(noting = false))) }
          if (done) {
            fails = 0
            saveLive()
          } else if (!stopped && ++fails >= 3) {
            // the same step keeps failing (not just stopped for a piece): skip it rather than loop
            AppLog.w("meeting notes: skipping a step after 3 tries")
            skipNoteStep()
            fails = 0
          }
          AppLog.i("meeting notes: step ${if (done) "done" else if (stopped) "stopped for a piece" else "failed"} in ${System.currentTimeMillis() - t0} ms")
        }
        _state.update { st -> st.copy(voice = st.voice.copy(live = st.voice.live?.copy(noting = false))) }
      }
  }

  /** The next notes step for [l] as work to do, or null when the notes are up to date; it returns false if it didn't finish. */
  private fun nextNoteStep(l: LiveTranscript): (suspend () -> Boolean)? {
    val lines = l.lines.map { MeetingNotes.Line(it.at, it.text) }
    return when (val step = MeetingNotes.nextStep(lines, l.notes, l.running, summaryWanted)) {
      null -> null
      is MeetingNotes.Step.CheckLine -> {
        {
          val line = lines[step.index]
          var items = l.notes.items
          var complete = true
          // each sentence with the ones around it, across line breaks ("…the delivery date. That's me, Ben.")
          val before = lines.getOrNull(step.index - 1)?.let { MeetingNotes.sentences(it.text).lastOrNull() }.orEmpty()
          val after = lines.getOrNull(step.index + 1)?.let { MeetingNotes.sentences(it.text).firstOrNull() }.orEmpty()
          val said = listOf(before) + MeetingNotes.sentences(line.text) + listOf(after)
          for (i in 1 until said.size - 1) {
            val c = said[i]
            if (!MeetingNotes.isCandidate(c)) continue
            val reply = engine.notesAsk(MeetingNotes.SYSTEM, MeetingNotes.itemQuestion(said[i - 1], c, said[i + 1]), 40)
            if (reply == null) { complete = false; break }
            MeetingNotes.parseItem(reply, line.at, said[i - 1] + " " + c + " " + said[i + 1])?.let { items = MeetingNotes.addItem(items, it) }
          }
          if (complete) updateNotes { it.copy(items = items, decisions = it.decisions + MeetingNotes.decisions(line.text, line.at), checked = step.index + 1) }
          complete
        }
      }
      is MeetingNotes.Step.Minute -> {
        {
          if (step.text == null) {
            updateNotes { it.copy(minutes = step.minute + 1) }
            true
          } else {
            val reply = engine.notesAsk(MeetingNotes.SYSTEM, MeetingNotes.pointsQuestion(step.text), 80)
            if (reply != null) updateNotes { it.copy(points = MeetingNotes.addPoints(it.points, MeetingNotes.parsePoints(reply, step.text, step.minute * MeetingNotes.MINUTE)), minutes = step.minute + 1) }
            reply != null
          }
        }
      }
      MeetingNotes.Step.Summary -> {
        {
          val summary = summarize(l.notes)
          if (summary != null) {
            summaryWanted = false
            updateNotes { it.copy(summary = summary) }
          }
          summary != null
        }
      }
    }
  }

  private fun skipNoteStep() {
    val l = _state.value.voice.live ?: return
    when (val step = MeetingNotes.nextStep(l.lines.map { MeetingNotes.Line(it.at, it.text) }, l.notes, l.running, summaryWanted)) {
      is MeetingNotes.Step.CheckLine -> updateNotes { it.copy(checked = step.index + 1) }
      is MeetingNotes.Step.Minute -> updateNotes { it.copy(minutes = step.minute + 1) }
      MeetingNotes.Step.Summary -> { summaryWanted = false; updateNotes { it.copy(summary = it.summary ?: "") } }
      null -> {}
    }
  }

  /**
   * The whole meeting in a few sentences, from its key points (an hour has ~100). More than fit the side context at
   * once are summarized in groups first, and the group summaries then make the final one.
   */
  private suspend fun summarize(n: MeetingNotes.Notes): String? {
    var points = n.points
    while (points.size > SUMMARY_GROUP) {
      val groups = points.chunked(SUMMARY_GROUP).map { g ->
        engine.notesAsk(MeetingNotes.SYSTEM, MeetingNotes.summaryQuestion(g, emptyList(), emptyList()), 160) ?: return null
      }
      points = groups.mapIndexed { i, t -> MeetingNotes.KeyPoint(points[i * SUMMARY_GROUP].at, t.replace("\n", " ")) }
    }
    return engine.notesAsk(MeetingNotes.SYSTEM, MeetingNotes.summaryQuestion(points, n.decisions, n.items), 220)
  }

  private fun updateNotes(f: (MeetingNotes.Notes) -> MeetingNotes.Notes) =
    _state.update { st -> st.copy(voice = st.voice.copy(live = st.voice.live?.let { it.copy(notes = f(it.notes)) })) }

  /** Writes a summary now (while listening it waits for a pause between pieces). */
  fun liveSummarize() {
    val l = _state.value.voice.live ?: return
    if (l.running && speechInUse) return notice("The notes and summary are written when you stop: the speech model only transcribes.")
    summaryWanted = true
    updateNotes { it.copy(summary = null) }
    if (notesJob?.isActive != true) startNotes()
    if (l.notes.points.size < 2) notice("Summaries need a couple of minutes of transcript first")
  }

  /** Bookmarks this moment of the recording. */
  fun liveMark() {
    val l = _state.value.voice.live ?: return
    if (!l.running) return
    val at = System.currentTimeMillis() - l.started
    _state.update { st -> st.copy(voice = st.voice.copy(live = st.voice.live?.copy(marks = st.voice.live.marks + at))) }
    saveLive()
  }

  /** A new chat with the transcript and its notes attached, to ask anything about the meeting. */
  fun liveAskInChat() {
    val l = _state.value.voice.live ?: return
    if (l.lines.isEmpty()) return
    val app = getApplication<Application>()
    viewModelScope.launch {
      val name = "Meeting " + java.text.SimpleDateFormat("yyyy-MM-dd HHmm", java.util.Locale.US).format(java.util.Date(l.started)) + ".md"
      val f = withContext(Dispatchers.IO) { runCatching { java.io.File(app.cacheDir, "meetings").apply { mkdirs() }.resolve(name).apply { writeText(l.markdown()) } }.getOrNull() }
        ?: return@launch notice("Couldn't write the meeting to a file for the chat")
      newChat()
      attach(listOf(Uri.fromFile(f)))
    }
  }

  private val liveFile get() = java.io.File(getApplication<Application>().filesDir, "live-transcript.json")
  private val liveSaveLock = kotlinx.coroutines.sync.Mutex()

  /** Auto-save: the transcript and notes as they stand, written whole to a new file that then replaces the old. */
  private fun saveLive() {
    val l = _state.value.voice.live ?: return
    viewModelScope.launch(Dispatchers.IO) {
      liveSaveLock.withLock {
        runCatching {
          val tmp = java.io.File(liveFile.path + ".tmp")
          tmp.writeText(l.toJson().toString())
          tmp.renameTo(liveFile)
        }.onFailure { AppLog.w("live transcript auto-save failed: ${it.message}") }
      }
    }
  }

  /** A transcript auto-saved before the app closed (a crash, the phone restarting): back on the page, stopped. */
  private fun restoreLive() {
    viewModelScope.launch {
      val saved = withContext(Dispatchers.IO) { runCatching { liveFile.takeIf { it.exists() }?.readText()?.let { LiveTranscript.fromJson(JSONObject(it)) } }.getOrNull() }
      if (saved == null || saved.lines.isEmpty() || _state.value.voice.live != null) return@launch
      _state.update { it.copy(voice = it.voice.copy(live = saved)) }
      AppLog.i("live transcript restored: ${saved.lines.size} parts")
      // notes it hadn't caught up with are finished once the model is loaded
      if (_state.value.settings.meetingNotes) viewModelScope.launch {
        while (_state.value.phase != Phase.Ready) delay(500)
        if (_state.value.voice.live === saved) startNotes()
      }
      // no chat notice: the chat's live-transcript banner (with Open) shows it, and goes away when it is closed
    }
  }

  /** Stops listening; what was said last is still written down. */
  /** The live-transcribe microphone's level, for the page's glow. */
  fun liveLevel(): Float = liveMic?.level ?: 0f

  fun liveStop() {
    val rec = liveMic ?: return
    liveMic = null
    viewModelScope.launch(Dispatchers.IO) { rec.end() }
  }

  /** Leaves live transcribe (the transcript is gone unless it was copied or saved). */
  fun liveClose() {
    liveMic?.let { m -> liveMic = null; viewModelScope.launch(Dispatchers.IO) { m.end() } }
    liveJob?.cancel()
    notesJob?.cancel()
    engine.stopNotes(true)
    summaryWanted = false
    _state.update { it.copy(voice = it.voice.copy(live = null)) }
    viewModelScope.launch(Dispatchers.IO) { liveSaveLock.withLock { liveFile.delete() } }
    viewModelScope.launch {
      liveJob?.join()
      notesJob?.join()
      useChatModel()
    }
  }

  /** What a saved transcript holds. */
  enum class LiveFormat { STAMPED, PLAIN, MARKDOWN, SUBTITLES }

  fun liveText(format: LiveFormat): String? {
    val t = _state.value.voice.live ?: return null
    return when (format) {
      LiveFormat.STAMPED -> t.export(true)
      LiveFormat.PLAIN -> t.export(false)
      LiveFormat.MARKDOWN -> t.markdown()
      LiveFormat.SUBTITLES -> t.srt(_state.value.settings.liveSeconds * 1000L)
    }
  }

  fun liveSave(uri: Uri?, format: LiveFormat) {
    val text = liveText(format) ?: return
    if (uri == null) return
    viewModelScope.launch(Dispatchers.IO) {
      runCatching { getApplication<Application>().contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) } }
        .onSuccess { notice("Transcript saved") }
        .onFailure { AppLog.e("saving transcript failed", it); notice("Couldn't save the transcript: ${it.message}") }
    }
  }

  /** Where to end a piece: the quietest 50 ms in its last 1.5 s, so the cut falls between words. */
  private fun quietCut(pcm: FloatArray, rate: Int): Int {
    val frame = rate / 20
    var best = pcm.size
    var bestE = Double.MAX_VALUE
    var i = maxOf(0, pcm.size - rate * 3 / 2)
    while (i + frame <= pcm.size) {
      var e = 0.0
      for (j in i until i + frame) e += pcm[j] * pcm[j]
      if (e < bestE) {
        bestE = e
        best = i + frame / 2
      }
      i += frame
    }
    return best
  }

  private fun peakLevel(pcm: FloatArray): Double {
    var peak = 0.0
    for (i in pcm.indices step 160) {
      var e = 0.0
      for (j in i until minOf(i + 160, pcm.size)) e += pcm[j] * pcm[j]
      peak = maxOf(peak, kotlin.math.sqrt(e / 160))
    }
    return peak
  }

  /** Near-silence makes the model invent filler ("uh, uh, uh"); such output is dropped. */
  private fun dropFiller(t: String): String =
    t.takeUnless { Regex("""\w+""").findAll(t.lowercase()).map { it.value }.toList().let { w -> w.size > 6 && w.toSet().size <= 2 } }.orEmpty()

  private fun voiceListen() {
    if (_state.value.phase != Phase.Ready || _state.value.generating || _state.value.voice.live?.running == true) return
    if (speechInUse) return notice(SPEECH_BUSY)
    if (voiceMic != null) return
    player.stop()
    val rec = MicRecorder(engine.audioSampleRate)
    if (!rec.start()) {
      notice(MIC_BUSY)
      return _state.update { it.copy(voice = it.voice.copy(phase = VoicePhase.IDLE)) }
    }
    voiceMic = rec
    _state.update { it.copy(voice = it.voice.copy(phase = VoicePhase.LISTENING, heard = false)) }
    if (_state.value.voice.camera) sendOnPause(rec)
  }

  private fun voiceSend() {
    val rec = voiceMic ?: return
    voiceMic = null
    viewModelScope.launch {
      val pcm = withContext(Dispatchers.IO) { rec.stop() }
      if (pcm.size < engine.audioSampleRate / 3) {
        _state.update { it.copy(voice = it.voice.copy(phase = VoicePhase.IDLE, heard = false)) }
        return@launch
      }
      val s = _state.value
      // with the live camera on, the frame current as you finished asking; else a photo you picked, if any
      val shot = if (s.voice.camera) cameraShot() else null
      if (s.voice.camera && shot == null) notice("The camera had no picture yet, so this went without one.")
      val photo = shot ?: s.voice.photo
      val atts = listOfNotNull(photo, Attachment(ids.incrementAndGet(), AttachmentKind.AUDIO, "Spoken · ${seconds(pcm.size)}", null, Media.Audio(pcm)))
      val user = ChatMessage(ids.incrementAndGet(), Role.USER, "", atts)
      val reply = ChatMessage(ids.incrementAndGet(), Role.ASSISTANT, "", activity = Activity.LISTENING, live = LiveStats())
      _state.update { it.copy(messages = it.messages + user + reply, generating = true, voice = it.voice.copy(phase = VoicePhase.THINKING, reply = "", photo = null, heard = false, speed = null)) }
      val app = getApplication<Application>()
      GenerationService.start(app, "Listening to you")
      stopRequested = false
      val lang = (VoiceLanguages[s.settings.voiceLang] ?: "English").substringBefore(" (")
      // The reply is written as text and each sentence is read out in LFM3's TTS mode, the only mode where the
      // voice (female/male) and language can be chosen; the mixed text+speech mode always uses its default voice.
      val seeing =
        if (shot != null) " The newest picture is what my phone's camera sees right now; answer about it when my question is about what I'm showing you. Earlier pictures are what it saw before." else ""
      val instruction = "(We are talking out loud.$seeing Reply in $lang, briefly and conversationally, in plain spoken sentences: no lists, markdown or emoji.)"
      genJob =
        viewModelScope.launch {
          useChatModel()
          // what is shown and saved: the spoken text, without tool calls
          val text = StringBuilder()
          var failed = false
          var rebuild = false
          try {
            // a camera conversation adds a picture (~1k tokens) every turn: before the context fills, the chat is
            // rebuilt from its text, earlier pictures kept only by name with what was said about them
            if (shot != null) {
              val (used, size) = engine.context()
              if (size > 0 && used + CAMERA_TURN_ROOM > size) {
                AppLog.i("camera chat: context $used of $size, rebuilt from text")
                engine.reset(systemPrompt(), withContext(Dispatchers.Default) { modelHistory(_state.value.messages.filterNot { it.id == user.id || it.id == reply.id }) })
              }
            }
            var turnText = if (offered.isNotEmpty()) instruction + TOOL_HINT else instruction
            var turnMedia = atts.map { it.media }
            var role = "user"
            val tried = HashSet<ToolCall>()
            val sources = ArrayList<Source>()
            var calls = 0
            while (true) {
              if (stopRequested) break
              val raw = StringBuilder()
              engine.speak(turnText, turnMedia, s.settings.temperature, 400, s.settings.voiceLang, s.settings.voiceGender, role).collect { ev ->
              when (ev) {
                is VoiceEvent.Prefilled -> {
                  updateReply(reply.id) { it.copy(activity = Activity.THINKING) }
                  // a tool result's turn adds to the first read rather than replacing it
                  _state.update { st ->
                    val was = st.voice.speed
                    st.copy(voice = st.voice.copy(speed = VoiceSpeed((was?.promptTokens ?: 0) + ev.promptTokens, (was?.readMillis ?: 0) + ev.millis, was?.tokensPerSecond, was?.realTime)))
                  }
                }
                is VoiceEvent.Speed ->
                  _state.update { st ->
                    st.copy(voice = st.voice.copy(speed = (st.voice.speed ?: VoiceSpeed(0, 0)).copy(
                      tokensPerSecond = if (ev.writeMillis > 0) ev.tokens * 1000f / ev.writeMillis else null,
                      realTime = if (ev.speechMillis > 0) (ev.audioSeconds * 1000 / ev.speechMillis).toFloat() else null,
                    )))
                  }
                is VoiceEvent.Text -> {
                  raw.append(ev.text)
                  val shown = uncited(text.toString() + raw.toString().substringBefore("<tool_call>"), sources)
                  updateReply(reply.id) { it.copy(text = shown, activity = Activity.WRITING) }
                  _state.update { it.copy(voice = it.voice.copy(reply = shown)) }
                }
                is VoiceEvent.Audio -> {
                  player.play(ev.pcm, ev.sampleRate)
                  if (_state.value.voice.phase != VoicePhase.SPEAKING) {
                    _state.update { it.copy(voice = it.voice.copy(phase = VoicePhase.SPEAKING)) }
                    GenerationService.progress(app, "Speaking")
                  }
                }
                is VoiceEvent.Done -> {}
              }
              }
              text.append(raw.toString().substringBefore("<tool_call>"))
              // the model asked for a tool: run it like the text chat does, then let it answer (out loud) with the result
              val call = if (calls < MAX_TOOL_CALLS && !stopRequested) ToolCalls.parse(raw.toString())?.takeIf { it.name in offered && it !in tried } else null
              if (call == null) break
              calls++
              tried += call
              AppLog.i("voice: model called ${call.name}: ${call.args}")
              _state.update { it.copy(voice = it.voice.copy(phase = VoicePhase.THINKING)) }
              turnText = runTool(call, reply.id, sources)
              turnMedia = emptyList()
              role = "tool"
            }
          } catch (e: EngineException) {
            failed = true
            AppLog.e("voice reply failed: ${e.message}")
            updateReply(reply.id) { it.copy(text = text.toString().ifEmpty { e.message ?: "Couldn't answer" }, error = text.isEmpty()) }
            if (e.needsReset) rebuild = true
          } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
          } catch (e: Throwable) {
            // the speaker couldn't start, a tool threw...: the reply fails, the app stays up
            failed = true
            AppLog.e("voice reply failed", e)
            updateReply(reply.id) { it.copy(text = text.toString().ifEmpty { "Something went wrong: ${e.message ?: e.javaClass.simpleName}" }, error = text.isEmpty()) }
          } finally {
            runCatching { player.finish() }
            // a stop while speaking can cut a decode of the chat itself short (its positions are then off by one and the
            // next question fails): a failed or stopped spoken reply rebuilds the chat from its text
            if (rebuild || stopRequested) {
              runCatching { engine.reset(systemPrompt(), withContext(Dispatchers.Default) { modelHistory(_state.value.messages.filterNot { it.id == user.id || it.id == reply.id }) }) }
                .onFailure { AppLog.e("voice: chat rebuild failed", it) }
            }
            updateReply(reply.id) { it.copy(activity = Activity.DONE) }
            dropMedia(user.id)
            _state.update { it.copy(generating = false) }
            GenerationService.stop(app, text.toString().trim().take(80).takeIf { it.isNotEmpty() }?.let { "Reply ready: $it" })
            runCatching { saveChat() }
            // back to idle once the queued speech has played
            while (player.isPlaying) delay(100)
            if (_state.value.voice.phase == VoicePhase.SPEAKING || _state.value.voice.phase == VoicePhase.THINKING) {
              _state.update { it.copy(voice = it.voice.copy(phase = VoicePhase.IDLE)) }
              // a camera conversation goes on by itself: listening again once the answer has been said
              if (_state.value.voice.camera && !failed && !stopRequested) voiceListen()
            }
          }
        }
    }
  }

  @Volatile private var stopRequested = false

  fun stop() {
    stopRequested = true
    ocr.stop()
    engine.abort()
  }

  /**
   * A long recording: decode [SEGMENT_SECONDS] at a time and transcribe each piece in a fresh context, streaming the
   * transcript into the reply. The transcript then becomes the model's memory of the recording, and [question] (if
   * any) is answered from it.
   */
  private fun transcribeLong(question: String, atts: List<Attachment>, long: Attachment) {
    val la = long.media as Media.LongAudio
    val app = getApplication<Application>()
    val parts = kotlin.math.ceil(la.seconds / SEGMENT_SECONDS).toInt().coerceAtLeast(1)
    val user = ChatMessage(ids.incrementAndGet(), Role.USER, question, atts)
    val reply = ChatMessage(ids.incrementAndGet(), Role.ASSISTANT, "", activity = Activity.LISTENING, live = LiveStats())
    stopRequested = false
    _state.update { it.copy(messages = it.messages + user + reply, pending = emptyList(), generating = true, liveTps = null) }
    GenerationService.start(app, "Transcribing ${la.name} · part 1 of $parts")
    val history = modelHistory(_state.value.messages.filterNot { it.id == user.id || it.id == reply.id })
    val header = "**Transcript of ${la.name}** (${hms(la.seconds)})\n\n"

    genJob =
      viewModelScope.launch {
        useChatModel()
        // the transcriber needs no tools (their descriptions are ~1k tokens per part); the next message puts them back
        if (offered.isNotEmpty()) {
          offered = emptyList()
          engine.setTools(emptyList())
        }
        val sb = StringBuilder()
        var failed: String? = null
        try {
          AudioStream(app.contentResolver, la.uri, engine.audioSampleRate).use { stream ->
            var i = 0
            while (!stopRequested) {
              val pcm = withContext(Dispatchers.IO) { stream.read(engine.audioSampleRate * SEGMENT_SECONDS) } ?: break
              if (pcm.size < engine.audioSampleRate / 2) break // a last sliver of silence
              i++
              val start = (i - 1) * SEGMENT_SECONDS
              val stamp = if (start % STAMP_EVERY_SECONDS == 0) "**[${hms(start.toDouble())}]** " else ""
              GenerationService.progress(app, "Transcribing ${la.name} · part $i of $parts")
              updateReply(reply.id) { it.copy(activity = Activity.LISTENING, text = header + sb + "_Listening to part $i of $parts…_") }
              engine.reset(TRANSCRIBE_SYSTEM)
              val part = StringBuilder()
              var shown = 0L
              engine.generate(TRANSCRIBE_PROMPT, listOf(Media.Audio(pcm)), 0f, 768).collect { ev ->
                if (ev is GenEvent.Piece) {
                  part.append(ev.text)
                  // throttled like a chat reply: a 2-hour transcript is 100k characters to rebuild and re-read per token
                  val now = System.currentTimeMillis()
                  if (now - shown >= TEXT_FRAME_MS) {
                    shown = now
                    updateReply(reply.id) { it.copy(activity = Activity.WRITING, text = header + sb + stamp + part) }
                  }
                }
              }
              if (part.isNotBlank()) {
                if (stamp.isNotEmpty() && sb.isNotEmpty()) sb.append("\n\n")
                sb.append(stamp).append(part.toString().trim()).append(' ')
              }
            }
          }
        } catch (e: kotlinx.coroutines.CancellationException) {
          throw e
        } catch (e: Throwable) {
          AppLog.e("transcription failed", e)
          failed = e.message ?: e.javaClass.simpleName
        }

        val transcript = sb.toString().trim()
        val status = when {
          failed != null -> "\n\n_Stopped: ${failed}_"
          stopRequested -> "\n\n_Stopped by you; the transcript above is partial._"
          else -> ""
        }
        updateReply(reply.id) { it.copy(activity = Activity.DONE, text = (header + transcript + status).trim(), error = transcript.isEmpty() && failed != null) }

        // the model remembers the recording through its transcript, cut to what the context can hold
        val budget = ((effectiveCtx() - 4096) * 3).coerceAtLeast(4000)
        val memory = if (transcript.length <= budget) transcript else transcript.take(budget) + "\n[transcript continues; cut to fit the context]"
        if (transcript.length > budget) notice("The transcript is longer than the model's context (${"%,d".format(effectiveCtx())} tokens), so answers only use its first part. Raise Context size in Settings to use more.")
        engine.reset(systemPrompt(), history + listOf("user" to "[audio shared: ${la.name}] Transcribe this recording.", "assistant" to memory))

        if (question.isNotBlank() && transcript.isNotEmpty() && !stopRequested) {
          val answer = ChatMessage(ids.incrementAndGet(), Role.ASSISTANT, "", activity = Activity.THINKING, live = LiveStats())
          _state.update { it.copy(messages = it.messages + answer) }
          GenerationService.progress(app, "Answering your question about ${la.name}")
          val out = StringBuilder()
          try {
            engine.generate(question, emptyList(), _state.value.settings.temperature, _state.value.settings.maxTokens).collect { ev ->
              if (ev is GenEvent.Piece) {
                out.append(ev.text)
                updateReply(answer.id) { it.copy(activity = Activity.WRITING, text = out.toString()) }
              }
            }
          } catch (e: EngineException) {
            AppLog.e("answer after transcription failed: ${e.message}")
          } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
          } catch (e: Throwable) {
            AppLog.e("answer after transcription failed", e)
          }
          updateReply(answer.id) { it.copy(activity = Activity.DONE, text = out.toString().ifEmpty { "No answer was produced." }) }
        }

        _state.update { it.copy(generating = false) }
        GenerationService.stop(app, if (transcript.isNotEmpty()) "Transcript of ${la.name} is ready" else null)
        runCatching { saveChat() }.onFailure { AppLog.e("saving chat failed", it) }
      }
  }

  private fun hms(seconds: Double): String {
    val t = seconds.toLong()
    return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60) else "%d:%02d".format(t / 60, t % 60)
  }

  /**
   * Once the model has read a message, its photos' pixels and its audio (3 MB a photo, up to 38 MB a recording) are
   * not needed again: they become the placeholders a reopened chat has. The picture is still shown, from its file.
   */
  private fun dropMedia(id: Long) =
    _state.update { st ->
      st.copy(messages = st.messages.map { m ->
        if (m.id != id) m
        else m.copy(attachments = m.attachments.map { a -> if (a.kind != AttachmentKind.DOCUMENT && a.media !is Media.Document) a.copy(media = Media.Document(a.label, "")) else a })
      })
    }

  private fun updateReply(id: Long, f: (ChatMessage) -> ChatMessage) =
    _state.update { s -> s.copy(messages = s.messages.map { if (it.id == id) f(it) else it }) }

  private fun notice(text: String) =
    _state.update { it.copy(messages = it.messages + ChatMessage(ids.incrementAndGet(), Role.NOTICE, text)) }

  private fun seconds(samples: Int) = "%.1f s".format(samples.toFloat() / engine.audioSampleRate)

  private fun displayName(uri: Uri): String =
    getApplication<Application>().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
      if (it.moveToFirst()) it.getString(0) else null
    } ?: uri.lastPathSegment ?: "file"

  private fun readSettings() =
    Settings(
      backend = prefs.getString("backend", null),
      temperature = prefs.getFloat("temperature", 0f),
      maxTokens = prefs.getInt("maxTokens", 2048),
      nCtx = prefs.getInt("nCtx", 16384),
      threads = prefs.getInt("threads", 4),
      animateWhileGenerating = true, // always on; the NPU doesn't share work with the UI
      mediaOnGpu = prefs.getBoolean("mediaOnGpu", true),
      fastHead = prefs.getBoolean("fastHead", false),
      webSearch = prefs.getBoolean("webSearch", false),
      exaKey = prefs.getString("exaKey", "") ?: "",
      firecrawlKey = prefs.getString("firecrawlKey", "") ?: "",
      voiceLang = prefs.getString("voiceLang", "en") ?: "en",
      voiceGender = prefs.getString("voiceGender", "female") ?: "female",
      liveSeconds = prefs.getInt("liveSeconds", LIVE_SECONDS_DEFAULT).coerceIn(LIVE_SECONDS_RANGE),
      meetingNotes = prefs.getBoolean("meetingNotes", true),
      voiceSpeed = prefs.getBoolean("voiceSpeed", true),
      speechModel = prefs.getBoolean("speechModel", true),
      phoneTools = prefs.getBoolean("phoneTools", true),
      smartSearch = prefs.getBoolean("smartSearch", false),
      gallerySearch = prefs.getBoolean("gallerySearch", false),
      galleryMonths = prefs.getInt("galleryMonths", 0),
      ocr = prefs.getBoolean("ocr", false),
      systemPrompt = prefs.getString("systemPrompt", null) ?: Settings().systemPrompt,
    )

  private fun writeSettings(s: Settings) =
    prefs
      .edit()
      .putString("backend", s.backend)
      .putFloat("temperature", s.temperature)
      .putInt("maxTokens", s.maxTokens)
      .putInt("nCtx", s.nCtx)
      .putInt("threads", s.threads)
      .putBoolean("animateWhileGenerating", s.animateWhileGenerating)
      .putBoolean("mediaOnGpu", s.mediaOnGpu)
      .putBoolean("fastHead", s.fastHead)
      .putBoolean("webSearch", s.webSearch)
      .putString("exaKey", s.exaKey.trim())
      .putString("firecrawlKey", s.firecrawlKey.trim())
      .putString("voiceLang", s.voiceLang)
      .putString("voiceGender", s.voiceGender)
      .putInt("liveSeconds", s.liveSeconds)
      .putBoolean("meetingNotes", s.meetingNotes)
      .putBoolean("voiceSpeed", s.voiceSpeed)
      .putBoolean("speechModel", s.speechModel)
      .putBoolean("phoneTools", s.phoneTools)
      .putBoolean("smartSearch", s.smartSearch)
      .putBoolean("gallerySearch", s.gallerySearch)
      .putInt("galleryMonths", s.galleryMonths)
      .putBoolean("ocr", s.ocr)
      .putString("systemPrompt", s.systemPrompt)
      .apply()

  override fun onCleared() {
    galleryJob?.cancel()
    liveJob?.cancel()
    notesJob?.cancel()
    engine.abort()
    engine.stopNotes(true)
    runCatching { player.stop() }
    // every open mic is closed (one left recording grows its buffer without limit, and keeps the mic on), off the
    // main thread: ending one waits for its reading thread
    val mics = listOfNotNull(mic, liveMic, voiceMic)
    mic = null
    liveMic = null
    voiceMic = null
    kotlin.concurrent.thread(name = "mic-close") { mics.forEach { runCatching { it.end() } } }
  }
}
