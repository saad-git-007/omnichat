package com.saad.omnichat.tools

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.telephony.SmsManager
import com.saad.omnichat.AppLog
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** One person a batch of texts goes to; [status] is null until sent ("Sent" or why it failed). */
data class TextTo(val name: String, val number: String, val status: String? = null, val ok: Boolean = false)

/**
 * The same text to several people, shown in the chat as one card. Nothing is sent until the user taps Send there:
 * the model only prepares it.
 */
data class TextBatch(val id: Long, val message: String, val to: List<TextTo>, val state: State = State.WAITING) {
  enum class State { WAITING, SENDING, DONE, CANCELLED }
}

/** Sends SMS straight from OmniChat (needs SEND_SMS), so a text to four people is one tap, not four app switches. */
object TextSender {
  private const val ACTION = "com.saad.omnichat.SMS_SENT"

  /** Sends [message] to [number]; returns null when the network took it, else why not. */
  suspend fun send(context: Context, number: String, message: String): String? {
    val sms = context.getSystemService(SmsManager::class.java) ?: return "this phone can't send texts"
    val parts = sms.divideMessage(message)
    val tag = System.nanoTime()
    val sent = PendingIntent.getBroadcast(context, tag.toInt(), Intent(ACTION).setPackage(context.packageName).putExtra("tag", tag), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
    return withTimeoutOrNull(60_000) {
      suspendCancellableCoroutine { cont ->
        val receiver = object : BroadcastReceiver() {
          override fun onReceive(c: Context, i: Intent) {
            if (i.getLongExtra("tag", 0) != tag) return
            runCatching { context.unregisterReceiver(this) }
            if (cont.isActive) cont.resume(if (resultCode == Activity.RESULT_OK) null else "not sent (error $resultCode; check signal and the SIM)")
          }
        }
        context.registerReceiver(receiver, IntentFilter(ACTION), Context.RECEIVER_NOT_EXPORTED)
        cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
        try {
          // the result comes with the last part
          val intents = ArrayList<PendingIntent?>(parts.map { null }).apply { this[size - 1] = sent }
          sms.sendMultipartTextMessage(number, null, parts, intents, null)
        } catch (e: Exception) {
          AppLog.e("sending a text failed", e)
          runCatching { context.unregisterReceiver(receiver) }
          if (cont.isActive) cont.resume(e.message ?: "not sent")
        }
      }
    } ?: "no answer from the network after a minute; it may still go out"
  }
}
