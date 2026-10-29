package com.saad.omnichat.llm

/** JNI surface of libomni.so. Every call except [nativeAbort] must run on the engine thread. */
object LlamaNative {
  init {
    System.loadLibrary("omni")
  }

  external fun nativeInit(libDir: String)

  /** Opens the session log file that native and [com.saad.omnichat.AppLog] lines are appended to. */
  external fun nativeOpenLog(path: String)

  /** Thread-safe; appends one line to the session log only (not logcat). */
  external fun nativeLog(priority: Int, message: String)

  /** "name\tdescription" for every GPU/accelerator device ggml found. */
  external fun nativeDevices(): Array<String>

  /** Returns null on success, error text otherwise. Empty [device] means CPU only. */
  external fun nativeLoad(model: String, mmproj: String, device: String, nCtx: Int, nThreads: Int, mediaGpu: Boolean, flashAttn: Int): String?

  external fun nativeUnload()

  external fun nativeDeviceInfo(): String

  /** {vision, audio, audioSampleRate} */
  external fun nativeCaps(): IntArray

  external fun nativeReset(systemPrompt: String)

  /** Earlier turns ("user"/"assistant") of a reopened chat; call right after [nativeReset]. */
  external fun nativeSetHistory(roles: Array<String>, contents: Array<String>)

  external fun nativeAddImage(rgb: ByteArray, width: Int, height: Int): Boolean

  external fun nativeAddAudio(pcm: FloatArray): Boolean

  /** {status, promptTokens, nPast} */
  external fun nativePrompt(role: String, text: String, temperature: Float, maxTokens: Int): IntArray

  /** Adds a tool the model may call (empty [name] clears them); applies from the next [nativeReset]. */
  external fun nativeSetTool(name: String, description: String, parametersJson: String)

  /** Null when the turn is over, "" when the token ended mid UTF-8 sequence. */
  external fun nativeNext(): String?

  external fun nativeEndTurn()

  /** Per-op NPU time summed since the last call (needs GGML_HEXAGON_PROFILE=1 at start-up), then reset. */
  external fun nativeProfileDump(): String

  /** Writes a copy of [input] with the embedding/output table in Q4_0. Null on success, error text otherwise. */
  external fun nativeQuantizeHead(input: String, output: String, threads: Int): String?

  /** Spoken reply: evaluates the user turn and opens a mixed text+speech assistant turn. {status, promptTokens, nPast}; status 6 = can't speak. */
  external fun nativeVoicePrompt(text: String, temperature: Float, maxSteps: Int): IntArray

  /** One text token or speech frame: 0 = more, 1 = done, -1 = error. */
  external fun nativeVoiceStep(): Int

  /** Reply text produced since the last call. */
  external fun nativeVoiceText(): String

  /** Speech (mono float PCM at [nativeVoiceRate]) produced since the last call. */
  external fun nativeVoiceAudio(): FloatArray

  external fun nativeVoiceRate(): Int

  /** Last GPU/backend exception message (cleared on read), or null. */
  external fun nativeLastError(): String?

  external fun nativeAbort()

  external fun nativeCtxUsed(): Int

  external fun nativeCtxSize(): Int

  /**
   * EmbeddingGemma 2 on the CPU, separate from the chat model; [mmproj] "" = text only (call again to add it). The image
   * tower runs on [encoderDevice] ("GPUOpenCL"), or the CPU when "".
   */
  external fun nativeEmbedLoad(path: String, mmproj: String, threads: Int, encoderDevice: String): Boolean

  /** Frees the image tower only, so it can be loaded again on another device. */
  external fun nativeEmbedDropTower()

  external fun nativeEmbedUnload()

  /** Unit-length embedding, or null on failure. */
  external fun nativeEmbed(text: String): FloatArray?

  /** Unit-length embedding of a photo, comparable with text query embeddings; null on failure. */
  external fun nativeEmbedImage(rgb: ByteArray, width: Int, height: Int): FloatArray?

  /** Loads MiniCPM-OCR (text part on the CPU, image tower on [encoderDevice] or the CPU); returns where the tower runs. */
  external fun nativeOcrLoad(path: String, mmproj: String, encoderDevice: String, threads: Int): String?

  external fun nativeOcrUnload()

  /** Writes a Q8_0 copy of a bf16 image tower (fast on the CPU, GPU and NPU backends); false on failure. */
  external fun nativeMmprojToQ8(src: String, dst: String): Boolean

  /** true: makes a running (or the next) [nativeOcrRead] stop early; false re-arms it before a new read. */
  external fun nativeOcrStop(stop: Boolean)

  /** The text in a picture (RGB bytes), written down by the OCR model; null on failure. */
  external fun nativeOcrRead(rgb: ByteArray, width: Int, height: Int, prompt: String): String?

  /** Speaks [text] in LFM3's TTS mode with a language code and "female"/"male"; 0 ok, 6 can't speak, 2 error. */
  external fun nativeSpeakStart(text: String, lang: String, voice: String): Int

  /** One speech frame (audio via [nativeVoiceAudio]): 0 more, 1 done, -1 error. */
  external fun nativeSpeakStep(): Int

  external fun nativeSpeakFree()

  /**
   * The next prompt loads its opening (up to the last [split], or the first with [first]) from [path], or reads it and
   * saves it there.
   */
  external fun nativeSetPromptCache(path: String, split: String, first: Boolean)

  /** Dictation: the words in [pcm] (16 kHz, up to a minute) in [language], in a side context; null on failure. */
  external fun nativeTranscribe(pcm: FloatArray, language: String): String?
}
