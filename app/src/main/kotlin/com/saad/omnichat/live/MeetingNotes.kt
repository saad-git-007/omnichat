package com.saad.omnichat.live

/**
 * Meeting notes for live transcribe: action items, decisions and a key point or two per minute, written while it
 * listens. The 2B model can't be handed a stretch of transcript and asked for action items: on scripted meetings it
 * found none in one prompt, and in another listed small talk ("go ahead", "let me share my screen") as tasks. So the
 * work is split:
 *  - sentences that sound like a request or a promise ("can you", "will", "by Friday") are picked out here, and the
 *    model answers one narrow question about each: a task for someone after the meeting, or not? (5/5 and 7/8 found
 *    on 10 and 30 minute meetings, none invented, none on a meeting without any);
 *  - decisions are the sentences that say so ("we agreed", "we decided"), word for word, so nothing is made up;
 *  - key points are written from one minute of transcript at a time, and a point whose words mostly aren't in that
 *    minute is dropped.
 */
object MeetingNotes {
  data class ActionItem(val at: Long, val who: String, val task: String, val due: String?)

  data class Decision(val at: Long, val text: String)

  data class KeyPoint(val at: Long, val text: String)

  /**
   * [checked]: lines whose sentences were looked at for action items and decisions; [minutes]: whole minutes of
   * transcript turned into key points; [summary]: the whole meeting in a few sentences, written on request.
   */
  data class Notes(
    val items: List<ActionItem> = emptyList(),
    val decisions: List<Decision> = emptyList(),
    val points: List<KeyPoint> = emptyList(),
    val summary: String? = null,
    val checked: Int = 0,
    val minutes: Int = 0,
  ) {
    val isEmpty get() = items.isEmpty() && decisions.isEmpty() && points.isEmpty() && summary == null
  }

  const val MINUTE = 60_000L

  const val SYSTEM =
    "You keep notes of a meeting from its live transcript. The transcript is written down by a speech recognizer, so a few words may be wrong. Use only what is said in it."

  private val REQUEST = Regex(
    """\b(can you|could you|would you|please|will|i'll|i will|we'll|is going to|are going to|going to|needs? to|has to|have to|by (monday|tuesday|wednesday|thursday|friday|tomorrow|today|next|the end))\b""",
    RegexOption.IGNORE_CASE,
  )
  private val DECISION = Regex("""\b(we (agreed|decided)|it was decided|decision is|we're going with|we will go with|let's go with)\b""", RegexOption.IGNORE_CASE)

  fun sentences(text: String): List<String> = text.split(Regex("""(?<=[.!?])\s+""")).map { it.trim() }.filter { it.isNotEmpty() }

  /** Sentences worth asking the model about: they sound like a request or a promise. */
  fun candidates(text: String) = sentences(text).filter(::isCandidate)

  fun isCandidate(sentence: String) = REQUEST.containsMatchIn(sentence)

  fun decisions(text: String, at: Long) = sentences(text).filter { DECISION.containsMatchIn(it) }.map { Decision(at, it) }

  /** The question about one [sentence], with the sentences said just [before] and [after] it ("that's me, Ben"). */
  fun itemQuestion(before: String, sentence: String, after: String) =
    "From a meeting transcript (a speech recognizer wrote it down, so a few words may be wrong):\n" +
      "Said just before: \"$before\"\nSentence: \"$sentence\"\nSaid just after: \"$after\"\n\n" +
      "Is the Sentence an action item: a task that a named person is asked to do, or agrees to do, after the meeting? " +
      "Things said to the people in the call right now (\"go ahead\", \"let me share my screen\", \"I'll let you finish\") are not action items, " +
      "and neither are reports of what already happened or is planned anyway, like an office closing.\n" +
      "If it is an action item, reply as: name | task | when (or -). Otherwise reply: no"

  /**
   * The model's answer to [itemQuestion] as an item, or null for "no", anything not in the asked form, and the model
   * filling the form from the question itself: the owner must be "I"/"we" or a name, every word capitalised, that is
   * in [context] (the sentence and its neighbours), and a due date mustn't be the question's own words.
   */
  fun parseItem(reply: String, at: Long, context: String): ActionItem? {
    val r = reply.trim().lines().firstOrNull { it.isNotBlank() }?.trim()?.removePrefix("-")?.trim() ?: return null
    if (r.lowercase().startsWith("no") || '|' !in r) return null
    val parts = r.split('|').map { it.trim().trim('*', '"', '.').trim() }
    val who = parts.getOrNull(0).orEmpty()
    val task = parts.getOrNull(1).orEmpty()
    // "by the end of the sprint" is shown as "by …" already
    val due = parts.getOrNull(2)?.replace(Regex("^by\\s+", RegexOption.IGNORE_CASE), "")?.takeIf { it.isNotEmpty() && it != "-" && !it.equals("none", true) }
    if (who.isEmpty() || task.isEmpty() || who.length > 40) return null
    if (who.lowercase() in PLACEHOLDERS || task.lowercase() in PLACEHOLDERS || due?.lowercase()?.trim('.', ' ') in ECHOES) return null
    if (who.lowercase() !in PRONOUNS) {
      val names = Regex("""[A-Za-z][A-Za-z'.-]*""").findAll(who).map { it.value }.toList()
      if (names.isEmpty() || names.any { !it[0].isUpperCase() }) return null
      if (names.any { n -> n.length >= 3 && !context.contains(n, ignoreCase = true) }) return null
    }
    return ActionItem(at, who, task, due)
  }

  /** [items] plus [item], unless the same person already has the same task (said twice, or in other words). */
  fun addItem(items: List<ActionItem>, item: ActionItem): List<ActionItem> {
    fun same(a: ActionItem, b: ActionItem): Boolean {
      if (!a.who.equals(b.who, ignoreCase = true)) return false
      val wa = words(a.task)
      val wb = words(b.task)
      return (wa intersect wb).size.toDouble() / maxOf(1, minOf(wa.size, wb.size)) >= 0.6
    }
    return if (items.any { same(it, item) }) items else items + item
  }

  private val PLACEHOLDERS = setOf("who", "name", "someone", "somebody", "nobody", "no one", "none", "n/a", "unknown", "-", "task", "agrees", "what")
  private val PRONOUNS = setOf("i", "we", "me")
  // words of the question the model sometimes returns as a due date
  private val ECHOES = setOf("after the meeting", "just before", "just after", "said just before", "said just after", "the meeting")

  fun pointsQuestion(minute: String) =
    "Transcript part:\n$minute\n\nWrite the 1 or 2 most important points of this part as short bullet points starting with \"- \". " +
      "Use only what is said here. Skip greetings, audio checks and small talk."

  /** Bullet points from [reply] whose words are mostly in [minute]: a point about something not said is dropped. */
  fun parsePoints(reply: String, minute: String, at: Long): List<KeyPoint> {
    val said = words(minute)
    return reply.lines().map { it.trim().trimStart('-', '*', '•').trim() }.filter { it.isNotEmpty() }
      .filter { p -> words(p).let { w -> w.isNotEmpty() && w.count { it in said }.toDouble() / w.size >= 0.6 } }
      .take(2)
      .map { KeyPoint(at, it) }
  }

  /** [points] plus those of [new] that don't repeat one already noted ("the office is closed on Monday" twice). */
  fun addPoints(points: List<KeyPoint>, new: List<KeyPoint>): List<KeyPoint> {
    val out = points.toMutableList()
    for (p in new) {
      val w = words(p.text)
      if (out.none { o -> words(o.text).let { ow -> (ow intersect w).size.toDouble() / maxOf(1, minOf(ow.size, w.size)) >= 0.8 } }) out += p
    }
    return out
  }

  /** The transcript of minute [m] (lines starting in it), or null while it isn't over yet and [running]. */
  fun minuteText(lines: List<Line>, m: Int, running: Boolean): String? {
    val from = m * MINUTE
    val to = from + MINUTE
    if (running && lines.none { it.at >= to }) return null
    return lines.filter { it.at in from until to }.joinToString("\n") { it.text }.ifBlank { null }
  }

  fun summaryQuestion(points: List<KeyPoint>, decisions: List<Decision>, items: List<ActionItem>) =
    "Key points of a meeting, in order:\n" + points.joinToString("\n") { "- " + it.text } +
      (if (decisions.isEmpty()) "" else "\n\nDecisions:\n" + decisions.joinToString("\n") { "- " + it.text }) +
      (if (items.isEmpty()) "" else "\n\nAction items:\n" + items.joinToString("\n") { "- " + it.who + ": " + it.task }) +
      "\n\nSummarize the meeting in 3 to 5 sentences. Mention the decisions. Use only what is listed here."

  /** A transcript line: [at] ms from the start. */
  data class Line(val at: Long, val text: String)

  /** What to note next. */
  sealed interface Step {
    /** Look for action items and decisions in line [index]. */
    data class CheckLine(val index: Int) : Step

    /** Key points of minute [minute]; [text] null when nothing was said in it. */
    data class Minute(val minute: Int, val text: String?) : Step

    data object Summary : Step
  }

  /**
   * The next step, or null when the notes are up to date: new lines first (an action item shows up a piece after it
   * is said), then each minute once a later line shows it is over (or listening stopped), then the summary, when asked
   * for or once listening has stopped, after everything else.
   */
  fun nextStep(lines: List<Line>, notes: Notes, running: Boolean, summaryWanted: Boolean): Step? {
    // a line waits for the next one while listening: its last sentence is asked about with the one after it
    if (notes.checked < lines.size && (notes.checked + 1 < lines.size || !running)) return Step.CheckLine(notes.checked)
    val lastMinute = ((lines.lastOrNull()?.at ?: -1) / MINUTE).toInt()
    if (notes.minutes <= lastMinute) {
      val m = notes.minutes
      val closed = !running || lines.any { it.at >= (m + 1) * MINUTE }
      if (closed) return Step.Minute(m, minuteText(lines, m, running = false))
    }
    val minutesDone = notes.minutes > lastMinute
    if ((summaryWanted || (!running && notes.summary == null)) && notes.points.size >= 2 && (running || minutesDone)) return Step.Summary
    return null
  }

  private fun words(t: String) = Regex("[a-z]{4,}").findAll(t.lowercase()).map { it.value }.toSet()
}
