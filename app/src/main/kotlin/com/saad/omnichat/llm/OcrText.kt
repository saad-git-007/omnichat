package com.saad.omnichat.llm

/** Turns what Xiaomi-OCR-0 writes into plain Markdown for the chat model. */
object OcrText {
  private val THINK = Regex("<think>\\s*</think>")
  private val OTSL = Regex("(?:<(?:fcel|ecel|lcel|ucel|xcel|ched|rhed|srow)>[^<]*)+(?:<nl>(?:<(?:fcel|ecel|lcel|ucel|xcel|ched|rhed|srow)>[^<]*)*)*(?:<nl>)?")
  private val CELL = Regex("<(fcel|ecel|lcel|ucel|xcel|ched|rhed|srow)>([^<]*)")

  // the model writes money as if in a LaTeX formula: "@$ 41.20", "Freight$ 18.40", "$ 378.00"
  private val MONEY_GAP = Regex("""\$ +(?=\d)""")
  private val MONEY_GLUED = Regex("""(?<=[^\s(\[{$])\$(?=\d)""")

  fun tidy(raw: String): String {
    var t = THINK.replace(raw, "")
    t = OTSL.replace(t) { "\n" + table(it.value) + "\n" }
    t = MONEY_GLUED.replace(MONEY_GAP.replace(t, "\\$"), " \\$")
    return t.replace(Regex("\n{3,}"), "\n\n").trim()
  }

  /**
   * OTSL, one token per cell: fcel has text, ecel is empty, lcel / ucel / xcel continue the cell to the left / above /
   * both (a merged cell), nl ends a row. A merged cell's text is written once, in its first cell.
   */
  private fun table(otsl: String): String {
    val rows = otsl.split("<nl>").map { row -> CELL.findAll(row).map { it.groupValues[2].trim().replace("|", "\\|") }.toList() }.filter { it.isNotEmpty() }
    if (rows.isEmpty()) return ""
    val cols = rows.maxOf { it.size }
    val lines = rows.map { r -> "| " + (r + List(cols - r.size) { "" }).joinToString(" | ") + " |" }
    return (listOf(lines[0], "|" + "---|".repeat(cols)) + lines.drop(1)).joinToString("\n")
  }
}
