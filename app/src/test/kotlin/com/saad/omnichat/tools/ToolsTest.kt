package com.saad.omnichat.tools

import com.saad.omnichat.ui.chat.asksFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolsTest {
  private fun calc(e: String) = Calculator.format(Calculator.eval(e))

  @Test
  fun calculator() {
    assertEquals("409.5", calc("17.5/100*2340"))
    assertEquals("409.5", calc("17.5% * 2340"))
    assertEquals("409.5", calc("17.5% of 2340"))
    assertEquals("12.6", calc("84 * 15%"))
    assertEquals("1047576", calc("(2 ** 20) - 1000"))
    assertEquals("1047576", calc("2^20 - 1000"))
    assertEquals("76.8", calc("230.40 ÷ 3"))
    assertEquals("7006652", calc("1,234 × 5,678"))
    assertEquals("-8", calc("-2^3"))
    assertEquals("1.414213562", calc("sqrt(2)"))
  }

  @Test
  fun parsesCalls() {
    val c = ToolCalls.parse("<tool_call>\n<function=set_alarm>\n<parameter=time>\n07:00\n</parameter>\n</function>\n</tool_call>")!!
    assertEquals("set_alarm", c.name)
    assertEquals(mapOf("time" to "07:00"), c.args)
    assertNull(ToolCalls.parse("<tool_call>\n<function=set_alarm>\n<parameter=time>\n07:")) // cut off mid-call
    assertNull(ToolCalls.parse("No tools here."))
  }

  @Test
  fun actionsNeedAnAsk() {
    // real requests (from the 64-message tool test) all pass
    listOf(
      "set_alarm" to "Set an alarm for 6:45 tomorrow", "set_alarm" to "Wake me up at 7", "set_alarm" to "alarm 5:30am please",
      "set_timer" to "Start a 5 minute timer", "set_timer" to "Remind me in 10 minutes to check the oven",
      "add_calendar_event" to "Add lunch with Sara on Friday at noon to my calendar", "add_calendar_event" to "Remind me tomorrow at 9 to call the bank",
      "draft_text_message" to "Text Sara and Ali that I'm running late", "draft_text_message" to "Message +15550100 saying hi",
      "draft_email" to "Email bob@example.com saying the report is ready", "dial" to "Call mom", "dial" to "Call +1 555 0100",
      "open_map" to "Give me directions to the CN Tower", "open_map" to "Navigate to Union Station",
      "open_website" to "Open youtube.com", "open_website" to "Go to github.com",
      "web_search" to "Who wrote Pride and Prejudice?", "calculate" to "anything", // not app-opening: never gated
    ).forEach { (tool, msg) -> assertTrue("$tool: $msg", asksFor(tool, msg)) }
    // what the model did unasked
    assertFalse(asksFor("set_alarm", "Say just OK"))
    assertFalse(asksFor("open_website", "Write a haiku about morning coffee"))
    assertFalse(asksFor("open_website", "Write a poem about an alarm clock"))
    assertFalse(asksFor("draft_text_message", "My friend Sara is sad, what should I say to her?"))
    // not English, or nothing typed (voice): left to the model
    assertTrue(asksFor("set_alarm", "مجھے سات بجے جگا دو"))
    assertTrue(asksFor("set_alarm", ""))
  }
}
