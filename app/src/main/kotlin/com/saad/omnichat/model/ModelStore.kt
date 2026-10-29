package com.saad.omnichat.model

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class ModelFile(val name: String, val size: Long, val sha256: String, val repo: String = "LiquidAI/LFM3-Omni-2B-GGUF") {
  val url get() = "https://huggingface.co/$repo/resolve/main/$name"
}

object Models {
  val MODEL = ModelFile("LFM3-Omni-2B-Q4_0.gguf", 1_049_617_920, "6d2273a383b92dd7c5db0eb4c8dfeae13e805b485a6621c4b391ae179c2b5f28")
  val MMPROJ = ModelFile("mmproj-LFM3-Omni-2B-Q8_0.gguf", 540_892_768, "720ff4fc09df0e026c4ec641d0c698bf27b5bcd50561944c88eda5e3dffe1839")
  val ALL = listOf(MODEL, MMPROJ)

  /** Google's EmbeddingGemma 2 (Apache 2.0): meaning search over chats and long documents. Optional. */
  val EMBED = ModelFile("embeddinggemma-2-Q8_0.gguf", 309_855_456, "2188ac1deca4b77dffefd603c2776a9d76d9d74ec01841392982ebb840b09135", "ggml-org/embeddinggemma-2-GGUF")

  /** Its image tower, in the same space as text: gallery search. Optional. */
  val EMBED_VISION = ModelFile("mmproj-embeddinggemma-2-Q8_0.gguf", 554_821_024, "c4a8a52691ecef40618438928bdf9e68379b854e24166f292592353db0aab64f", "ggml-org/embeddinggemma-2-GGUF")

  /** OpenBMB's MiniCPM-OCR 1.0 (Apache 2.0): reads the text in scanned PDF pages and photos. Optional. */
  val OCR = ModelFile("MiniCPM-OCR-1_0-Q4_K_M.gguf", 529_106_816, "c80a703400633f298cc2fddf6d493a541be36918c0801c0b1bf3f14c5703ce49", "openbmb/MiniCPM-OCR-1.0-gguf")

  /** Its image tower. */
  val OCR_VISION = ModelFile("mmproj-model-f16-4x.gguf", 901_017_536, "ceed82261b3b1380c3165f8b37c10117a50093be1362cbfeeb692fa036444682", "openbmb/MiniCPM-OCR-1.0-gguf")
}

/**
 * Model files live in /sdcard/Android/data/com.saad.omnichat/files/models, so they can also be pushed with adb.
 * A file is ready once it has the expected size; the SHA-256 is checked right after a download.
 */
class ModelStore(context: Context) {
  val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "models").apply { mkdirs() }

  fun file(m: ModelFile) = File(dir, m.name)

  fun isReady(m: ModelFile) = file(m).length() == m.size

  fun allReady() = Models.ALL.all(::isReady)

  /** Downloads the missing files, resuming partial downloads. [onProgress] gets (bytes done, bytes total). */
  suspend fun downloadAll(onProgress: (Long, Long) -> Unit) =
    withContext(Dispatchers.IO) {
      val total = Models.ALL.sumOf { it.size }
      var before = 0L
      for (m in Models.ALL) {
        if (!isReady(m)) download(m) { done -> onProgress(before + done, total) }
        before += m.size
        onProgress(before, total)
      }
    }

  /** One file, resuming a partial download; [onProgress] gets the bytes done. */
  suspend fun downloadOne(m: ModelFile, onProgress: (Long) -> Unit) = withContext(Dispatchers.IO) { if (!isReady(m)) download(m, onProgress) }

  private suspend fun download(m: ModelFile, onProgress: (Long) -> Unit) {
    val part = File(dir, m.name + ".part")
    var have = part.length()
    if (have > m.size) {
      part.delete()
      have = 0
    }
    if (have < m.size) {
      val conn = URL(m.url).openConnection() as HttpURLConnection
      conn.instanceFollowRedirects = true
      conn.connectTimeout = 20_000
      conn.readTimeout = 60_000
      if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
      conn.connect()
      when (conn.responseCode) {
        HttpURLConnection.HTTP_PARTIAL -> {}
        HttpURLConnection.HTTP_OK -> have = 0 // server ignored the range, start over
        else -> throw IllegalStateException("HTTP ${conn.responseCode} for ${m.name}")
      }
      conn.inputStream.use { input ->
        FileOutputStream(part, have > 0).use { out ->
          val buf = ByteArray(1 shl 20)
          var lastReport = 0L
          while (true) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            have += n
            if (have - lastReport > (8 shl 20)) {
              onProgress(have)
              lastReport = have
            }
          }
        }
      }
      conn.disconnect()
    }
    if (part.length() != m.size) throw IllegalStateException("${m.name}: incomplete download (${part.length()} of ${m.size} bytes)")
    if (sha256(part) != m.sha256) {
      part.delete()
      throw IllegalStateException("${m.name}: checksum mismatch, file deleted. Try again.")
    }
    if (!part.renameTo(file(m))) throw IllegalStateException("${m.name}: could not move file into place")
  }

  private suspend fun sha256(f: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    f.inputStream().use { input ->
      val buf = ByteArray(1 shl 20)
      while (true) {
        currentCoroutineContext().ensureActive()
        val n = input.read(buf)
        if (n < 0) break
        md.update(buf, 0, n)
      }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
  }
}
