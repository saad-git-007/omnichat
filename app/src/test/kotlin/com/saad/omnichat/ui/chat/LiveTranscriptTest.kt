package com.saad.omnichat.ui.chat

import com.saad.omnichat.live.MeetingNotes
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTranscriptTest {
  private val t = LiveTranscript(
    started = 0,
    lines = listOf(LiveLine(0, "Good morning everyone."), LiveLine(12_000, "Priya, can you send the budget by Thursday?"), LiveLine(3_725_500, "Thank you all.")),
    running = false,
    marks = listOf(14_000),
    notes = MeetingNotes.Notes(
      items = listOf(MeetingNotes.ActionItem(12_000, "Priya", "send the budget", "Thursday")),
      decisions = listOf(MeetingNotes.Decision(12_000, "We agreed to keep the pricing.")),
      points = listOf(MeetingNotes.KeyPoint(0, "Budget is due Thursday.")),
      summary = "A short planning meeting.",
      checked = 3,
      minutes = 63,
    ),
  )

  @Test
  fun bookmarksMarkTheirLine() {
    assertFalse(t.marked(0))
    assertTrue(t.marked(1))
    assertTrue(t.text(true).lines()[1].startsWith("[00:12] ★ Priya"))
    assertEquals("[1:02:05] Thank you all.", t.text(true).lines()[2])
  }

  @Test
  fun markdownHasNotesThenTranscript() {
    val md = t.markdown()
    val order = listOf("## Summary", "## Action items", "## Decisions", "## Key points", "## Bookmarks", "## Transcript").map { md.indexOf(it) }
    assertTrue(md, order.all { it >= 0 } && order == order.sorted())
    assertTrue(md, "- [ ] **Priya**: send the budget (by Thursday) · 00:12" in md)
    assertTrue(md, "- 00:14 Priya, can you send the budget by Thursday?" in md)
  }

  @Test
  fun subtitlesRunUntilTheNextLine() {
    val srt = t.srt(lastMs = 5000)
    assertTrue(srt, srt.startsWith("1\n00:00:00,000 --> 00:00:12,000\nGood morning everyone.\n\n2\n00:00:12,000 --> 01:02:05,500\n"))
    assertTrue(srt, "3\n01:02:05,500 --> 01:02:10,500\nThank you all.\n" in srt)
  }

  @Test
  fun subtitlesLastAsLongAsThePiece() {
    val l = LiveTranscript(0, listOf(LiveLine(0, "One.", 8_000), LiveLine(12_000, "Two.", 41_500)), running = false)
    assertTrue(l.srt(), l.srt().startsWith("1\n00:00:00,000 --> 00:00:08,000\nOne.\n\n2\n00:00:12,000 --> 00:00:53,500\nTwo.\n"))
    assertEquals(l, LiveTranscript.fromJson(JSONObject(l.toJson().toString())))
  }

  @Test
  fun autoSaveRoundTrip() {
    val back = LiveTranscript.fromJson(JSONObject(t.toJson().toString()))
    assertEquals(t.copy(running = false), back)
    // a recording saved mid-way comes back stopped, without a summary it never had
    val partial = LiveTranscript.fromJson(JSONObject(t.copy(running = true, notes = MeetingNotes.Notes(checked = 1)).toJson().toString()))
    assertFalse(partial.running)
    assertEquals(null, partial.notes.summary)
    assertEquals(1, partial.notes.checked)
  }
}
