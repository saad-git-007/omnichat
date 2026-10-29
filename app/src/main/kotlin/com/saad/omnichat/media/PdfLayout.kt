package com.saad.omnichat.media

/**
 * Lays out the words of one PDF page as text, keeping what plain extraction loses: which value sits in which table
 * column, and which side of a two-column page a sentence belongs to.
 *
 * A wide gap between words splits a line into cells. Runs of lines with two or more cells are tables: their columns are
 * the x ranges the cells overlap in, and they come out as Markdown tables. A run of long prose cells in two or three
 * columns is a multi-column page instead: each column comes out whole, one after the other.
 */
object PdfLayout {
  /** A cell: words with no wide gap between them, [x0]..[x1] in page points. */
  data class Seg(val x0: Float, val x1: Float, val text: String)

  /** [para]: a paragraph ends before this line. */
  data class Line(val segs: List<Seg>, val para: Boolean = false)

  private const val MAX_COLUMNS = 12
  private const val PROSE_CELL = 30 // average characters per cell in two-column text, more than in tables

  fun render(lines: List<Line>): String {
    val out = StringBuilder()
    var i = 0
    while (i < lines.size) {
      if (lines[i].para && out.isNotEmpty()) out.append('\n')
      // a table: consecutive lines of 2+ cells; a one-cell line inside it is a row with empty cells. Paragraph breaks
      // don't end it: PDFBox takes widely spaced rows, or a row with an empty first cell, for new paragraphs
      var end = i
      while (end < lines.size) {
        val l = lines[end]
        if (l.segs.size >= 2) end++
        else if (l.segs.size == 1 && end > i && end + 1 < lines.size && lines[end + 1].segs.size >= 2) end++
        else break
      }
      val block = lines.subList(i, end)
      val cols = if (block.size >= 2) columns(block) else emptyList()
      when {
        cols.size < 2 || cols.size > MAX_COLUMNS -> {
          // a lone line with a wide gap ("Invoice 1042      Date 3 May"): a tab keeps the two apart
          out.append(lines[i].segs.joinToString("\t") { it.text }).append('\n')
          i++
          continue
        }
        cols.size <= 3 && block.size >= 3 && block.sumOf { l -> l.segs.sumOf { it.text.length } } / block.sumOf { it.segs.size } > PROSE_CELL ->
          // two or three columns of running text, read down each column
          for (c in cols.indices) {
            out.append(block.mapNotNull { l -> cellText(l, cols, c).ifEmpty { null } }.joinToString("\n")).append("\n\n")
          }
        else -> {
          for ((r, l) in block.withIndex()) {
            out.append("| ").append(cols.indices.joinToString(" | ") { cellText(l, cols, it).replace("|", "\\|") }).append(" |\n")
            if (r == 0) out.append("|").append("---|".repeat(cols.size)).append('\n')
          }
        }
      }
      i = end
    }
    return out.toString().trim()
  }

  /** The x ranges cells overlap in, left to right. One-cell lines (a title, a wrapped note) don't count. */
  private fun columns(block: List<Line>): List<ClosedFloatingPointRange<Float>> {
    val spans = block.filter { it.segs.size >= 2 }.flatMap { it.segs }.map { it.x0..it.x1 }.sortedBy { it.start }
    val out = ArrayList<ClosedFloatingPointRange<Float>>()
    for (s in spans) {
      val last = out.lastOrNull()
      if (last != null && s.start <= last.endInclusive) out[out.lastIndex] = last.start..maxOf(last.endInclusive, s.endInclusive) else out += s
    }
    return out
  }

  private fun cellText(l: Line, cols: List<ClosedFloatingPointRange<Float>>, c: Int) =
    l.segs.filter { s -> column(cols, s) == c }.joinToString(" ") { it.text }

  /** The column the cell's middle is in, else the one starting nearest it. */
  private fun column(cols: List<ClosedFloatingPointRange<Float>>, s: Seg) =
    cols.indexOfFirst { (s.x0 + s.x1) / 2 in it }.takeIf { it >= 0 } ?: cols.indices.minBy { kotlin.math.abs(cols[it].start - s.x0) }
}
