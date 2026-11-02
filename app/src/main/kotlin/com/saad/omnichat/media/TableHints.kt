package com.saad.omnichat.media

/**
 * Helps a small chat model answer from tables. Read as CSV, a 2B model shifts values into the wrong column after an
 * empty cell, invents values for empty cells, and miscounts and misadds rows. So small spreadsheets are written one row
 * per line with every value named, and for a question about a table the facts it needs are worked out here and put
 * next to it: the rows that name the question's words, exact counts and totals for a value it names, and a summary of
 * each column it names. Nothing is added when the question matches nothing in the tables, so other messages are sent
 * exactly as before.
 */
object TableHints {
  /** [partial]: only the opening of a longer document, so counts and column summaries over it would be wrong. */
  class Table(val name: String, val header: List<String>, val rows: List<List<String>>, val partial: Boolean = false) {
    fun cell(r: List<String>, j: Int) = r.getOrNull(j).orEmpty()

    // worked out once per table (tables are cached per document), not for every question: a big sheet is 100k rows
    internal val lower: List<List<String>> by lazy { rows.map { r -> r.map { it.lowercase() } } }
    internal val phrases: List<List<String>> by lazy { rows.map { r -> r.map { if (it.length >= 2) phrase(it) else "" } } }

    /** How many cells hold each whole-word value: "West" in 5,000 rows, an order number in one. */
    internal val phraseCount: Map<String, Int> by lazy { phrases.flatten().filter { it.isNotEmpty() }.groupingBy { it }.eachCount() }

    /** Each column's distinct values (3+ characters) with their whole-word form, for finding the ones a question names. */
    internal val values: List<List<Pair<String, String>>> by lazy {
      header.indices.map { j -> rows.indices.map { i -> cell(rows[i], j) to phrases[i].getOrElse(j) { "" } }.filter { it.first.length >= 3 }.distinctBy { it.first } }
    }
  }

  /** How an empty cell is written: the model reads "—" as "nothing here" more often than "(empty)" or "(blank)". */
  const val EMPTY = "—"

  /** A spreadsheet is written row by row up to this size; larger ones stay CSV, which takes half the tokens. */
  private const val RECORDS_MAX_CHARS = 12_000
  private const val MAX_ROWS = 12
  private const val LIST_COLUMN_MAX = 40 // rows: a column's values are listed only up to this many
  private const val MAX_GROUPS = 12 // distinct values: counts per value only up to this many
  // the whole block stays near 1k tokens however wide the sheet: rows of a 60-column sheet are long
  private const val MAX_CHARS = 3000
  private const val MAX_LINE = 500
  private const val MAX_SUMS = 4
  private const val WIDE_ROW = 12 // columns: wider rows show only the columns the question names

  private val STOP = setOf("the", "and", "for", "with", "what", "which", "who", "how", "many", "much", "did", "does", "was", "were",
    "are", "this", "that", "from", "all", "total", "their", "there", "have", "has", "give", "list", "show", "tell")
  private val HEADER_STOP = setOf("the", "and", "for", "with")

  // columns whose numbers make no sense added up (years, codes, phone numbers, prices)
  private val NOT_AMOUNT = Regex("""year|date|\bid\b|\bno\b|number|ext|code|sku|order|phone|zip|postal|\bage\b|price|rate|avg|average|percent|%|ratio""", RegexOption.IGNORE_CASE)

  fun isSheet(name: String) = name.substringAfterLast('.', "").lowercase() in setOf("xlsx", "csv", "tsv")

  /** The tables in a document: "## Sheet:" sections (a workbook), a whole CSV or TSV file, or Markdown tables. */
  fun tables(docName: String, text: String, partial: Boolean = false): List<Table> {
    val ext = docName.substringAfterLast('.', "").lowercase()
    return when {
      ext == "xlsx" -> sheets(text).mapNotNull { (name, csv) -> table(name, parseCsv(csv, ','), partial) }
      ext == "csv" || ext == "tsv" -> listOfNotNull(table(docName, parseCsv(text, if (ext == "tsv") '\t' else ','), partial))
      else -> markdownTables(docName, text, partial)
    }
  }

  /** What the model reads for a document: small spreadsheets row by row, everything else as it is. */
  fun forModel(docName: String, text: String, partial: Boolean): String {
    if (partial || !isSheet(docName)) return text
    val ts = tables(docName, text)
    if (ts.isEmpty()) return text
    val out = if (docName.endsWith(".xlsx", true)) ts.joinToString("\n\n") { "## Sheet: ${it.name}\n" + records(it) } else records(ts[0])
    return if (out.length <= RECORDS_MAX_CHARS) out else text
  }

  fun records(t: Table) = t.rows.withIndex().joinToString("\n") { (i, r) ->
    "Row ${i + 2}: " + t.header.indices.joinToString("; ") { j -> "${t.header[j]}: ${t.cell(r, j).ifEmpty { EMPTY }}" }
  }

  /** The facts a question about [tables] needs, or null when it names nothing in them. */
  fun hints(tables: List<Table>, question: String): String? {
    if (tables.isEmpty() || question.isBlank()) return null
    val terms = terms(question).map { it to Regex("(^|[^\\p{L}\\p{N}])${Regex.escape(it)}($|[^\\p{L}\\p{N}])") }
    val q = phrase(question)
    val rowsOut = ArrayList<String>()
    var matched = 0
    val counts = ArrayList<String>()
    val cols = ArrayList<String>()
    for (t in tables) {
      val heads = t.header.map { it.lowercase() }.toSet()
      val named = t.header.indices.filter { j -> j > 0 && mentions(question, t.header[j]) }
      // a word in most rows ("Person" in "Person 1" … "Person 300") picks out nothing
      // a cell has the term as a whole word: a plain contains() first, the regex only when that finds it
      fun has(cells: List<String>, term: String, re: Regex) = cells.any { c -> c.contains(term) && re.containsMatchIn(c) }
      val useful = terms.filter { (term, re) -> term !in heads && t.lower.count { has(it, term, re) } <= maxOf(3, t.rows.size / 2) }
      // a whole cell the question names ("Person 7", "M. Okafor") comes first, the rarest first: an order number in one
      // row before a region in thousands
      val hits = t.rows.withIndex().mapNotNull { (i, r) ->
        val rarest = t.phrases[i].filter { p -> p.isNotEmpty() && p in q }.minOfOrNull { t.phraseCount[it] ?: Int.MAX_VALUE }
        if (rarest != null || useful.any { (term, re) -> has(t.lower[i], term, re) }) Triple(i, r, rarest ?: Int.MAX_VALUE) else null
      }.sortedBy { it.third }
      matched += hits.size
      for ((i, r) in hits.take(MAX_ROWS - rowsOut.size)) rowsOut += rowLine(t, r, i + 2, named)
      if (t.partial) continue
      // a value the question names ("West", "Acme Ltd", "Priya"): how many rows have it, and their totals
      for (j in t.header.indices) {
        val named = t.values[j].filter { (v, w) -> v.lowercase() !in heads && listOf(w, w.dropLast(1) + "s ", w.dropLast(1) + "es ").any { it in q } }.map { it.first }
        for (v in named) {
          val group = t.rows.filter { t.cell(it, j) == v }
          if (group.size < 2) continue
          val sums = amountColumns(t, group).filter { it != j }.take(MAX_SUMS).map { k -> "${t.header[k]} adds up to ${num(group.sumOf { number(t.cell(it, k))!! })}" }
          counts += "${group.size} rows of ${t.name} have ${t.header[j]} = $v" + if (sums.isEmpty()) "" else ": " + sums.joinToString(", ")
        }
      }
      for (j in named) cols += column(t, j)
    }
    // counts first: they are short and the model can't work them out itself; then columns, then as many rows as fit
    var room = MAX_CHARS
    fun fit(lines: List<String>) = lines.map { if (it.length > MAX_LINE) it.take(MAX_LINE - 1) + "…" else it }.takeWhile { l -> (room - l.length - 1 >= 0).also { if (it) room -= l.length + 1 } }
    val c = fit(counts)
    val k = fit(cols)
    val r = fit(rowsOut)
    val parts = ArrayList<String>()
    if (r.isNotEmpty()) parts += "Rows that mention your words" + (if (matched > r.size) " (the first ${r.size} of $matched)" else "") + ":\n" + r.joinToString("\n")
    if (c.isNotEmpty()) parts += "Counts:\n" + c.joinToString("\n")
    if (k.isNotEmpty()) parts += "Columns you mention:\n" + k.joinToString("\n")
    if (parts.isEmpty()) return null
    return "<from_the_spreadsheet>\n" + parts.joinToString("\n\n") + "\n</from_the_spreadsheet>"
  }

  /** A wide row shows its label and the columns the question names ([named]), so they aren't cut off. */
  private fun rowLine(t: Table, r: List<String>, n: Int, named: List<Int>): String {
    val shown = if (t.header.size > WIDE_ROW && named.isNotEmpty()) listOf(0) + named else t.header.indices.toList()
    return "[${t.name} row $n] " + shown.joinToString("; ") { j -> "${t.header[j]}: ${t.cell(r, j).ifEmpty { EMPTY }}" } +
      if (shown.size < t.header.size) "; …" else ""
  }

  /** " words like this " for finding a cell's text inside the question as whole words. */
  private val NOT_WORD = Regex("[^\\p{L}\\p{N}@.\\-]+")

  private fun phrase(s: String) = " " + s.lowercase().replace(NOT_WORD, " ").trim().trimEnd('.') + " "

  private fun column(t: Table, j: Int): String {
    val vals = t.rows.map { label(t, it) to t.cell(it, j) }
    val filled = vals.filter { it.second.isNotEmpty() }
    val empty = vals.filter { it.second.isEmpty() }.map { it.first }
    val nums = filled.mapNotNull { (l, v) -> number(v)?.let { l to it } }
    val numeric = nums.isNotEmpty() && nums.size == filled.size
    val s = StringBuilder("[${t.name}, column ${t.header[j]}] ")
    if (numeric) {
      if (vals.size <= LIST_COLUMN_MAX) s.append("values: ").append(filled.joinToString(", ") { "${it.first} ${it.second}" }).append(". ")
    } else {
      val kinds = filled.groupingBy { it.second }.eachCount()
      if (kinds.size <= MAX_GROUPS && kinds.size < filled.size) {
        val amounts = amountColumns(t, t.rows, complete = false).filter { it != j }.take(MAX_SUMS)
        s.append("per value: ").append(kinds.entries.sortedByDescending { it.value }.joinToString("; ") { (v, n) ->
          val g = t.rows.filter { t.cell(it, j) == v }
          val sums = amounts.map { k -> "${t.header[k]} ${num(g.sumOf { number(t.cell(it, k)) ?: 0.0 })}" }
          "$v $n rows" + if (sums.isEmpty()) "" else " (${sums.joinToString(", ")})"
        }).append(". ")
      } else if (vals.size <= LIST_COLUMN_MAX) {
        s.append("values: ").append(filled.joinToString(", ") { "${it.first} ${it.second}" }).append(". ")
      }
    }
    s.append(if (empty.isEmpty()) "No empty cells." else "Empty for: ${empty.joinToString(", ")}.")
    if (numeric) {
      val mx = nums.maxOf { it.second }
      val mn = nums.minOf { it.second }
      s.append(" Highest ${num(mx)} (${nums.filter { it.second == mx }.joinToString(", ") { it.first }}); lowest ${num(mn)} (${nums.filter { it.second == mn }.joinToString(", ") { it.first }})")
      if (!NOT_AMOUNT.containsMatchIn(t.header[j])) s.append("; total ${num(nums.sumOf { it.second })} over ${nums.size} rows")
      s.append('.')
    }
    return s.toString()
  }

  /**
   * Columns of [rows] that hold amounts: every filled cell a number, and a header that isn't a year, code or price.
   * [complete]: every cell filled, so a total doesn't quietly leave a row out.
   */
  private fun amountColumns(t: Table, rows: List<List<String>>, complete: Boolean = true) = t.header.indices.filter { k ->
    !NOT_AMOUNT.containsMatchIn(t.header[k]) && rows.map { t.cell(it, k) }.filter { it.isNotEmpty() }.let { v -> v.isNotEmpty() && v.all { number(it) != null } } &&
      (!complete || rows.all { t.cell(it, k).isNotEmpty() })
  }

  private fun label(t: Table, r: List<String>) = t.cell(r, 0).ifEmpty { "?" }

  /** Does the question name this column? "Tuesday" names "Tue", "lead time" names "Lead time (days)". */
  private fun mentions(question: String, header: String): Boolean {
    if (header.length >= 2 && phrase(header) in phrase(question)) return true // "Q12", "Qty"
    val words = Regex("\\p{L}+").findAll(question).map { it.value.lowercase() }.filter { it.length >= 3 }.toList()
    val hw = Regex("\\p{L}+").findAll(header).map { it.value.lowercase() }.filter { it.length >= 3 && it !in HEADER_STOP }.toList()
    return words.any { w -> hw.any { h -> w.startsWith(h) || h.startsWith(w) } }
  }

  private fun terms(question: String): Set<String> {
    val out = HashSet<String>()
    for (m in Regex("[\\p{L}\\p{N}@.\\-]+").findAll(question)) {
      val w = m.value.trim('.', '-').lowercase()
      if (w.length < 3 || w in STOP) continue
      out += w
      if (w.endsWith("s") && w.length > 3) out += w.dropLast(1)
    }
    return out
  }

  private fun number(s: String) = s.trim().replace(",", "").removePrefix("$").toDoubleOrNull()

  private fun num(d: Double): String {
    val r = Math.round(d * 1e6) / 1e6
    return if (r == Math.rint(r) && kotlin.math.abs(r) < 1e15) r.toLong().toString() else java.math.BigDecimal(r).round(java.math.MathContext(12)).stripTrailingZeros().toPlainString()
  }

  private fun table(name: String, grid: List<List<String>>, partial: Boolean): Table? {
    val rows = grid.filter { r -> r.any { it.isNotBlank() } }
    if (rows.size < 2) return null
    val width = rows.maxOf { it.size }
    val header = (0 until width).map { j -> rows[0].getOrNull(j)?.trim().orEmpty().ifEmpty { "Column ${j + 1}" } }
    return Table(name, header, rows.drop(1).map { r -> r.map { it.trim() } }, partial)
  }

  private fun sheets(text: String): List<Pair<String, String>> {
    val out = ArrayList<Pair<String, String>>()
    var name: String? = null
    val body = StringBuilder()
    for (line in text.lines()) {
      if (line.startsWith("## Sheet: ")) {
        name?.let { out += it to body.toString() }
        name = line.removePrefix("## Sheet: ").trim()
        body.clear()
      } else {
        body.append(line).append('\n')
      }
    }
    name?.let { out += it to body.toString() }
    return out
  }

  private fun parseCsv(text: String, sep: Char): List<List<String>> {
    val rows = ArrayList<List<String>>()
    var row = ArrayList<String>()
    val cell = StringBuilder()
    var quoted = false
    var i = 0
    while (i < text.length) {
      val c = text[i]
      when {
        quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
        c == '"' && (quoted || cell.isEmpty()) -> quoted = !quoted
        quoted -> cell.append(c)
        c == sep -> { row += cell.toString(); cell.clear() }
        c == '\n' || c == '\r' -> {
          if (c == '\r' && text.getOrNull(i + 1) == '\n') i++
          row += cell.toString(); cell.clear()
          rows += row; row = ArrayList()
        }
        else -> cell.append(c)
      }
      i++
    }
    if (cell.isNotEmpty() || row.isNotEmpty()) { row += cell.toString(); rows += row }
    return rows
  }

  /** "| a | b |" tables with a "|---|" line under the header, as PDF tables and OCR'd tables are written. */
  private fun markdownTables(docName: String, text: String, partial: Boolean): List<Table> {
    val out = ArrayList<Table>()
    val lines = text.lines()
    var i = 0
    while (i < lines.size) {
      if (i + 1 < lines.size && lines[i].trimStart().startsWith("|") && Regex("^\\s*\\|[\\s:|-]+\\|\\s*$").matches(lines[i + 1]) && '-' in lines[i + 1]) {
        val block = ArrayList<List<String>>()
        block += mdCells(lines[i])
        var k = i + 2
        while (k < lines.size && lines[k].trimStart().startsWith("|")) block += mdCells(lines[k++])
        table("$docName table ${out.size + 1}", block, partial)?.let { out += it }
        i = k
      } else {
        i++
      }
    }
    return out
  }

  private fun mdCells(line: String) = line.trim().removePrefix("|").removeSuffix("|").split(Regex("(?<!\\\\)\\|")).map { it.trim().replace("\\|", "|") }
}
