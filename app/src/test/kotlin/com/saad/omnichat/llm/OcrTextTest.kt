package com.saad.omnichat.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrTextTest {
  @Test
  fun otslTableWithEmptyAndMergedCells() {
    // Xiaomi-OCR-0's own output for a receipt and a timesheet with blanks (host test, 2026-10-30)
    val raw = "## Order\n\n<fcel>MAPLE GROCERS #208<lcel><lcel><nl><fcel>Oat milk 1L<fcel>2<fcel>4.98<nl><fcel>TOTAL<ecel><fcel>37.17<nl>\n\nThanks"
    assertEquals(
      """
      ## Order

      | MAPLE GROCERS #208 |  |  |
      |---|---|---|
      | Oat milk 1L | 2 | 4.98 |
      | TOTAL |  | 37.17 |

      Thanks
      """.trimIndent(),
      OcrText.tidy(raw),
    )
  }

  @Test
  fun moneyWrittenLikeLatex() {
    assertEquals(
      "14 x cable @ \$8.75; 3 x hub @ \$41.20. Freight \$18.40, total \$378.00",
      OcrText.tidy("<think>\n\n</think>14 x cable @ \$8.75; 3 x hub @\$ 41.20. Freight\$ 18.40, total \$ 378.00"),
    )
  }
}
