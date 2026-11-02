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
 * Xiaomi-OCR-0: writes down the text in a picture (a scanned PDF page, a photo of a sign or a receipt) so the chat
 * model, which can't read small print itself, gets it as text, with tables as Markdown tables. Loaded only while
 * reading and freed a minute later.
 *
 * Its image tower runs on the CPU. On the Adreno GPU, OpenCL has no flash attention, so a page's attention needs one
 * ~3 GB block (8k image patches squared, 12 heads) where the driver allows 1 GB: the allocation fails and llama.cpp
 * then crashes on the missing buffer. With flash attention on the CPU the same page needs a few MB for it.
 */
/** One thread for the process: the OCR model is a native global (see [OmniEngine]'s thread). */
private val ocrThread = Executors.newSingleThreadExecutor { r -> Thread(r, "ocr") }.asCoroutineDispatcher()

class Ocr(context: Context, private val store: ModelStore) {
  private val thread = ocrThread
  private val scope = CoroutineScope(SupervisorJob() + thread)
  private var loaded = false
  private var idle: Job? = null

  /** Where the image tower runs once loaded, for the logs. */
  @Volatile var device: String? = null
    private set

  init {
    // the model used before (1.15 GB)
    Models.OLD_OCR.map { java.io.File(store.dir, it) }.filter { it.exists() }.forEach {
      AppLog.i("OCR: deleting ${it.name} (replaced by Xiaomi-OCR-0)")
      it.delete()
    }
  }

  private val tower get() = store.file(Models.OCR_VISION)

  val ready: Boolean get() = store.isReady(Models.OCR) && store.isReady(Models.OCR_VISION)

  /** The text in [bmp], or null when the model isn't downloaded or reading failed. Blank means no text was found. */
  suspend fun read(bmp: Bitmap, onStatus: (String) -> Unit = {}): String? =
    withContext(thread) {
      if (!ready) return@withContext null
      // armed here, so Stop pressed while the model loads still stops the read that follows
      LlamaNative.nativeOcrStop(false)
      idle?.cancel()
      if (!loaded) {
        onStatus("Loading the OCR model")
        val t0 = System.currentTimeMillis()
        device = LlamaNative.nativeOcrLoad(store.file(Models.OCR).path, tower.path, "", 4)
        loaded = device != null
        AppLog.i("OCR ${if (loaded) "loaded (image tower on $device)" else "failed to load"} in ${System.currentTimeMillis() - t0} ms")
        if (!loaded) return@withContext null
      }
      val (rgb, w, h) = rgb(bmp)
      val text = LlamaNative.nativeOcrRead(rgb, w, h, PROMPT)
      idle = scope.launch {
        delay(IDLE_UNLOAD_MS)
        LlamaNative.nativeOcrUnload()
        loaded = false
        AppLog.i("OCR unloaded")
      }
      text?.let(OcrText::tidy)
    }

  fun stop() = LlamaNative.nativeOcrStop(true)

  private fun rgb(src: Bitmap): Triple<ByteArray, Int, Int> {
    // each 32 px square is one token to read, and reading time grows faster than the token count; at 1.2M pixels
    // (~1,200 tokens) a page of 8-pt print still reads with 0.3% wrong characters, at half the time of 2.1M
    val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(src.width, src.height), kotlin.math.sqrt(MAX_PIXELS / (src.width.toFloat() * src.height)))
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
    private const val IDLE_UNLOAD_MS = 60_000L
    private const val MAX_SIDE = 1800
    private const val MAX_PIXELS = 1_200_000f

    /**
     * The model's own whole-page prompt: Markdown in reading order, tables in OTSL (made Markdown by [OcrText]). It
     * keeps empty table cells empty; the plain-text prompt lost which column a value was in.
     */
    private const val PROMPT =
      "Extract all information from the main body of the document image and represent it in markdown format, ignoring " +
        "headers and footers. Tables should be expressed in OTSL format, formulas in the document should be represented " +
        "using LATEX format, and the parsing should be organized according to the reading order."
  }
}
