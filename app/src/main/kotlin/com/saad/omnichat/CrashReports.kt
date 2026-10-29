package com.saad.omnichat

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A native crash (GPU driver, ggml) kills the process before any handler runs. On the next launch Android's
 * record of it is turned into readable text and kept for the Logs page.
 */
object CrashReports {
  fun file(context: Context) = File(context.filesDir, "logs/last-crash.txt")

  /**
   * Whether the previous run ended in a crash, not in Android closing it to free memory (which a big app in the
   * background often is). Guards that blame a GPU or a file for "the app died" use this so a normal kill doesn't count.
   */
  fun lastExitWasCrash(context: Context): Boolean =
    runCatching {
        val exit = context.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull()
        exit?.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || exit?.reason == ApplicationExitInfo.REASON_CRASH
      }
      .getOrDefault(true)

  /** Saves the newest unseen crash; returns true when there was one. */
  fun saveLastCrash(context: Context): Boolean {
    val am = context.getSystemService(ActivityManager::class.java)
    val exit = am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() ?: return false
    if (exit.reason != ApplicationExitInfo.REASON_CRASH_NATIVE && exit.reason != ApplicationExitInfo.REASON_CRASH) return false
    val prefs = context.getSharedPreferences("crash", Context.MODE_PRIVATE)
    if (exit.timestamp <= prefs.getLong("seen", 0L)) return false
    prefs.edit().putLong("seen", exit.timestamp).apply()

    val native = exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE
    val trace = runCatching { exit.traceInputStream?.use { it.readBytes() } }.getOrNull()
    val body =
      when {
        trace == null -> "No trace was recorded."
        native -> runCatching { Tombstone.format(trace) }.getOrElse { "Could not decode the native trace: $it" }
        else -> String(trace)
      }
    val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(exit.timestamp))
    val text = buildString {
      appendLine("${if (native) "Native" else "Java"} crash at $time")
      exit.description?.let { appendLine("Description: $it") }
      appendLine()
      append(body)
    }
    return runCatching {
        file(context).apply { parentFile!!.mkdirs() }.writeText(text)
        true
      }
      .getOrDefault(false)
  }
}

/** Minimal reader for AOSP's tombstone.proto (system/core/debuggerd/proto), enough for a readable report. */
private object Tombstone {
  private class Reader(val buf: ByteArray, var pos: Int = 0, val end: Int = buf.size) {
    fun varint(): Long {
      var r = 0L
      var shift = 0
      while (true) {
        val b = buf[pos++].toInt() and 0xFF
        r = r or ((b and 0x7F).toLong() shl shift)
        if (b < 0x80) return r
        shift += 7
      }
    }

    /** Yields (field, wireType, value) where value is Long for varint/fixed and Reader for length-delimited. */
    fun fields(): Sequence<Triple<Int, Int, Any>> = sequence {
      while (pos < end) {
        val key = varint()
        val field = (key ushr 3).toInt()
        when (val wt = (key and 7).toInt()) {
          0 -> yield(Triple(field, wt, varint()))
          1 -> { pos += 8; yield(Triple(field, wt, 0L)) }
          2 -> {
            val len = varint().toInt()
            val sub = Reader(buf, pos, pos + len)
            pos += len
            yield(Triple(field, wt, sub))
          }
          5 -> { pos += 4; yield(Triple(field, wt, 0L)) }
          else -> return@sequence
        }
      }
    }

    fun string() = String(buf, pos, end - pos)
  }

  private class Frame(var relPc: Long = 0, var fn: String = "", var off: Long = 0, var file: String = "")

  private class Thread(var id: Int = 0, var name: String = "", val frames: MutableList<Frame> = mutableListOf())

  fun format(bytes: ByteArray): String {
    var tid = 0
    var abort = ""
    var signal = ""
    var cmd = ""
    val causes = mutableListOf<String>()
    val threads = mutableListOf<Thread>()
    val logs = mutableListOf<String>()

    for ((f, _, v) in Reader(bytes).fields()) {
      when (f) {
        6 -> tid = (v as Long).toInt()
        9 -> cmd += (v as Reader).string() + " "
        10 -> signal = signal(v as Reader)
        14 -> abort = (v as Reader).string()
        15 -> (v as Reader).fields().firstOrNull { it.first == 1 }?.let { causes += (it.third as Reader).string() }
        16 -> (v as Reader).fields().firstOrNull { it.first == 2 }?.let { threads += thread(it.third as Reader) }
        18 -> logs += logBuffer(v as Reader)
      }
    }

    val crashed = threads.firstOrNull { it.id == tid }
    return buildString {
      appendLine("Process: ${cmd.trim()}")
      appendLine("Signal: $signal")
      if (abort.isNotEmpty()) appendLine("Abort message: $abort")
      causes.forEach { appendLine("Cause: $it") }
      appendLine()
      if (crashed != null) {
        appendLine("Crashed thread ${crashed.id} \"${crashed.name}\":")
        appendFrames(crashed)
      }
      for (t in threads) {
        if (t === crashed || t.frames.none { "omni" in it.file || "ggml" in it.file || "llama" in it.file || "mtmd" in it.file }) continue
        appendLine()
        appendLine("Thread ${t.id} \"${t.name}\":")
        appendFrames(t)
      }
      if (logs.isNotEmpty()) {
        appendLine()
        appendLine("Last log lines:")
        logs.takeLast(80).forEach { appendLine(it) }
      }
    }
  }

  private fun StringBuilder.appendFrames(t: Thread) {
    t.frames.take(48).forEachIndexed { i, fr ->
      val fn = if (fr.fn.isNotEmpty()) " (${fr.fn}+${fr.off})" else ""
      appendLine("  #%02d pc %08x  %s%s".format(i, fr.relPc, fr.file, fn))
    }
  }

  private fun signal(r: Reader): String {
    var number = 0L
    var name = ""
    var codeName = ""
    var fault: Long? = null
    for ((f, _, v) in r.fields()) {
      when (f) {
        1 -> number = v as Long
        2 -> name = (v as Reader).string()
        4 -> codeName = (v as Reader).string()
        9 -> fault = v as Long
      }
    }
    return "$number ($name), code $codeName" + (fault?.let { ", fault addr 0x%x".format(it) } ?: "")
  }

  private fun thread(r: Reader): Thread {
    val t = Thread()
    for ((f, _, v) in r.fields()) {
      when (f) {
        1 -> t.id = (v as Long).toInt()
        2 -> t.name = (v as Reader).string()
        4 -> {
          val fr = Frame()
          for ((ff, _, vv) in (v as Reader).fields()) {
            when (ff) {
              1 -> fr.relPc = vv as Long
              4 -> fr.fn = (vv as Reader).string()
              5 -> fr.off = vv as Long
              6 -> fr.file = (vv as Reader).string()
            }
          }
          t.frames += fr
        }
      }
    }
    return t
  }

  private fun logBuffer(r: Reader): List<String> {
    val out = mutableListOf<String>()
    for ((f, _, v) in r.fields()) {
      if (f != 2) continue
      var ts = ""
      var tag = ""
      var msg = ""
      var prio = 0L
      for ((ff, _, vv) in (v as Reader).fields()) {
        when (ff) {
          1 -> ts = (vv as Reader).string()
          4 -> prio = vv as Long
          5 -> tag = (vv as Reader).string()
          6 -> msg = (vv as Reader).string()
        }
      }
      val lvl = "VVVDIWEF".getOrElse(prio.toInt()) { '?' }
      out += "$ts $lvl $tag: ${msg.trimEnd()}"
    }
    return out
  }
}
