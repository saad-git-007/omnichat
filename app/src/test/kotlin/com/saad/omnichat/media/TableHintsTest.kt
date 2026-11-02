package com.saad.omnichat.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TableHintsTest {
  // what DocumentReader makes of a workbook: trailing empty cells are dropped
  private val workbook =
    """
    ## Sheet: Hours
    Employee,Mon,Tue,Wed,Thu,Fri
    A. Rahman,8,7.5,,8,6
    L. Chen,,8,8,8,8
    M. Okafor,4,,4,,4
    S. Waraich,9,9,8.5,9
    """.trimIndent()

  private val hours = TableHints.tables("timesheet.xlsx", workbook)

  @Test
  fun smallSheetIsWrittenRowByRow() {
    assertEquals(
      """
      ## Sheet: Hours
      Row 2: Employee: A. Rahman; Mon: 8; Tue: 7.5; Wed: —; Thu: 8; Fri: 6
      Row 3: Employee: L. Chen; Mon: —; Tue: 8; Wed: 8; Thu: 8; Fri: 8
      Row 4: Employee: M. Okafor; Mon: 4; Tue: —; Wed: 4; Thu: —; Fri: 4
      Row 5: Employee: S. Waraich; Mon: 9; Tue: 9; Wed: 8.5; Thu: 9; Fri: —
      """.trimIndent(),
      TableHints.forModel("timesheet.xlsx", workbook, partial = false),
    )
    // the opening of a long document, PDFs and text files are left as they are
    assertEquals(workbook, TableHints.forModel("timesheet.xlsx", workbook, partial = true))
    assertEquals("a,b\n1,2", TableHints.forModel("notes.txt", "a,b\n1,2", partial = false))
  }

  @Test
  fun rowTheQuestionNames() {
    assertEquals(
      """
      <from_the_spreadsheet>
      Rows that mention your words:
      [Hours row 4] Employee: M. Okafor; Mon: 4; Tue: —; Wed: 4; Thu: —; Fri: 4
      </from_the_spreadsheet>
      """.trimIndent(),
      TableHints.hints(hours, "Which days did M. Okafor work?"),
    )
  }

  @Test
  fun columnTheQuestionNames() {
    assertEquals(
      """
      <from_the_spreadsheet>
      Columns you mention:
      [Hours, column Tue] values: A. Rahman 7.5, L. Chen 8, S. Waraich 9. Empty for: M. Okafor. Highest 9 (S. Waraich); lowest 7.5 (A. Rahman); total 24.5 over 3 rows.
      </from_the_spreadsheet>
      """.trimIndent(),
      TableHints.hints(hours, "Who worked on Tuesday?"),
    )
  }

  @Test
  fun countsAndTotalsForANamedValue() {
    val csv = "Date,Vendor,Amount,Paid by\n2026-10-01,Air Canada,412.50,Nadia\n2026-10-08,Subway,22.10,Priya\n2026-10-09,Amazon,61.99,Priya\n2026-10-22,Air Canada,389.00,Priya\n"
    val h = TableHints.hints(TableHints.tables("expenses.csv", csv), "What did Priya spend in total?")!!
    assertTrue(h, "3 rows of expenses.csv have Paid by = Priya: Amount adds up to 473.09" in h)
    // a value of two words, and a column whose numbers aren't amounts (no "Date adds up to")
    val air = TableHints.hints(TableHints.tables("expenses.csv", csv), "How much went to Air Canada?")!!
    assertTrue(air, "2 rows of expenses.csv have Vendor = Air Canada: Amount adds up to 801.5" in air)
  }

  @Test
  fun pluralNamesAValue() {
    val csv = "Order,Product,Qty\n1001,Chair,2\n1002,Desk,1\n1003,Chair,8\n"
    val h = TableHints.hints(TableHints.tables("sales.csv", csv), "How many chairs were sold?")!!
    assertTrue(h, "2 rows of sales.csv have Product = Chair: Qty adds up to 10" in h)
  }

  @Test
  fun markdownTableFromOcr() {
    val ocr = "| Employee | Mon | Tue |\n|---|---|---|\n| L. Chen |  | 8 |\n| M. Okafor | 4 |  |"
    val h = TableHints.hints(TableHints.tables("photo 1", ocr), "What did L. Chen work on Monday?")!!
    assertTrue(h, "[photo 1 table 1 row 2] Employee: L. Chen; Mon: —; Tue: 8" in h)
  }

  @Test
  fun nothingForQuestionsAboutSomethingElse() {
    for (q in listOf("Write a haiku about autumn", "Set an alarm for 7am", "What is the capital of Peru?", "Thanks!", "Tell me a joke")) {
      assertNull(q, TableHints.hints(hours, q))
    }
    assertNull(TableHints.hints(TableHints.tables("memo.pdf", "Just a paragraph of text."), "Which days did M. Okafor work?"))
  }

  @Test
  fun wideSheetStaysSmall() {
    val header = "Name," + (1..60).joinToString(",") { "Q$it" }
    val rows = (1..300).joinToString("\n") { r -> "Person $r," + (1..60).joinToString(",") { "${r * it}" } }
    val h = TableHints.hints(TableHints.tables("wide.csv", "$header\n$rows\n"), "What did Person 7 score in Q3, and who has the highest Q12?")!!
    assertTrue(h.length.toString(), h.length < 3300)
    assertTrue(h, "Person 7" in h && "column Q12" in h)
  }

  @Test
  fun rowFarIntoABigSheet() {
    // the case meaning-based search got wrong: one invoice among a thousand rows of numbers
    val rows = (1..1000).joinToString("\n") { i -> "${10000 + i},2026-07-${i % 28 + 1},${i % 97},${i * 3}.25,${i * 7}.5" }
    val t = System.currentTimeMillis()
    val h = TableHints.hints(TableHints.tables("ledger.csv", "Invoice,Date,Units,Unit cost,Total\n$rows\n"), "What is the total for invoice 10500?")!!
    assertTrue(h, "[ledger.csv row 501] Invoice: 10500; Date: 2026-07-25; Units: 15; Unit cost: 1500.25; Total: 3500.5" in h)
    assertTrue("took ${System.currentTimeMillis() - t} ms", System.currentTimeMillis() - t < 2000)
  }

  @Test
  fun aBigSheetIsQuickOnceParsed() {
    val rows = (1..20_000).joinToString("\n") { i -> "${100000 + i},Customer ${i % 700},${listOf("North", "South", "East", "West")[i % 4]},${i % 50},${i * 1.5}" }
    val tables = TableHints.tables("big.csv", "Order,Customer,Region,Qty,Total\n$rows\n")
    TableHints.hints(tables, "What is the total for order 104242?") // the first question builds the per-table lookups
    val t = System.currentTimeMillis()
    val h = TableHints.hints(tables, "How many orders came from the West region and what is the total for order 112345?")!!
    val ms = System.currentTimeMillis() - t
    assertTrue(h, "5000 rows of big.csv have Region = West" in h && "Order: 112345" in h)
    assertTrue("a question took $ms ms", ms < 1500)
  }

  @Test
  fun partialDocumentGetsRowsOnly() {
    val h = TableHints.hints(TableHints.tables("timesheet.xlsx", workbook, partial = true), "Who worked on Tuesday? And M. Okafor?")!!
    assertTrue(h, "M. Okafor" in h && "Columns you mention" !in h)
  }
}
