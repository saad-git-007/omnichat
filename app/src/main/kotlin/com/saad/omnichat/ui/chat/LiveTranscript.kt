package com.saad.omnichat.ui.chat

import com.saad.omnichat.live.MeetingNotes
import org.json.JSONArray
import org.json.JSONObject

/** One stretch of live transcription; [at] is milliseconds from the start, [ms] how long the piece was (0: unknown). */
data class LiveLine(val at: Long, val text: String, val ms: Long = 0)

/**
 * Live transcribe: the mic stays open and what is said is written down a few seconds at a time. [marks] are the
 * moments the user bookmarked (ms from the start); [notes] are the meeting notes written alongside, when on.
 */
data class LiveTranscript(
  val started: Long,
  val lines: List<LiveLine> = emptyList(),
  val running: Boolean = true,
  val writing: Boolean = false,
  val marks: List<Long> = emptyList(),
  val notes: MeetingNotes.Notes = MeetingNotes.Notes(),
  val noting: Boolean = false,
) {
  /** Whether a bookmark falls in line [i] (from its start to the next line's). */
  fun marked(i: Int): Boolean {
    val from = lines[i].at
    val to = lines.getOrNull(i + 1)?.at ?: Long.MAX_VALUE
    return marks.any { it in from until to }
  }

  /** With [stamps], a line per piece starting with its time (★ when bookmarked); without, plain running text. */
  fun text(stamps: Boolean): String =
    if (stamps) lines.indices.joinToString("\n") { i -> "[${stamp(lines[i].at)}]${if (marked(i)) " ★" else ""} ${lines[i].text}" }
    else lines.joinToString(" ") { it.text.trim() }

  fun export(stamps: Boolean): String =
    if (!stamps) text(false) + "\n"
    else "Live transcript, ${date()}\n\n${text(true)}\n"

  /** Notes first (summary, action items, decisions, key points, bookmarks), then the transcript with time stamps. */
  fun markdown(): String = buildString {
    append("# Meeting, ").append(date()).append("\n\n")
    notes.summary?.let { append("## Summary\n\n").append(it.trim()).append("\n\n") }
    if (notes.items.isNotEmpty()) {
      append("## Action items\n\n")
      notes.items.forEach { append("- [ ] **").append(it.who).append("**: ").append(it.task).append(it.due?.let { d -> " (by $d)" }.orEmpty()).append(" · ").append(stamp(it.at)).append('\n') }
      append('\n')
    }
    if (notes.decisions.isNotEmpty()) {
      append("## Decisions\n\n")
      notes.decisions.forEach { append("- ").append(it.text).append(" · ").append(stamp(it.at)).append('\n') }
      append('\n')
    }
    if (notes.points.isNotEmpty()) {
      append("## Key points\n\n")
      notes.points.forEach { append("- ").append(stamp(it.at)).append(" ").append(it.text).append('\n') }
      append('\n')
    }
    if (marks.isNotEmpty()) {
      append("## Bookmarks\n\n")
      marks.forEach { m -> append("- ").append(stamp(m)).append(lines.lastOrNull { it.at <= m }?.text?.let { t -> " " + t.take(120) }.orEmpty()).append('\n') }
      append('\n')
    }
    append("## Transcript\n\n").append(text(true)).append('\n')
  }

  /** Subtitles: each piece shown for as long as it was said (or until the next one; [lastMs] when unknown). */
  fun srt(lastMs: Long = 10_000): String = buildString {
    lines.forEachIndexed { i, l ->
      val next = lines.getOrNull(i + 1)?.at
      val end = if (l.ms > 0) (l.at + l.ms).let { e -> if (next != null) minOf(e, next) else e } else next ?: (l.at + lastMs)
      append(i + 1).append('\n').append(srtTime(l.at)).append(" --> ").append(srtTime(end)).append('\n').append(l.text.trim()).append("\n\n")
    }
  }

  /** What auto-save writes after every piece, so a crash or a reboot loses nothing. */
  fun toJson(): JSONObject = JSONObject().apply {
    put("started", started)
    put("lines", JSONArray().apply { lines.forEach { put(JSONObject().put("at", it.at).put("text", it.text).put("ms", it.ms)) } })
    put("marks", JSONArray(marks))
    put("items", JSONArray().apply { notes.items.forEach { put(JSONObject().put("at", it.at).put("who", it.who).put("task", it.task).put("due", it.due ?: "")) } })
    put("decisions", JSONArray().apply { notes.decisions.forEach { put(JSONObject().put("at", it.at).put("text", it.text)) } })
    put("points", JSONArray().apply { notes.points.forEach { put(JSONObject().put("at", it.at).put("text", it.text)) } })
    notes.summary?.let { put("summary", it) }
    put("checked", notes.checked)
    put("minutes", notes.minutes)
  }

  private fun date() = java.text.SimpleDateFormat("EEE d MMM yyyy, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(started))

  companion object {
    fun stamp(ms: Long): String {
      val s = ms / 1000
      return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
    }

    private fun srtTime(ms: Long) = "%02d:%02d:%02d,%03d".format(ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)

    /** A transcript saved by [toJson], stopped (it is no longer listening). */
    fun fromJson(o: JSONObject): LiveTranscript {
      fun <T> list(key: String, f: (JSONObject) -> T): List<T> = o.optJSONArray(key)?.let { a -> (0 until a.length()).map { f(a.getJSONObject(it)) } }.orEmpty()
      return LiveTranscript(
        started = o.getLong("started"),
        lines = list("lines") { LiveLine(it.getLong("at"), it.getString("text"), it.optLong("ms")) },
        running = false,
        marks = o.optJSONArray("marks")?.let { a -> (0 until a.length()).map { a.getLong(it) } }.orEmpty(),
        notes = MeetingNotes.Notes(
          items = list("items") { MeetingNotes.ActionItem(it.getLong("at"), it.getString("who"), it.getString("task"), it.optString("due").ifEmpty { null }) },
          decisions = list("decisions") { MeetingNotes.Decision(it.getLong("at"), it.getString("text")) },
          points = list("points") { MeetingNotes.KeyPoint(it.getLong("at"), it.getString("text")) },
          summary = o.optString("summary").ifEmpty { null },
          checked = o.optInt("checked"),
          minutes = o.optInt("minutes"),
        ),
      )
    }
  }
}
