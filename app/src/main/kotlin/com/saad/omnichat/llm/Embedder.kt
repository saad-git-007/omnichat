package com.saad.omnichat.llm

import android.content.Context
import com.saad.omnichat.AppLog
import com.saad.omnichat.CrashReports
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * EmbeddingGemma 2 on the CPU: text (or a photo) in, a meaning vector out, so "what did I ask about my visa?" finds the chat even
 * without the word "visa". Runs on its own thread, beside the chat model on the NPU.
 *
 * The image tower (gallery indexing, ~9 s a photo on the CPU) can also run on the Adreno GPU, which nothing else uses
 * while the chat model sits on the NPU. The first time, both are timed on a test picture and their vectors compared:
 * the GPU is used only if it agrees with the CPU and is faster. A crash during a GPU read turns it off for good.
 */
/** One thread for the process: the embedding model is a native global (see [OmniEngine]'s thread). */
private val embedThread = Executors.newSingleThreadExecutor { r -> Thread(r, "embedder") }.asCoroutineDispatcher()

class Embedder(context: Context) {
  private val thread = embedThread
  private val prefs = context.getSharedPreferences("embed", Context.MODE_PRIVATE)
  // there while a photo is read on the GPU; still there on the next launch after a crash means the GPU did it
  private val gpuMarker = File(context.filesDir, "index/embed-gpu.reading")

  @Volatile var loaded = false
    private set

  @Volatile var seesImages = false
    private set

  /** Where the image tower runs ("GPUOpenCL" or "CPU"), for the logs and Settings. */
  @Volatile var towerDevice: String? = null
    private set

  init {
    if (gpuMarker.exists()) {
      gpuMarker.delete()
      if (CrashReports.lastExitWasCrash(context)) {
        prefs.edit().putString(KEY_GPU, GPU_BAD).apply()
        AppLog.w("embedder: the app crashed while the GPU read a photo; photos are read on the CPU from now on")
      }
    }
  }

  /** Loads the text model; with [mmproj], also the image tower (gallery search). */
  suspend fun load(file: File, mmproj: File? = null, threads: Int = 6): Boolean =
    withContext(thread) {
      if (!loaded) {
        val t0 = System.currentTimeMillis()
        loaded = LlamaNative.nativeEmbedLoad(file.path, "", threads, "")
        AppLog.i("embedder ${if (loaded) "loaded" else "failed to load"} in ${System.currentTimeMillis() - t0} ms")
      }
      if (loaded && mmproj != null && !seesImages) {
        val t0 = System.currentTimeMillis()
        towerDevice = loadTower(file, mmproj, threads)
        seesImages = towerDevice != null
        AppLog.i("embedder image tower ${towerDevice?.let { "on $it" } ?: "failed to load"} in ${System.currentTimeMillis() - t0} ms")
      }
      loaded
    }

  private fun loadTower(file: File, mmproj: File, threads: Int): String? {
    fun on(dev: String) = LlamaNative.nativeEmbedLoad(file.path, mmproj.path, threads, dev)
    when (prefs.getString(KEY_GPU, null)) {
      GPU_GOOD -> if (on(GPU)) return GPU
      GPU_BAD -> {}
      else -> {
        val verdict = benchmark(::on)
        prefs.edit().putString(KEY_GPU, if (verdict) GPU_GOOD else GPU_BAD).apply()
        if (verdict) return GPU
      }
    }
    LlamaNative.nativeEmbedDropTower()
    return if (on("")) "CPU" else null
  }

  /** Once per install: is the GPU tower right (same vector as the CPU's) and faster? Leaves no tower loaded. */
  private fun benchmark(on: (String) -> Boolean): Boolean {
    val (rgb, w, h) = testPicture()
    fun timed(): Pair<FloatArray?, Long> {
      LlamaNative.nativeEmbedImage(rgb, w, h) // the first read compiles GPU kernels and sizes buffers
      val t0 = System.currentTimeMillis()
      return LlamaNative.nativeEmbedImage(rgb, w, h) to System.currentTimeMillis() - t0
    }
    val gpu =
      if (on(GPU)) {
        gpuMarker.writeText("benchmark")
        try { timed() } finally { gpuMarker.delete() }
      } else null to 0L
    LlamaNative.nativeEmbedDropTower()
    val cpu = if (on("")) timed() else null to 0L
    LlamaNative.nativeEmbedDropTower()
    val (g, gMs) = gpu
    val (c, cMs) = cpu
    val agree = g != null && c != null && g.all { it.isFinite() } && cosine(g, c) >= 0.98f
    val faster = gMs in 1 until cMs * 9 / 10
    AppLog.i("embedder: photo read on the GPU in $gMs ms, on the CPU in $cMs ms; vectors ${if (g != null && c != null) "agree %.4f".format(cosine(g, c)) else "missing"} -> ${if (agree && faster) "GPU" else "CPU"}")
    return agree && faster
  }

  suspend fun unload() =
    withContext(thread) {
      if (loaded) LlamaNative.nativeEmbedUnload()
      loaded = false
      seesImages = false
      towerDevice = null
    }

  /** A photo (RGB bytes) in the same space as [query]; null if the image tower isn't loaded or it failed. */
  suspend fun image(rgb: ByteArray, width: Int, height: Int): FloatArray? =
    withContext(thread) {
      if (!seesImages) return@withContext null
      if (towerDevice != GPU) return@withContext LlamaNative.nativeEmbedImage(rgb, width, height)
      gpuMarker.writeText("photo")
      try { LlamaNative.nativeEmbedImage(rgb, width, height) } finally { gpuMarker.delete() }
    }

  /** EmbeddingGemma's prompt for a search query. */
  suspend fun query(text: String): FloatArray? = embed("task: search result | query: $text")

  /** EmbeddingGemma's prompt for a passage to be found. */
  suspend fun passage(text: String, title: String? = null): FloatArray? = embed("title: ${title ?: "none"} | text: $text")

  private suspend fun embed(text: String): FloatArray? = withContext(thread) { if (loaded) LlamaNative.nativeEmbed(text) else null }

  companion object {
    /** EmbeddingGemma 2's full size (3 KB per passage or photo); its Matryoshka cuts measured worse on related text. */
    const val DIMS = 768

    private const val GPU = "GPUOpenCL"
    private const val KEY_GPU = "towerGpu"
    private const val GPU_GOOD = "good"
    private const val GPU_BAD = "bad"

    fun cosine(a: FloatArray, b: FloatArray): Float {
      var s = 0f
      for (i in 0 until minOf(a.size, b.size)) s += a[i] * b[i]
      return s
    }

    /** A 448x336 picture with colour gradients, stripes and blocks: enough structure to exercise every layer. */
    private fun testPicture(): Triple<ByteArray, Int, Int> {
      val w = 448
      val h = 336
      val rgb = ByteArray(w * h * 3)
      for (y in 0 until h) for (x in 0 until w) {
        val i = (y * w + x) * 3
        val block = (x / 56 + y / 56) % 3
        rgb[i] = (x * 255 / w).toByte()
        rgb[i + 1] = (if (block == 0) 220 else (128 + 100 * kotlin.math.sin(x * 0.11 + y * 0.05)).toInt()).toByte()
        rgb[i + 2] = (if ((x / 8) % 2 == 0) y * 255 / h else 40).toByte()
      }
      return Triple(rgb, w, h)
    }
  }
}
