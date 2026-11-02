package com.saad.omnichat.live

import com.saad.omnichat.live.MeetingNotes.ActionItem
import com.saad.omnichat.live.MeetingNotes.KeyPoint
import com.saad.omnichat.live.MeetingNotes.Line
import com.saad.omnichat.live.MeetingNotes.Notes
import com.saad.omnichat.live.MeetingNotes.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeetingNotesTest {
  @Test
  fun requestsAndPromisesAreCandidates() {
    val text = "Okay, let's move on to the next point. Priya, can you send the revised budget to finance by Thursday? " +
      "Sales in the west region went up a little. Omar will book the venue for the team offsite next week."
    assertEquals(
      listOf("Priya, can you send the revised budget to finance by Thursday?", "Omar will book the venue for the team offsite next week."),
      MeetingNotes.candidates(text),
    )
    assertTrue(MeetingNotes.candidates("The numbers this month look about the same as last month.").isEmpty())
  }

  @Test
  fun decisionsAreTakenWordForWord() {
    val d = MeetingNotes.decisions("Right. We agreed to move the product launch to April fifteenth. Thanks all.", 60_000)
    assertEquals(listOf(MeetingNotes.Decision(60_000, "We agreed to move the product launch to April fifteenth.")), d)
    assertTrue(MeetingNotes.decisions("We talked about the launch for a while.", 0).isEmpty())
  }

  @Test
  fun modelRepliesBecomeItems() {
    // replies as the model wrote them in the scripted-meeting bench; the context is the sentence and its neighbours
    val ctx = "Grace is going to fix the slow login screen by the end of the sprint. That's me, Ben. Hamza, please order two more monitors."
    assertEquals(ActionItem(5, "Grace", "fix the slow login screen", "end of the sprint"), MeetingNotes.parseItem("Grace | fix the slow login screen | end of the sprint", 5, ctx))
    assertEquals(ActionItem(5, "Hamza", "order two more monitors", null), MeetingNotes.parseItem("Hamza | order two more monitors | -", 5, ctx))
    assertEquals(ActionItem(5, "Ben", "call the vendor", "tomorrow"), MeetingNotes.parseItem("- **Ben** | call the vendor | tomorrow.\n", 5, ctx))
    assertEquals(ActionItem(5, "I", "send the notes", null), MeetingNotes.parseItem("I | send the notes | -", 5, ctx))
    // the page shows "by …" itself
    assertEquals("the end of the sprint", MeetingNotes.parseItem("Grace | fix lag on screen | by the end of the sprint", 5, ctx)?.due)
    for (no in listOf("no", "No.", "No, this is small talk.", "Grace fixes the login screen", "", "  | task | -")) assertNull(no, MeetingNotes.parseItem(no, 5, ctx))
  }

  @Test
  fun formsFilledFromTheQuestionAreDropped() {
    // all seen on noisy or garbled transcripts
    val ctx = "I'll let you finish your point. Go ahead, that's interesting. Ben, the office will be closed on Monday."
    for (echo in listOf(
      "who | let you finish your point | ahead",
      "someone | agrees | -",
      "go ahead | finish your point | after the meeting",
      "Go ahead | to finish your point | just before",
      "Grace | fix the login screen | -", // Grace isn't in these sentences
    )) assertNull(echo, MeetingNotes.parseItem(echo, 5, ctx))
  }

  @Test
  fun theSameTaskSaidTwiceIsListedOnce() {
    val a = ActionItem(1, "Omar", "book the venue for the team offsite next week", "next week")
    assertEquals(listOf(a), MeetingNotes.addItem(listOf(a), ActionItem(9, "omar", "Book the venue for the team offsite", null)))
    assertEquals(2, MeetingNotes.addItem(listOf(a), ActionItem(9, "Omar", "send the customer survey", null)).size)
    assertEquals(2, MeetingNotes.addItem(listOf(a), ActionItem(9, "Priya", "book the venue for the team offsite", null)).size)
  }

  @Test
  fun pointsMustComeFromTheirMinute() {
    val minute = "The vendor said they are still waiting on the parts from their supplier. Customer feedback has been mostly positive, people like the new layout."
    val reply = "- The vendor is still waiting on parts from their supplier.\n- Customer feedback on the new layout is positive.\n- The board approved a new budget for Calgary."
    assertEquals(
      listOf(KeyPoint(60_000, "The vendor is still waiting on parts from their supplier."), KeyPoint(60_000, "Customer feedback on the new layout is positive.")),
      MeetingNotes.parsePoints(reply, minute, 60_000),
    )
  }

  @Test
  fun aPointNotedTwiceIsKeptOnce() {
    val first = listOf(KeyPoint(0, "The office will be closed on Monday for the holiday."))
    val next = listOf(KeyPoint(60_000, "The office will be closed on Monday for the holiday."), KeyPoint(60_000, "The new hire starts in two weeks."))
    assertEquals(listOf(first[0], next[1]), MeetingNotes.addPoints(first, next))
  }

  @Test
  fun stepsComeInOrder() {
    val lines = listOf(Line(0, "a"), Line(12_000, "b"), Line(61_000, "c"))
    // new lines first; while listening the newest waits for the one after it
    assertEquals(Step.CheckLine(0), MeetingNotes.nextStep(lines, Notes(), running = true, summaryWanted = false))
    assertEquals(Step.CheckLine(1), MeetingNotes.nextStep(lines, Notes(checked = 1), running = true, summaryWanted = false))
    // then minute 0, which a later line shows is over; minute 1 and line 2 wait while listening
    assertEquals(Step.Minute(0, "a\nb"), MeetingNotes.nextStep(lines, Notes(checked = 2), running = true, summaryWanted = false))
    assertNull(MeetingNotes.nextStep(lines, Notes(checked = 2, minutes = 1), running = true, summaryWanted = false))
    // stopped: the last line and minute are done too, then the summary
    assertEquals(Step.CheckLine(2), MeetingNotes.nextStep(lines, Notes(checked = 2, minutes = 1), running = false, summaryWanted = false))
    assertEquals(Step.Minute(1, "c"), MeetingNotes.nextStep(lines, Notes(checked = 3, minutes = 1), running = false, summaryWanted = false))
    val points = listOf(KeyPoint(0, "x"), KeyPoint(60_000, "y"))
    assertEquals(Step.Summary, MeetingNotes.nextStep(lines, Notes(checked = 3, minutes = 2, points = points), running = false, summaryWanted = false))
    // written once; asked for again while listening
    assertNull(MeetingNotes.nextStep(lines, Notes(checked = 3, minutes = 2, points = points, summary = "s"), running = false, summaryWanted = false))
    assertEquals(Step.Summary, MeetingNotes.nextStep(lines, Notes(checked = 2, minutes = 1, points = points, summary = "s"), running = true, summaryWanted = true))
    // a silent minute is still a step (it is skipped, not waited on forever)
    val gap = listOf(Line(0, "a"), Line(130_000, "b"))
    assertEquals(Step.Minute(1, null), MeetingNotes.nextStep(gap, Notes(checked = 1, minutes = 1), running = true, summaryWanted = false))
  }

  @Test
  fun anHourOfPointsFitsTheSideContextInGroups() {
    val points = (0 until 30).map { KeyPoint(it * 60_000L, "The team discussed the vendor delivery delay and the new onboarding guide for support staff.") }
    val q = MeetingNotes.summaryQuestion(points, emptyList(), emptyList())
    // ~4 characters a token: well under the 2,048-token side context with room for the 220-token answer
    assertTrue(q.length.toString(), q.length / 4 < 1500)
  }
}
