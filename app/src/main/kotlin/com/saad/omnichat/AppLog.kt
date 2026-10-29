package com.saad.omnichat

import android.content.Context
import android.os.Build
import android.util.Log
import com.saad.omnichat.llm.LlamaNative
import java.io.File

/**
 * Detailed log for the in-app Logs page. App events and every llama.cpp/ggml line go to one file per launch,
 * written by native code and flushed per line, so the lines just before a native crash are kept.
 * The previous launch's file is kept as well: after a crash, that is the one worth reading.
 */
object AppLog {
  private const val TAG = "OmniChat"
  @Volatile private var dir: File? = null

  fun current(context: Context) = File(context.filesDir, "logs/session.log")

  fun previous(context: Context) = File(context.filesDir, "logs/session-prev.log")

  fun start(context: Context) {
    if (dir != null) return
    val cur = current(context)
    cur.parentFile!!.mkdirs()
    val prev = previous(context)
    if (cur.exists()) {
      prev.delete()
      cur.renameTo(prev)
    }
    LlamaNative.nativeOpenLog(cur.path)
    dir = cur.parentFile
    val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
    i("OmniChat $version on ${Build.MANUFACTURER} ${Build.MODEL} (${Build.SOC_MODEL}), Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")

    val fallback = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { t, e ->
      e("Uncaught exception on ${t.name}", e)
      fallback?.uncaughtException(t, e)
    }
  }

  fun i(msg: String) = write(Log.INFO, msg)

  fun w(msg: String) = write(Log.WARN, msg)

  fun e(msg: String, t: Throwable? = null) = write(Log.ERROR, if (t == null) msg else "$msg\n${Log.getStackTraceString(t)}")

  private fun write(prio: Int, msg: String) {
    Log.println(prio, TAG, msg)
    if (dir != null) LlamaNative.nativeLog(prio, "[app] $msg")
  }
}
