package com.saad.omnichat.tools

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import com.saad.omnichat.AppLog
import com.saad.omnichat.GenerationService
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Things the model can do on the phone. Actions only open the right app, filled in (Clock, Calendar, Messages,
 * Phone, Maps, browser): you confirm or send there, so nothing happens behind your back. Reading contacts or the
 * calendar needs Android's permission, granted in Settings.
 */
class PhoneTools(private val context: Context) {
  private fun granted(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

  fun specs(): List<ToolSpec> = buildList {
    add(ToolSpec("device_status", "Current time, time zone, battery level and charging, and network type. Only when the user asks about one of these.", ToolCalls.params()))
    add(ToolSpec("calculate", "Evaluate an arithmetic expression exactly when it is too long to do in your head, e.g. (12.5*4)/3 + 2^8 or sqrt(2). Write percentages as division: 17.5% of 2340 is 17.5/100*2340.", ToolCalls.params("expression" to "The expression", required = listOf("expression"))))
    add(ToolSpec("set_alarm", "Open the Clock app with an alarm ready to save. Only when the user asks for an alarm or to be woken.", ToolCalls.params("time" to "24-hour time, HH:MM", "label" to "Optional label", required = listOf("time"))))
    add(ToolSpec("set_timer", "Start a countdown timer in the Clock app (also for \"remind me in N minutes\"). Only when the user asks for a timer or a reminder after a delay.", ToolCalls.params("seconds" to "Length in seconds", "label" to "Optional label", required = listOf("seconds"), types = mapOf("seconds" to "integer"))))
    add(ToolSpec("add_calendar_event", "Open the Calendar app with a new event filled in, for the user to save. Only when the user asks to add an event or be reminded.", ToolCalls.params("title" to "Event title", "start" to "Start, YYYY-MM-DD HH:MM", "end" to "Optional end, YYYY-MM-DD HH:MM", "location" to "Optional place", required = listOf("title", "start"))))
    add(ToolSpec("draft_text_message", "Only when the user asks to text someone: write a text for the user to send, one draft per recipient (Messages opens for each in turn). Put ALL recipients in one call, comma-separated.", ToolCalls.params("to" to "Phone numbers or contact names, comma-separated, e.g. \"Sara, Ali, +1 555 0100\"", "message" to "The text", required = listOf("to", "message"))))
    add(ToolSpec("draft_email", "Only when the user asks to email someone: open the email app with an email written for the user to send; several recipients go on the same email.", ToolCalls.params("to" to "Email addresses or contact names, comma-separated", "subject" to "Subject", "body" to "Body", required = listOf("body"))))
    add(ToolSpec("dial", "Only when the user asks to call someone: open the Phone app with the number ready (the user presses call).", ToolCalls.params("to" to "Phone number or contact name", required = listOf("to"))))
    add(ToolSpec("open_map", "Show a place in Maps, or start directions to it. Only when the user asks for a map or directions.", ToolCalls.params("place" to "Address or place name", "directions" to "true to navigate there", required = listOf("place"), types = mapOf("directions" to "boolean"))))
    add(ToolSpec("open_website", "Open a web page in the browser. Only when the user asks to open a site; not to read it yourself.", ToolCalls.params("url" to "The address", required = listOf("url"))))
    add(ToolSpec("read_clipboard", "Read the text the user last copied. Only when the user refers to what they copied.", ToolCalls.params()))
    if (granted(Manifest.permission.READ_CONTACTS))
      add(ToolSpec("find_contact", "Look up a contact's phone numbers and email addresses by name.", ToolCalls.params("name" to "Name or part of it", required = listOf("name"))))
    if (granted(Manifest.permission.READ_CALENDAR))
      add(ToolSpec("read_calendar", "List the user's calendar events for the next days.", ToolCalls.params("days" to "How many days ahead (1 = today)", types = mapOf("days" to "integer"))))
  }

  fun handles(name: String) = name in NAMES

  /** Texts the last [run] prepared for the chat's Send card (with SEND_SMS allowed); taken by the caller. */
  var prepared: TextBatch? = null

  /** Runs a call; the returned text goes back to the model as the tool's response. */
  fun run(call: ToolCall): String =
    runCatching {
        val a = call.args
        when (call.name) {
          "device_status" -> status()
          "calculate" -> Calculator.eval(a["expression"].orEmpty()).let { "${a["expression"]} = ${Calculator.format(it)}" }
          "set_alarm" -> {
            val (h, m) = parseTime(a["time"].orEmpty())
            open(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, m).apply { a["label"]?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) } }, "Clock, with an alarm for %02d:%02d".format(h, m))
          }
          "set_timer" -> {
            val secs = a["seconds"]?.toDoubleOrNull()?.toInt()?.takeIf { it > 0 } ?: error("seconds must be a positive number")
            open(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, secs).putExtra(AlarmClock.EXTRA_SKIP_UI, false).apply { a["label"]?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) } }, "Clock, with a $secs-second timer")
          }
          "add_calendar_event" -> {
            val start = parseDateTime(a["start"].orEmpty())
            val end = a["end"]?.takeIf { it.isNotBlank() }?.let(::parseDateTime) ?: (start + 3_600_000)
            open(
              Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, a["title"])
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
                .apply { a["location"]?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) } },
              "Calendar, with \"${a["title"]}\" on ${SimpleDateFormat("EEE d MMM yyyy HH:mm", Locale.US).format(Date(start))}",
            ).notDone("saved", "save it in Calendar")
          }
          "draft_text_message" -> {
            // Android shows one compose screen at a time: one draft per recipient, opened in turn
            val names = recipients(a["to"].orEmpty())
            if (names.isEmpty()) return open(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")).putExtra("sms_body", a["message"]), "Messages, with the text ready (no recipient yet)")
            val found = ArrayList<Pair<String, String>>()
            val missing = ArrayList<String>()
            for (n in names) runCatching { resolve(n, phone = true) }.onSuccess { found += n to it }.onFailure { missing += "$n (${it.message})" }
            if (found.isNotEmpty() && granted(Manifest.permission.SEND_SMS)) {
              // one card in the chat with a single Send button, instead of a compose screen per person
              prepared = TextBatch(System.nanoTime(), a["message"].orEmpty(), found.map { (n, number) -> TextTo(n, number) })
              return buildString {
                append("Prepared the text to ${found.joinToString { "${it.first} (${it.second})" }}. It is shown in the chat with a Send button: nothing is sent until the user taps it, so say it is ready to send, not that it was sent. ")
                if (missing.isNotEmpty()) append("Could not add: ${missing.joinToString("; ")}. Tell the user.")
              }.trim()
            }
            val opened = found.map { (n, number) -> open(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number))).putExtra("sms_body", a["message"]), "Messages with the text to $n ($number)") }
            buildString {
              if (found.isNotEmpty()) append("Prepared ${found.size} separate draft(s), one per person: ${found.joinToString { it.first }}. ")
              opened.forEach { append(it).append(' ') }
              if (found.size > 1) append("Only the first is on screen now; each next draft opens when the user returns to OmniChat. ")
              if (opened.any { it.startsWith("Opened") || it.startsWith("Queued") }) append("".notDone("sent", "tap send in each draft").trim()).append(' ')
              if (missing.isNotEmpty()) append("Could not prepare: ${missing.joinToString("; ")}. Tell the user.")
            }.trim()
          }
          "draft_email" -> {
            val found = ArrayList<String>()
            val missing = ArrayList<String>()
            for (n in recipients(a["to"].orEmpty())) runCatching { resolve(n, phone = false) }.onSuccess { found += it }.onFailure { missing += "$n (${it.message})" }
            open(
              Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).putExtra(Intent.EXTRA_EMAIL, found.toTypedArray()).putExtra(Intent.EXTRA_SUBJECT, a["subject"]).putExtra(Intent.EXTRA_TEXT, a["body"]),
              "the email app, with the email ready to ${found.joinToString().ifEmpty { "(no recipient yet)" }}",
            ).notDone("sent", "send it from the email app") + if (missing.isNotEmpty()) " Could not add: ${missing.joinToString("; ")}." else ""
          }
          "dial" -> {
            val to = resolve(a["to"].orEmpty(), phone = true)
            open(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(to))), "the Phone app with $to ready").notDone("called", "press call")
          }
          "open_map" -> {
            val place = a["place"].orEmpty()
            val nav = a["directions"]?.lowercase() == "true"
            open(Intent(Intent.ACTION_VIEW, Uri.parse(if (nav) "google.navigation:q=" + Uri.encode(place) else "geo:0,0?q=" + Uri.encode(place))), "Maps ${if (nav) "with directions to" else "showing"} $place")
          }
          "open_website" -> {
            val url = a["url"].orEmpty().let { if (it.startsWith("http")) it else "https://$it" }
            open(Intent(Intent.ACTION_VIEW, Uri.parse(url)), "the browser at $url")
          }
          "read_clipboard" -> {
            val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
            clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.take(4000)?.let { "Clipboard: $it" } ?: "The clipboard is empty."
          }
          "find_contact" -> findContacts(a["name"].orEmpty())
          "read_calendar" -> readCalendar(a["days"]?.toDoubleOrNull()?.toInt()?.coerceIn(1, 31) ?: 7)
          else -> "Unknown tool ${call.name}."
        }
      }
      .getOrElse {
        AppLog.w("tool ${call.name} failed: ${it.message}")
        "${call.name} failed: ${it.message}"
      }

  /** "Sara, Ali and Bob" -> [Sara, Ali, Bob] */
  private fun recipients(to: String): List<String> =
    to.split(Regex("""\s*(?:[,;\n]|\band\b|&)\s*""")).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

  /**
   * Opens [intent] now, or, when another app was already opened for this request, queues it to open when the user
   * comes back to OmniChat (Android shows one app at a time, so opening several at once would hide all but one).
   */
  private fun open(intent: Intent, what: String): String {
    if (!GenerationService.appVisible) return "Could not open $what: OmniChat is in the background, and Android only lets the app open other apps while it is on screen. Tell the user to ask again when the app is open."
    return when (val position = PhoneQueue.open(context, intent, what)) {
      0 -> "Opened $what."
      -1 -> "No app on this phone can open $what."
      else -> "Queued $what (#${position + 1}); it opens when the user comes back to OmniChat."
    }
  }

  private fun status(): String {
    val now = Date()
    val bm = context.getSystemService(BatteryManager::class.java)
    val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    val charging = bm.isCharging
    val net = runCatching {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        when {
          caps == null -> "offline"
          caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
          caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
          else -> "online"
        }
      }.getOrDefault("unknown")
    return "Now: ${SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.US).format(now)} (${java.util.TimeZone.getDefault().id}). Battery $level%${if (charging) ", charging" else ""}. Network: $net."
  }

  /** A contact name becomes their first number or email; numbers and addresses pass through. */
  private fun resolve(to: String, phone: Boolean): String {
    if (phone && to.any(Char::isDigit) && to.none(Char::isLetter)) return to
    if (!phone && '@' in to) return to
    if (!granted(Manifest.permission.READ_CONTACTS)) error("'$to' is a name, and OmniChat may not read contacts (allow it in Settings > Phone tools), so ask the user for the ${if (phone) "number" else "address"}")
    val uri = if (phone) ContactsContract.CommonDataKinds.Phone.CONTENT_URI else ContactsContract.CommonDataKinds.Email.CONTENT_URI
    val col = if (phone) ContactsContract.CommonDataKinds.Phone.NUMBER else ContactsContract.CommonDataKinds.Email.ADDRESS
    context.contentResolver.query(uri, arrayOf(col), "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?", arrayOf("%$to%"), null)?.use { c ->
      if (c.moveToFirst()) return c.getString(0)
    }
    error("no contact matches '$to'")
  }

  private fun findContacts(name: String): String {
    val out = LinkedHashMap<String, MutableList<String>>()
    val q = arrayOf("%$name%")
    val sel = "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?"
    context.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, arrayOf(ContactsContract.Contacts.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), sel, q, null)?.use { c ->
      while (c.moveToNext() && out.size < 10) out.getOrPut(c.getString(0)) { mutableListOf() } += c.getString(1)
    }
    context.contentResolver.query(ContactsContract.CommonDataKinds.Email.CONTENT_URI, arrayOf(ContactsContract.Contacts.DISPLAY_NAME, ContactsContract.CommonDataKinds.Email.ADDRESS), sel, q, null)?.use { c ->
      while (c.moveToNext()) out[c.getString(0)]?.add(c.getString(1))
    }
    return if (out.isEmpty()) "No contact matches '$name'." else out.entries.joinToString("\n") { (n, v) -> "$n: ${v.distinct().joinToString(", ")}" }
  }

  private fun readCalendar(days: Int): String {
    val start = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0) }.timeInMillis
    val end = start + days * 86_400_000L
    val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { android.content.ContentUris.appendId(it, start); android.content.ContentUris.appendId(it, end) }.build()
    val fmt = SimpleDateFormat("EEE d MMM HH:mm", Locale.US)
    val lines = ArrayList<String>()
    context.contentResolver.query(uri, arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.ALL_DAY), null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
      while (c.moveToNext() && lines.size < 40) {
        val allDay = c.getInt(4) == 1
        val whenText = if (allDay) SimpleDateFormat("EEE d MMM", Locale.US).format(Date(c.getLong(1))) + " (all day)" else fmt.format(Date(c.getLong(1))) + "–" + SimpleDateFormat("HH:mm", Locale.US).format(Date(c.getLong(2)))
        lines += "$whenText: ${c.getString(0)}${c.getString(3)?.takeIf { it.isNotBlank() }?.let { " @ $it" } ?: ""}"
      }
    }
    return if (lines.isEmpty()) "No events in the next $days day(s)." else lines.joinToString("\n")
  }

  private fun parseTime(s: String): Pair<Int, Int> {
    val m = Regex("""(\d{1,2})(?::(\d{2}))?\s*([aApP][mM])?""").find(s) ?: error("time must look like 07:30")
    var h = m.groupValues[1].toInt()
    val min = m.groupValues[2].ifEmpty { "0" }.toInt()
    val ap = m.groupValues[3].lowercase()
    if (ap == "pm" && h < 12) h += 12
    if (ap == "am" && h == 12) h = 0
    require(h in 0..23 && min in 0..59) { "invalid time $s" }
    return h to min
  }

  private fun parseDateTime(s: String): Long {
    val t = s.trim().replace('T', ' ')
    for (p in listOf("yyyy-MM-dd HH:mm", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd")) {
      runCatching { return SimpleDateFormat(p, Locale.US).apply { isLenient = false }.parse(t)!!.time }
    }
    error("date must look like 2026-11-03 14:00")
  }

  companion object {
    /** The model reads "opened the email app" as "sent": say plainly that the user still has to act. */
    private fun String.notDone(done: String, how: String) =
      if (isEmpty() || startsWith("Opened") || startsWith("Queued")) "$this Nothing was $done yet. Reply in one short sentence that it is ready for the user to $how."
      else this

    val NAMES = setOf("device_status", "calculate", "set_alarm", "set_timer", "add_calendar_event", "draft_text_message", "draft_email", "dial", "open_map", "open_website", "read_clipboard", "find_contact", "read_calendar")
  }
}

/**
 * Apps the model opened for one request, shown one at a time: the first opens at once, each next one when the user
 * returns to OmniChat (MainActivity.onResume). Stale entries (10 minutes) are dropped.
 */
object PhoneQueue {
  private class Item(val intent: Intent, val what: String)
  private val queue = ArrayDeque<Item>()
  private var lastOpen = 0L

  /** 0 = opened now, -1 = no app for it, n > 0 = queued at that position. */
  @Synchronized
  fun open(context: Context, intent: Intent, what: String): Int {
    val now = System.currentTimeMillis()
    if (now - lastOpen > 10 * 60_000) queue.clear()
    // something opened moments ago is still covering OmniChat: this one waits its turn
    if (queue.isNotEmpty() || now - lastOpen < 20_000) {
      queue.addLast(Item(intent, what))
      return queue.size
    }
    return if (launch(context, Item(intent, what))) 0 else -1
  }

  /** OmniChat is on screen again: open the next waiting app, if any. */
  @Synchronized
  fun resume(context: Context) {
    val next = queue.removeFirstOrNull() ?: return
    if (System.currentTimeMillis() - lastOpen > 10 * 60_000) return queue.clear()
    launch(context, next)
  }

  val waiting: Int @Synchronized get() = queue.size

  private fun launch(context: Context, item: Item): Boolean =
    try {
      context.startActivity(item.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      lastOpen = System.currentTimeMillis()
      AppLog.i("tool opened ${item.what}")
      true
    } catch (e: ActivityNotFoundException) {
      false
    }
}

/**
 * A small exact calculator: + - * / ^ (or **), percent ("17.5% of 2340", "84 * 15%"), parentheses, and
 * sqrt/abs/round/floor/ceil/ln/log/sin/cos/tan, pi and e.
 */
object Calculator {
  fun eval(expr: String): Double {
    val p = Parser(expr.replace("×", "*").replace("÷", "/").replace(",", "").replace("**", "^").replace(OF, "*"))
    val v = p.sum()
    p.skip()
    require(p.i == p.s.length) { "could not read '${p.s.substring(p.i)}'" }
    return v
  }

  fun format(v: Double): String = if (v == Math.rint(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else "%.10g".format(v).trimEnd('0').trimEnd('.')

  private val OF = Regex("""\s+of\s+""", RegexOption.IGNORE_CASE)

  private class Parser(val s: String) {
    var i = 0

    fun skip() { while (i < s.length && s[i].isWhitespace()) i++ }

    fun eat(c: Char): Boolean { skip(); return if (i < s.length && s[i] == c) { i++; true } else false }

    fun sum(): Double {
      var v = product()
      while (true) v = when { eat('+') -> v + product(); eat('-') -> v - product(); else -> return v }
    }

    fun product(): Double {
      var v = power()
      while (true) v = when { eat('*') -> v * power(); eat('/') -> v / power(); else -> return v }
    }

    fun power(): Double {
      var b = unary()
      // the model writes % as percent, never as a remainder
      while (eat('%')) b /= 100
      return if (eat('^')) Math.pow(b, power()) else b
    }

    fun unary(): Double = if (eat('-')) -unary() else if (eat('+')) unary() else atom()

    fun atom(): Double {
      skip()
      if (eat('(')) { val v = sum(); require(eat(')')) { "missing )" }; return v }
      val start = i
      if (i < s.length && s[i].isLetter()) {
        while (i < s.length && s[i].isLetter()) i++
        val name = s.substring(start, i).lowercase()
        when (name) { "pi" -> return Math.PI; "e" -> return Math.E }
        val x = atom()
        return when (name) {
          "sqrt" -> kotlin.math.sqrt(x); "abs" -> kotlin.math.abs(x); "round" -> Math.rint(x); "floor" -> kotlin.math.floor(x); "ceil" -> kotlin.math.ceil(x)
          "ln" -> kotlin.math.ln(x); "log" -> kotlin.math.log10(x); "sin" -> kotlin.math.sin(x); "cos" -> kotlin.math.cos(x); "tan" -> kotlin.math.tan(x)
          else -> error("unknown function $name")
        }
      }
      while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
      require(i > start) { "expected a number at '${s.substring(start)}'" }
      return s.substring(start, i).toDouble()
    }
  }
}
