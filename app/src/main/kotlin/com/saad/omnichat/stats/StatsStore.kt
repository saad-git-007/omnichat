package com.saad.omnichat.stats

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class BackendKind(val label: String) {
  NPU("NPU"),
  OPENCL("OpenCL"),
  VULKAN("Vulkan"),
  CPU("CPU");

  companion object {
    fun of(device: String) =
      when {
        device.isEmpty() -> CPU
        device.startsWith("HTP") -> NPU
        device.contains("opencl", true) -> OPENCL
        else -> VULKAN
      }
  }
}

/** One prompt/response run. Times in ms, memory in MB. */
data class RunRecord(
  val timestamp: Long,
  val backend: BackendKind,
  val device: String,
  val model: String,
  val prompt: String,
  val images: Int,
  val audios: Int,
  val promptTokens: Int,
  val prefillMs: Long,
  val genTokens: Int,
  val genMs: Long,
  val ttftMs: Long,
  val totalMs: Long,
  val ramBeforeMb: Int,
  val ramPeakMb: Int,
  val ctxUsed: Int,
  val ctxSize: Int,
  val threads: Int,
  val stopped: Boolean,
  val animated: Boolean = true,
) {
  val genTps get() = if (genMs > 0) genTokens * 1000f / genMs else 0f
  val prefillTps get() = if (prefillMs > 0) promptTokens * 1000f / prefillMs else 0f

  fun toJson(): String =
    JSONObject()
      .put("ts", timestamp)
      .put("backend", backend.name)
      .put("device", device)
      .put("model", model)
      .put("prompt", prompt)
      .put("images", images)
      .put("audios", audios)
      .put("promptTokens", promptTokens)
      .put("prefillMs", prefillMs)
      .put("genTokens", genTokens)
      .put("genMs", genMs)
      .put("ttftMs", ttftMs)
      .put("totalMs", totalMs)
      .put("ramBeforeMb", ramBeforeMb)
      .put("ramPeakMb", ramPeakMb)
      .put("ctxUsed", ctxUsed)
      .put("ctxSize", ctxSize)
      .put("threads", threads)
      .put("stopped", stopped)
      .put("animated", animated)
      .toString()

  companion object {
    fun fromJson(s: String): RunRecord? =
      runCatching {
          val o = JSONObject(s)
          RunRecord(
            timestamp = o.getLong("ts"),
            // before the NPU kind existed, Hexagon runs were filed under Vulkan; the device name tells them apart
            backend = if (o.optString("device").startsWith("HTP")) BackendKind.NPU else BackendKind.valueOf(o.getString("backend")),
            device = o.optString("device"),
            model = o.optString("model"),
            prompt = o.optString("prompt"),
            images = o.optInt("images"),
            audios = o.optInt("audios"),
            promptTokens = o.getInt("promptTokens"),
            prefillMs = o.getLong("prefillMs"),
            genTokens = o.getInt("genTokens"),
            genMs = o.getLong("genMs"),
            ttftMs = o.optLong("ttftMs"),
            totalMs = o.getLong("totalMs"),
            ramBeforeMb = o.optInt("ramBeforeMb"),
            ramPeakMb = o.optInt("ramPeakMb"),
            ctxUsed = o.optInt("ctxUsed"),
            ctxSize = o.optInt("ctxSize"),
            threads = o.optInt("threads"),
            stopped = o.optBoolean("stopped"),
            animated = o.optBoolean("animated", true),
          )
        }
        .getOrNull()
  }
}

/** Run history as JSON lines in app-private storage; survives app restarts, removed on uninstall. */
class StatsStore(context: Context) {
  private val file = File(context.filesDir, "runs.jsonl")
  private val resolver = context.contentResolver

  suspend fun load(): List<RunRecord> =
    withContext(Dispatchers.IO) { if (file.exists()) file.readLines().mapNotNull(RunRecord::fromJson) else emptyList() }

  suspend fun append(r: RunRecord) = withContext(Dispatchers.IO) { file.appendText(r.toJson() + "\n") }

  suspend fun clear() = withContext(Dispatchers.IO) { file.delete() }

  /** Writes a CSV to Downloads and returns its display name. */
  suspend fun exportCsv(runs: List<RunRecord>): String =
    withContext(Dispatchers.IO) {
      val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
      val name = "omnichat-runs-$stamp.csv"
      val values =
        ContentValues().apply {
          put(MediaStore.Downloads.DISPLAY_NAME, name)
          put(MediaStore.Downloads.MIME_TYPE, "text/csv")
          put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
      val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("could not create $name")
      resolver.openOutputStream(uri)!!.bufferedWriter().use { w ->
        w.appendLine(
          "timestamp,backend,device,model,images,audios,prompt_tokens,prefill_ms,prefill_tok_s,gen_tokens,gen_ms,gen_tok_s,ttft_ms,total_ms,ram_before_mb,ram_peak_mb,ctx_used,ctx_size,threads,stopped,ui_animated,prompt"
        )
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        for (r in runs) {
          val prompt = "\"" + r.prompt.replace("\"", "\"\"").replace('\n', ' ') + "\""
          w.appendLine(
            listOf(
                iso.format(Date(r.timestamp)), r.backend.label, r.device, r.model, r.images, r.audios,
                r.promptTokens, r.prefillMs, "%.2f".format(Locale.US, r.prefillTps), r.genTokens, r.genMs,
                "%.2f".format(Locale.US, r.genTps), r.ttftMs, r.totalMs, r.ramBeforeMb, r.ramPeakMb,
                r.ctxUsed, r.ctxSize, r.threads, r.stopped, r.animated, prompt,
              )
              .joinToString(",")
          )
        }
      }
      name
    }
}

/** Resident memory of this process (model weights paged in, KV cache, compute buffers, GPU mappings). */
object ProcMem {
  fun rssMb(): Int =
    runCatching {
        File("/proc/self/status").useLines { lines ->
          lines.first { it.startsWith("VmRSS:") }.filter(Char::isDigit).toLong().div(1024).toInt()
        }
      }
      .getOrDefault(0)
}
