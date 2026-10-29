package com.saad.omnichat.llm

import com.saad.omnichat.AppLog

import android.content.Context
import android.system.Os
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

data class Backend(val device: String, val label: String) {
  val isCpu get() = device.isEmpty()

  companion object {
    val CPU = Backend("", "CPU")
  }
}

sealed interface Media {
  class Image(val rgb: ByteArray, val width: Int, val height: Int) : Media

  /** A recording too long to hear in one go: transcribed in parts by the view model, never sent to the encoder. */
  class LongAudio(val uri: android.net.Uri, val name: String, val seconds: Double) : Media

  /**
   * Text extracted from a PDF, spreadsheet or text file; it goes into the prompt, not through the encoder. A
   * document too long for the context keeps only its opening in [text] and is searched by meaning: [index] names
   * its passages in the semantic index, and the passages that fit each question are added to it.
   */
  class Document(val name: String, val text: String, val truncated: Boolean = false, val index: String? = null) : Media

  /** [truncated]: the source was longer than the decoder keeps. */
  class Audio(val pcm: FloatArray, val truncated: Boolean = false) : Media
}

sealed interface VoiceEvent {
  data class Prefilled(val promptTokens: Int, val millis: Long) : VoiceEvent

  class Text(val text: String) : VoiceEvent

  class Audio(val pcm: FloatArray, val sampleRate: Int) : VoiceEvent

  data class Done(val millis: Long) : VoiceEvent
}

sealed interface GenEvent {
  data class Prefilled(val promptTokens: Int, val millis: Long) : GenEvent

  data class Piece(val text: String, val genTokens: Int, val genMillis: Long) : GenEvent

  data class Done(val genTokens: Int, val genMillis: Long, val ctxUsed: Int, val ctxSize: Int) : GenEvent
}

class EngineException(message: String, val needsReset: Boolean = false) : Exception(message)

/**
 * The native model lives in process-wide globals (omni_jni.cpp), so every engine shares one thread and one init:
 * a second engine (a second activity) waits its turn instead of freeing the model under the first one's load.
 */
private val engineThread = Executors.newSingleThreadExecutor { r -> Thread(r, "omni-engine") }.asCoroutineDispatcher()
@Volatile private var nativeReady = false

/** Owns the native model. All native work runs on one dedicated thread. */
class OmniEngine(context: Context) {
  /** Saved document states: ~12 KB per token, so a 30k-token spreadsheet is ~360 MB. Android may clear it. */
  private val docState = java.io.File(context.cacheDir, "docstate").apply { mkdirs() }
  private val thread = engineThread
  private val libDir = context.applicationInfo.nativeLibraryDir

  var loadedBackend: Backend? = null
    private set

  var audioSampleRate = 16000
    private set

  private var loadedModel = ""
  private var toolsText = ""

  /**
   * Names the saved state of the current system turn (instructions and tool list, ~1-2k tokens): every new chat
   * starts by loading it instead of reading it again. Null when there is no system turn. Set by [reset].
   */
  private var systemKey: String? = null

  private suspend fun ensureInit() =
    withContext(thread) {
      if (!nativeReady) {
        // FastRPC looks for the NPU-side libggml-htp-v73.so on this path (';'-separated); ours sits in nativeLibraryDir
        Os.setenv("ADSP_LIBRARY_PATH", "$libDir;/vendor/lib/rfsa/adsp;/vendor/dsp/cdsp;/system/lib/rfsa/adsp;/dsp", true)
        LlamaNative.nativeInit(libDir)
        nativeReady = true
      }
    }

  suspend fun backends(): List<Backend> {
    ensureInit()
    val gpus =
      withContext(thread) {
        LlamaNative.nativeDevices().map {
          val (name, desc) = it.split('\t', limit = 2).let { p -> p[0] to p.getOrElse(1) { "" } }
          Backend(name, if (name.startsWith("HTP")) "NPU · Hexagon $name" else "$name · $desc")
        }
      }
    return gpus + Backend.CPU
  }

  suspend fun load(model: String, mmproj: String, backend: Backend, nCtx: Int, nThreads: Int, mediaGpu: Boolean): String {
    ensureInit()
    return withContext(thread) {
      loadedBackend = null
      // Adreno's Vulkan driver fails to compile the flash-attention kernel used for batches of 32+ tokens
      // (any image or audio prompt), so attention runs unfused there. -1 = llama.cpp decides.
      val flashAttn = if (backend.device.startsWith("Vulkan")) 0 else -1
      AppLog.i("load: flashAttn=$flashAttn device=${backend.device.ifEmpty { "CPU" }} ctx=$nCtx threads=$nThreads mediaGpu=$mediaGpu")
      LlamaNative.nativeLoad(model, mmproj, backend.device, nCtx, nThreads, mediaGpu, flashAttn)?.let { throw EngineException(it) }
      loadedBackend = backend
      loadedModel = model
      audioSampleRate = LlamaNative.nativeCaps()[2].takeIf { it > 0 } ?: 16000
      LlamaNative.nativeDeviceInfo()
    }
  }

  suspend fun quantizeHead(input: String, output: String, threads: Int) {
    ensureInit()
    withContext(thread) {
      AppLog.i("converting output layer to Q4_0: $output")
      LlamaNative.nativeQuantizeHead(input, output, threads)?.let { throw EngineException(it) }
    }
  }

  suspend fun unload() =
    withContext(thread) {
      LlamaNative.nativeUnload()
      loadedBackend = null
    }

  /** Starts a chat; [history] holds (role, text) of earlier turns when reopening a saved conversation. */
  suspend fun reset(systemPrompt: String, history: List<Pair<String, String>> = emptyList()) =
    withContext(thread) {
      systemKey =
        if (systemPrompt.isBlank()) null
        else sha1(listOf(loadedModel, loadedBackend?.device.orEmpty(), systemPrompt, toolsText)).take(24).let { "sys-$it" }
      LlamaNative.nativeReset(systemPrompt)
      if (history.isNotEmpty()) LlamaNative.nativeSetHistory(history.map { it.first }.toTypedArray(), history.map { it.second }.toTypedArray())
    }

  /** Thread-safe: stops the running prefill or generation. */
  fun abort() = LlamaNative.nativeAbort()

  /** Replaces the tools the model may call; takes effect at the next [reset]. */
  suspend fun setTools(tools: List<com.saad.omnichat.tools.ToolSpec>) =
    withContext(thread) {
      LlamaNative.nativeSetTool("", "", "")
      tools.forEach { LlamaNative.nativeSetTool(it.name, it.description, it.parameters) }
      toolsText = tools.joinToString("\u0000") { it.name + "\u0000" + it.description + "\u0000" + it.parameters }
    }

  /**
   * LFM3's audio encoder takes a clip in one pass, with memory growing with its length (a 5-minute clip needs GBs and
   * crashed the app), so audio goes in as consecutive pieces of at most [AUDIO_PIECE_SECONDS].
   */
  private fun attach(m: Media) {
    val ok =
      when (m) {
        is Media.Image -> LlamaNative.nativeAddImage(m.rgb, m.width, m.height)
        is Media.Audio -> {
          val piece = audioSampleRate * AUDIO_PIECE_SECONDS
          // a tail under a second rides along with the piece before it
          val starts = (0 until m.pcm.size step piece).filter { it == 0 || m.pcm.size - it >= audioSampleRate }
          starts.indices.all { i ->
            val end = if (i == starts.lastIndex) m.pcm.size else starts[i + 1]
            LlamaNative.nativeAddAudio(if (starts.size == 1) m.pcm else m.pcm.copyOfRange(starts[i], end))
          }
        }
        is Media.Document, is Media.LongAudio -> true
      }
    if (!ok) throw EngineException("Could not attach media")
  }

  /**
   * [role] is "user", or "tool" to hand the model a tool's result. With [cacheKey], a fresh chat's opening (system
   * turn and the documents in [text]) is loaded from a saved state instead of being read again, or saved for next time.
   */
  fun generate(text: String, media: List<Media>, temperature: Float, maxTokens: Int, role: String = "user", cacheKey: String? = null): Flow<GenEvent> =
    flow {
        media.forEach(::attach)
        val saved = promptCache(cacheKey)

        AppLog.i("generate: ${media.size} attachment(s), ${text.length} chars, on ${loadedBackend?.device?.ifEmpty { "CPU" }}")
        val t0 = System.nanoTime()
        val (status, promptTokens, _) = LlamaNative.nativePrompt(role, text, temperature, maxTokens)
        if (status != 0) AppLog.w("prompt status $status")
        when (status) {
          0 -> {}
          1 -> throw EngineException("Could not tokenize the message")
          3 -> throw EngineException("Stopped during prompt processing", needsReset = true)
          5 -> throw EngineException(gpuFailed(), needsReset = true)
          4 -> throw EngineException("Context is full: start a new chat", needsReset = true)
          else -> throw EngineException("Prompt processing failed", needsReset = true)
        }
        emit(GenEvent.Prefilled(promptTokens, (System.nanoTime() - t0) / 1_000_000))
        saved?.takeIf { it.exists() }?.setLastModified(System.currentTimeMillis())

        val g0 = System.nanoTime()
        var n = 0
        try {
          while (true) {
            val piece = LlamaNative.nativeNext() ?: break
            n++
            if (piece.isNotEmpty()) emit(GenEvent.Piece(piece, n, (System.nanoTime() - g0) / 1_000_000))
          }
        } finally {
          LlamaNative.nativeEndTurn()
        }
        LlamaNative.nativeLastError()?.let { throw EngineException(gpuFailed(it), needsReset = true) }
        AppLog.i("generated $n tokens in ${(System.nanoTime() - g0) / 1_000_000} ms")
        LlamaNative.nativeProfileDump().takeIf { it.isNotEmpty() }?.let { AppLog.i("NPU profile (prompt + reply):\n$it") }
        emit(GenEvent.Done(n, (System.nanoTime() - g0) / 1_000_000, LlamaNative.nativeCtxUsed(), LlamaNative.nativeCtxSize()))
      }
      .flowOn(thread)

  /**
   * Sets up the saved state for the next prompt: with [docKey], the chat's opening up to its documents; otherwise, at
   * the start of a chat, its system turn. Returns the file used (touched afterwards to keep it recent).
   */
  private fun promptCache(docKey: String?): java.io.File? {
    val key = docKey ?: systemKey?.takeIf { LlamaNative.nativeCtxUsed() == 0 } ?: return null
    // Android empties the cache folder itself when storage runs low, folder included
    docState.mkdirs()
    val saved = java.io.File(docState, "$key.state")
    pruneDocState(keep = saved)
    if (docKey != null) LlamaNative.nativeSetPromptCache(saved.path, DOC_END, false)
    else LlamaNative.nativeSetPromptCache(saved.path, TURN_END, true)
    return saved
  }

  /** Keeps saved document states under [DOC_STATE_BUDGET], dropping the least recently used. */
  private fun pruneDocState(keep: java.io.File) {
    val files = docState.listFiles().orEmpty().filter { it != keep }.sortedByDescending { it.lastModified() }
    var total = keep.length()
    for (f in files) {
      total += f.length()
      if (total > DOC_STATE_BUDGET || f.name.endsWith(".part")) f.delete()
    }
  }

  private fun gpuFailed(detail: String? = LlamaNative.nativeLastError()) =
    "The GPU driver could not run this step (${detail ?: "unknown error"}). Try another backend in Settings; details are in Settings > Logs."

  /**
   * A spoken reply in a chosen voice: the text reply is written as usual, and each sentence is spoken in LFM3's TTS
   * mode ([lang] code, [voice] "female"/"male") as soon as it is complete, while the rest is still to come.
   */
  fun speak(text: String, media: List<Media>, temperature: Float, maxTokens: Int, lang: String, voice: String, role: String = "user"): Flow<VoiceEvent> =
    flow {
        media.forEach(::attach)
        promptCache(null)
        AppLog.i("spoken reply: ${media.size} attachment(s), voice $voice, $lang")
        val t0 = System.nanoTime()
        val (status, promptTokens, _) = LlamaNative.nativePrompt(role, text, temperature, maxTokens)
        when (status) {
          0 -> {}
          3 -> throw EngineException("Stopped", needsReset = true)
          4 -> throw EngineException("Context is full: start a new chat", needsReset = true)
          5 -> throw EngineException(gpuFailed(), needsReset = true)
          else -> throw EngineException("Couldn't start the reply", needsReset = true)
        }
        emit(VoiceEvent.Prefilled(promptTokens, (System.nanoTime() - t0) / 1_000_000))
        val g0 = System.nanoTime()
        var audioSamples = 0L
        var rate = 0
        var speechNs = 0L

        suspend fun say(sentence: String) {
          // a tool call is for the app: never read it out
          val clean = speakable(sentence.substringBefore("<tool"))
          if (clean.none(Char::isLetterOrDigit)) return
          val s0 = System.nanoTime()
          when (LlamaNative.nativeSpeakStart(clean, lang, voice)) {
            0 -> {}
            6 -> throw EngineException("This model file can't speak")
            else -> throw EngineException(LlamaNative.nativeLastError() ?: "Speaking failed")
          }
          rate = LlamaNative.nativeVoiceRate()
          while (true) {
            val st = LlamaNative.nativeSpeakStep()
            LlamaNative.nativeVoiceAudio().takeIf { it.isNotEmpty() }?.let {
              audioSamples += it.size
              emit(VoiceEvent.Audio(it, rate))
            }
            if (st < 0) throw EngineException(LlamaNative.nativeLastError() ?: "Speaking failed")
            if (st != 0) break
          }
          speechNs += System.nanoTime() - s0
        }

        val pending = StringBuilder()
        val full = StringBuilder()
        try {
          while (true) {
            val piece = LlamaNative.nativeNext() ?: break
            if (piece.isEmpty()) continue
            emit(VoiceEvent.Text(piece))
            full.append(piece)
            // from a tool call on, nothing more is spoken (the app runs the tool, then the reply continues)
            if ("<tool_call>" in full) {
              if (pending.isNotEmpty()) {
                say(pending.toString())
                pending.clear()
              }
              continue
            }
            pending.append(piece)
            // speak every finished sentence (short ones wait for the next, so the voice keeps a natural flow)
            val ends = sentenceEnd.findAll(pending).map { it.range.last + 1 }.lastOrNull { it >= 24 }
            if (ends != null) {
              val sentence = pending.substring(0, ends)
              pending.delete(0, ends)
              say(sentence)
            }
          }
        } finally {
          LlamaNative.nativeEndTurn()
        }
        LlamaNative.nativeLastError()?.let { throw EngineException(gpuFailed(it), needsReset = true) }
        if (pending.isNotBlank()) say(pending.toString())
        val ms = (System.nanoTime() - g0) / 1_000_000
        val audioSec = if (rate > 0) audioSamples.toDouble() / rate else 0.0
        AppLog.i("spoken reply done in $ms ms: %.1f s of speech made in %.1f s (%.2fx real time)".format(audioSec, speechNs / 1e9, if (speechNs > 0) audioSec / (speechNs / 1e9) else 0.0))
        emit(VoiceEvent.Done(ms))
      }
      .flowOn(thread)

  /** Dictation: [pcm] as text, a minute at a time; the chat itself is untouched. */
  suspend fun transcribe(pcm: FloatArray, language: String): String =
    withContext(thread) {
      val piece = audioSampleRate * AUDIO_PIECE_SECONDS
      (0 until pcm.size step piece)
        .mapNotNull { LlamaNative.nativeTranscribe(pcm.copyOfRange(it, minOf(it + piece, pcm.size)), language)?.replace("<think></think>", "")?.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(" ")
    }

  /** Frees the TTS context when voice chat closes. */
  suspend fun releaseSpeech() = withContext(thread) { LlamaNative.nativeSpeakFree() }

  /** A spoken reply to [text] plus [media]: streams the reply's text and its speech as they are produced. */
  fun voice(text: String, media: List<Media>, temperature: Float, maxSteps: Int = 3000): Flow<VoiceEvent> =
    flow {
        media.forEach(::attach)
        AppLog.i("voice: ${media.size} attachment(s), ${text.length} chars")
        val t0 = System.nanoTime()
        val (status, promptTokens, _) = LlamaNative.nativeVoicePrompt(text, temperature, maxSteps)
        when (status) {
          0 -> {}
          4 -> throw EngineException("Context is full: start a new chat", needsReset = true)
          5 -> throw EngineException(gpuFailed(), needsReset = true)
          6 -> throw EngineException("This model file can't speak", needsReset = true)
          3 -> throw EngineException("Stopped", needsReset = true)
          else -> throw EngineException("Couldn't start the spoken reply", needsReset = true)
        }
        emit(VoiceEvent.Prefilled(promptTokens, (System.nanoTime() - t0) / 1_000_000))
        val rate = LlamaNative.nativeVoiceRate()
        val g0 = System.nanoTime()
        try {
          while (true) {
            val st = LlamaNative.nativeVoiceStep()
            LlamaNative.nativeVoiceText().takeIf { it.isNotEmpty() }?.let { emit(VoiceEvent.Text(it)) }
            LlamaNative.nativeVoiceAudio().takeIf { it.isNotEmpty() }?.let { emit(VoiceEvent.Audio(it, rate)) }
            if (st < 0) throw EngineException(LlamaNative.nativeLastError() ?: "Speaking failed", needsReset = true)
            if (st != 0) break
          }
        } finally {
          LlamaNative.nativeEndTurn()
        }
        val ms = (System.nanoTime() - g0) / 1_000_000
        AppLog.i("voice reply done in $ms ms")
        emit(VoiceEvent.Done(ms))
      }
      .flowOn(thread)
}

/** Sentences as they complete in streamed text: the speaker starts on one while the next is being written. */
private val sentenceEnd = Regex("""[.!?…](?=\s)|[。！？؟]|\n""")

/** What the TTS voice should read: no markdown, links, code or emoji. */
internal fun speakable(s: String): String =
  s.replace(Regex("""```[\s\S]*?```"""), " ")
    .replace(Regex("""\[([^\]]*)]\([^)]*\)"""), "$1")
    .replace(Regex("""\[\d+]"""), "")
    .replace(Regex("""[*_#`>|~]"""), "")
    .replace(Regex("""^\s*[-•]\s+""", RegexOption.MULTILINE), "")
    .replace(Regex("""[\p{So}\p{Cn}]"""), "")
    .replace(Regex("""\s+"""), " ")
    .trim()

private fun sha1(parts: List<String>): String {
  val md = java.security.MessageDigest.getInstance("SHA-1")
  parts.forEach { md.update(it.toByteArray()); md.update(0) }
  return md.digest().joinToString("") { "%02x".format(it) }
}

/** The end of a turn in LFM3's chat template; the first one closes the system turn. */
private const val TURN_END = "<|im_end|>\n"

/** Where a prompt's documents end (see ChatViewModel.modelText): the saved state covers everything before it. */
const val DOC_END = "</document>\n\n"

private const val DOC_STATE_BUDGET = 2L shl 30

/** Longest audio piece handed to the encoder at once (its warm-up size is 30 s). */
const val AUDIO_PIECE_SECONDS = 60
