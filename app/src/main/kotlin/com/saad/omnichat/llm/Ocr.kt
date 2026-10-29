package com.saad.omnichat.llm

import android.content.Context
import android.graphics.Bitmap
import com.saad.omnichat.AppLog
import com.saad.omnichat.model.ModelStore
import com.saad.omnichat.model.Models
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * MiniCPM-OCR 1.0: writes down the text in a picture (a scanned PDF page, a photo of a sign or a receipt) so the chat
 * model, which can't read small print itself, gets it as text. Loaded only while reading and freed a minute later.
 *
 * Its image tower is the slow part, so it runs on the Adreno GPU (the NPU's memory is taken by the chat model). If
 * the GPU ever takes the app down mid-read, that is remembered and the CPU is used from then on.
 */
/** One thread for the process: the OCR model is a native global (see [OmniEngine]'s thread). */
private val ocrThread = Executors.newSingleThreadExecutor { r -> Thread(r, "ocr") }.asCoroutineDispatcher()

class Ocr(context: Context, private val store: ModelStore) {
  private val thread = ocrThread
  private val scope = CoroutineScope(SupervisorJob() + thread)
  private val prefs = context.getSharedPreferences("ocr", Context.MODE_PRIVATE)
  private var loaded = false
  private var idle: Job? = null

  /** Where the image tower runs once loaded ("GPUOpenCL" or "CPU"), for the logs. */
  @Volatile var device: String? = null
    private set

  init {
    // still set from last time: the app died during a GPU read (a crash, not Android freeing memory)
    if (prefs.getBoolean(KEY_TRYING, false)) {
      val crashed = com.saad.omnichat.CrashReports.lastExitWasCrash(context)
      prefs.edit().putBoolean(KEY_TRYING, false).putBoolean(KEY_GPU_BROKEN, crashed).commit()
      if (crashed) AppLog.w("OCR: the GPU stopped the app last time; reading on the CPU from now on")
    }
  }

  /** The image tower as used: a Q8_0 copy made once from the bf16 download (which is then deleted). */
  private val tower = java.io.File(store.dir, "mmproj-MiniCPM-OCR-1_0-Q8_0.gguf")

  val ready: Boolean get() = store.isReady(Models.OCR) && (tower.exists() || store.isReady(Models.OCR_VISION))

  /** True once the downloaded image tower has been converted (so it needn't be downloaded again). */
  val converted: Boolean get() = tower.exists()

  /** The text in [bmp], or null when the model isn't downloaded or reading failed. Blank means no text was found. */
  suspend fun read(bmp: Bitmap, prompt: String = "Text Recognition:", onStatus: (String) -> Unit = {}): String? =
    withContext(thread) {
      if (!ready) return@withContext null
      // armed here, so Stop pressed while the model loads still stops the read that follows
      LlamaNative.nativeOcrStop(false)
      idle?.cancel()
      if (!tower.exists()) {
        onStatus("Preparing the OCR model (once, about a minute)")
        val t0 = System.currentTimeMillis()
        val part = java.io.File(tower.path + ".part")
        if (!LlamaNative.nativeMmprojToQ8(store.file(Models.OCR_VISION).path, part.path) || !part.renameTo(tower)) {
          AppLog.e("OCR: converting the image tower failed")
          return@withContext null
        }
        store.file(Models.OCR_VISION).delete()
        AppLog.i("OCR: image tower converted to Q8_0 in ${System.currentTimeMillis() - t0} ms (${tower.length() shr 20} MB)")
      }
      if (!loaded) {
        val gpu = !prefs.getBoolean(KEY_GPU_BROKEN, false)
        val t0 = System.currentTimeMillis()
        device = LlamaNative.nativeOcrLoad(store.file(Models.OCR).path, tower.path, if (gpu) "GPUOpenCL" else "", 4)
          ?: if (gpu) LlamaNative.nativeOcrLoad(store.file(Models.OCR).path, tower.path, "", 4) else null
        loaded = device != null
        AppLog.i("OCR ${if (loaded) "loaded (image tower on $device)" else "failed to load"} in ${System.currentTimeMillis() - t0} ms")
        if (!loaded) return@withContext null
      }
      val (rgb, w, h) = rgb(bmp)
      val onGpu = device != "CPU"
      if (onGpu) prefs.edit().putBoolean(KEY_TRYING, true).commit()
      val text = try {
        LlamaNative.nativeOcrRead(rgb, w, h, prompt)
      } finally {
        if (onGpu) prefs.edit().putBoolean(KEY_TRYING, false).commit()
      }
      idle = scope.launch {
        delay(IDLE_UNLOAD_MS)
        LlamaNative.nativeOcrUnload()
        loaded = false
        AppLog.i("OCR unloaded")
      }
      text?.replace(Regex("<think>\\s*</think>"), "")?.trim()
    }

  fun stop() = LlamaNative.nativeOcrStop(true)

  private fun rgb(src: Bitmap): Triple<ByteArray, Int, Int> {
    // the model slices big pictures itself; past ~2.5k px a side only memory grows
    val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(src.width, src.height))
    val bmp = if (scale < 1f) Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true) else src
    val c = if (bmp.config == Bitmap.Config.ARGB_8888) bmp else bmp.copy(Bitmap.Config.ARGB_8888, false)
    val px = IntArray(c.width * c.height)
    c.getPixels(px, 0, c.width, 0, 0, c.width, c.height)
    val out = ByteArray(px.size * 3)
    for (i in px.indices) {
      val p = px[i]
      out[i * 3] = (p shr 16).toByte()
      out[i * 3 + 1] = (p shr 8).toByte()
      out[i * 3 + 2] = p.toByte()
    }
    return Triple(out, c.width, c.height)
  }

  companion object {
    private const val KEY_TRYING = "gpuReading"
    private const val KEY_GPU_BROKEN = "gpuBroken"
    private const val IDLE_UNLOAD_MS = 60_000L
    private const val MAX_SIDE = 1800
  }
}
