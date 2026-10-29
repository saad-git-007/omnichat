package com.saad.omnichat.net

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Hits the real keyless endpoints; run on demand with -Dlive=true. */
class WebSearchLiveTest {
  @Test
  fun keylessSearch() = runBlocking {
    assumeTrue(System.getProperty("live") == "true")
    val found = WebSearch.search("llama.cpp hexagon backend snapdragon", "", null)
    found.forEach { println("[${it.n}] ${it.title} — ${it.url} (${it.content.length} chars)\n  ${it.content.take(160).replace('\n', ' ')}") }
    check(found.isNotEmpty())
    check(found.first().content.length > 1000) // top hit was read in full by Firecrawl
  }
}
