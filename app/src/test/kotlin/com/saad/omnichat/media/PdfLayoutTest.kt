package com.saad.omnichat.media

import com.saad.omnichat.media.PdfLayout.Line
import com.saad.omnichat.media.PdfLayout.Seg
import org.junit.Assert.assertEquals
import org.junit.Test

class PdfLayoutTest {
  private fun line(vararg cells: Pair<Float, String>, para: Boolean = false) =
    Line(cells.map { (x, t) -> Seg(x, x + t.length * 5f, t) }, para)

  @Test
  fun tableWithAnEmptyCell() {
    val text = PdfLayout.render(
      listOf(
        line(72f to "Quarterly sales"),
        line(72f to "Region", 200f to "Q1", 300f to "Q2"),
        line(72f to "North", 200f to "1,200", 300f to "1,350", para = true), // rows far apart look like paragraphs
        line(72f to "South", 300f to "990", para = true), // no Q1 figure
        line(72f to "Total", 200f to "1,200", 300f to "2,340"),
        line(72f to "Figures in dollars.", para = true),
      ),
    )
    assertEquals(
      """
      Quarterly sales
      | Region | Q1 | Q2 |
      |---|---|---|
      | North | 1,200 | 1,350 |
      | South |  | 990 |
      | Total | 1,200 | 2,340 |

      Figures in dollars.
      """.trimIndent(),
      text,
    )
  }

  @Test
  fun twoColumnPageReadsDownEachColumn() {
    val l = "The left column starts the story and"
    val r = "The right column carries on reading"
    val text = PdfLayout.render(listOf(line(72f to "$l 1", 320f to "$r 1"), line(72f to "$l 2", 320f to "$r 2"), line(72f to "$l 3", 320f to "$r 3")))
    assertEquals("$l 1\n$l 2\n$l 3\n\n$r 1\n$r 2\n$r 3", text)
  }

  @Test
  fun loneGapIsATab() {
    assertEquals("Invoice 1042\tDate 3 May\nThanks", PdfLayout.render(listOf(line(72f to "Invoice 1042", 400f to "Date 3 May"), line(72f to "Thanks"))))
  }
}
