package com.saad.omnichat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager

/**
 * Keeps the process alive and the CPU awake while the model reads or writes, so a reply (or a long transcription)
 * finishes with the app in the background or the screen off. The work itself stays in the view model; this
 * service only holds the foreground notification and a partial wake lock.
 */
class GenerationService : Service() {
  private var wakeLock: PowerManager.WakeLock? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Working…"
    startForeground(ID_PROGRESS, notification(this, text, ongoing = true), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    if (wakeLock == null) {
      wakeLock = getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "omnichat:generation")
        .apply { acquire(4 * 60 * 60 * 1000L) } // a 2-hour recording takes well under this to transcribe
    }
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    wakeLock?.takeIf { it.isHeld }?.release()
    wakeLock = null
    super.onDestroy()
  }

  companion object {
    private const val CHANNEL = "generation"
    private const val CHANNEL_DONE = "done"
    private const val ID_PROGRESS = 1
    private const val ID_DONE = 2
    private const val EXTRA_TEXT = "text"
    private var running = false

    /**
     * Activities of the app on screen (MainActivity counts itself in onStart/onStop). A count, not a flag: when an
     * activity is replaced, the new one starts before the old one stops, and a flag would end up false while visible.
     */
    private val started = java.util.concurrent.atomic.AtomicInteger()

    fun activityStarted() = started.incrementAndGet()

    fun activityStopped() = started.updateAndGet { maxOf(0, it - 1) }

    val appVisible: Boolean get() = started.get() > 0

    fun start(context: Context, text: String) {
      channels(context)
      context.getSystemService(NotificationManager::class.java).cancel(ID_DONE)
      context.startForegroundService(Intent(context, GenerationService::class.java).putExtra(EXTRA_TEXT, text))
      running = true
    }

    /** Updates the ongoing notification's text (cheap; call at most every second or two). */
    fun progress(context: Context, text: String) {
      if (!running) return
      context.getSystemService(NotificationManager::class.java).notify(ID_PROGRESS, notification(context, text, ongoing = true))
    }

    /** Stops the service; [doneText] is shown as a "ready" notification when the app isn't on screen. */
    fun stop(context: Context, doneText: String?) {
      if (!running) return
      running = false
      context.stopService(Intent(context, GenerationService::class.java))
      if (doneText != null && !appVisible) {
        context.getSystemService(NotificationManager::class.java).notify(ID_DONE, notification(context, doneText, ongoing = false))
      }
    }

    internal fun channels(context: Context) {
      val nm = context.getSystemService(NotificationManager::class.java)
      nm.createNotificationChannel(NotificationChannel(CHANNEL, "Generating", NotificationManager.IMPORTANCE_LOW).apply {
        description = "Shown while the model reads or writes in the background"
      })
      nm.createNotificationChannel(NotificationChannel(CHANNEL_DONE, "Replies ready", NotificationManager.IMPORTANCE_DEFAULT).apply {
        description = "A reply or transcription finished while the app was in the background"
      })
    }

    internal fun notification(context: Context, text: String, ongoing: Boolean, title: String? = null): Notification {
      val open = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE,
      )
      return Notification.Builder(context, if (ongoing) CHANNEL else CHANNEL_DONE)
        .setSmallIcon(R.drawable.ic_stat_orb)
        .setContentTitle(title ?: if (ongoing) "LFM3 Omni is working" else "LFM3 Omni")
        .setContentText(text)
        .setContentIntent(open)
        .setOngoing(ongoing)
        .setOnlyAlertOnce(true)
        .setAutoCancel(!ongoing)
        .build()
    }
  }
}

/**
 * Keeps live transcribe going with the app in the background or the screen off: a microphone foreground service
 * (Android only lets a foreground service keep the mic) plus a partial wake lock. The work stays in the view model.
 */
class LiveTranscribeService : Service() {
  private var wakeLock: PowerManager.WakeLock? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    startForeground(ID, GenerationService.notification(this, intent?.getStringExtra("text") ?: "Listening", ongoing = true, title = "Live transcribe"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    if (wakeLock == null) {
      wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "omnichat:live").apply { acquire(8 * 60 * 60 * 1000L) }
    }
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    wakeLock?.takeIf { it.isHeld }?.release()
    wakeLock = null
    super.onDestroy()
  }

  companion object {
    private const val ID = 3

    fun start(context: Context) {
      GenerationService.channels(context)
      context.startForegroundService(Intent(context, LiveTranscribeService::class.java))
    }

    fun progress(context: Context, text: String) =
      context.getSystemService(NotificationManager::class.java).notify(ID, GenerationService.notification(context, text, ongoing = true, title = "Live transcribe"))

    fun stop(context: Context) = context.stopService(Intent(context, LiveTranscribeService::class.java))
  }
}
