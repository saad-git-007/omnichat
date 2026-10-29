package com.saad.omnichat.media

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.util.Xml
import com.saad.omnichat.llm.Media
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.util.zip.ZipInputStream
import org.xmlpull.v1.XmlPullParser

/**
 * Turns a PDF, Excel workbook (.xlsx), CSV, Markdown or plain-text file into text for the prompt. The model reads it as
 * part of the message, so the text is capped to leave room in the context for the question and the answer.
 */
/** Reads the text in a picture of part of a PDF (bitmap, what it is: "scanned page 2 of 9"); null = can't. */
typealias PageReader = suspend (Bitmap, String) -> String?

object DocumentReader {
  private const val SCANNED_BELOW = 20 // letters and digits on a page with a real text layer
  private const val PAGE_MAX_SIDE = 1600f
  private const val WIDE_GAP = 2.5f // in spaces: a gap this wide between words is a gap between columns
  private const val PICTURE_MIN_SHARE = 0.04f // of the page area: smaller pictures are icons and logos
  private const val ALREADY_READ = 15 // letters of the text layer over a picture
  private const val MAX_PICTURES = 20 // each takes a few seconds per 448 px slice
  private val TEXT_EXT = setOf("txt", "md", "markdown", "csv", "tsv", "json", "log", "xml", "html", "htm", "yaml", "yml")

  fun isDocument(mime: String, name: String): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase()
    return mime == "application/pdf" || mime.startsWith("text/") || mime == XLSX || ext == "pdf" || ext == "xlsx" || ext in TEXT_EXT
  }

  /**
   * [maxChars]: about 4 characters per token. [ocr] reads PDF pages that have no text layer (scans), and pictures on the
   * other pages. [onPictures]: how many scanned pages and pictures there are to read.
   */
  suspend fun read(context: Context, uri: Uri, mime: String, name: String, maxChars: Int, ocr: PageReader? = null, onPictures: (Int, Int) -> Unit = { _, _ -> }): Media.Document {
    val ext = name.substringAfterLast('.', "").lowercase()
    val text =
      when {
        mime == "application/pdf" || ext == "pdf" -> pdf(context, uri, ocr, onPictures)
        mime == XLSX || ext == "xlsx" -> xlsx(context.contentResolver, uri)
        else -> context.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
      }.trim()
    require(text.isNotEmpty()) { "No text found in $name" + if (ext == "pdf") " (scanned PDFs have no text; attach a photo of the page instead)" else "" }
    return if (text.length <= maxChars) Media.Document(name, text) else Media.Document(name, text.take(maxChars), truncated = true)
  }

  private suspend fun pdf(context: Context, uri: Uri, ocr: PageReader?, onPictures: (Int, Int) -> Unit): String {
    PDFBoxResourceLoader.init(context.applicationContext)
    val pages = ArrayList<String>()
    val pictures = ArrayList<Picture>()
    context.contentResolver.openInputStream(uri)!!.use { input ->
      PDDocument.load(input).use { doc ->
        val stripper = LayoutStripper()
        val seen = HashSet<Any>()
        for (n in 1..doc.numberOfPages) {
          pages += PdfLayout.render(stripper.page(doc, n))
          if (pages.last().count(Char::isLetterOrDigit) >= SCANNED_BELOW) pictures += PictureFinder.find(doc.getPage(n - 1), n - 1, stripper.letters, seen)
        }
      }
    }
    // a page with (almost) no text layer is a scan or a photo of text
    val scanned = pages.indices.filter { pages[it].count(Char::isLetterOrDigit) < SCANNED_BELOW }
    onPictures(scanned.size, pictures.size)
    if (ocr != null && (scanned.isNotEmpty() || pictures.isNotEmpty())) {
      context.contentResolver.openFileDescriptor(uri, "r")!!.use { fd ->
        PdfRenderer(fd).use { pdf ->
          for (i in scanned) {
            val read = pdf.openPage(i).use { page -> readArea(page, 0f, 0f, page.width.toFloat(), page.height.toFloat(), ocr, "scanned page ${i + 1} of ${pages.size}") }
            if (!read.isNullOrBlank()) pages[i] = (pages[i] + "\n" + read).trim()
          }
          for ((k, pic) in pictures.take(MAX_PICTURES).withIndex()) {
            val what = "picture ${k + 1} of ${minOf(pictures.size, MAX_PICTURES)} (page ${pic.page + 1})"
            val read = pdf.openPage(pic.page).use { page -> readArea(page, pic.left, pic.top, pic.width, pic.height, ocr, what) }
            // a photo or a drawing with no words in it comes back empty or as a few stray characters
            if (read != null && read.count(Char::isLetterOrDigit) >= 3) pages[pic.page] += "\n\n[text in a picture on this page]\n${read.trim()}"
          }
        }
      }
    }
    return pages.withIndex().joinToString("") { (i, t) -> "--- page ${i + 1} ---\n$t\n\n" }
  }

  /** Renders part of a page (points, from its top left) at ~150 dpi and OCRs it. */
  private suspend fun readArea(page: PdfRenderer.Page, left: Float, top: Float, width: Float, height: Float, ocr: PageReader, what: String): String? {
    // small print stays readable, and the OCR model cuts the picture into fewer 448 px slices (each takes seconds)
    val scale = minOf(150f / 72f, PAGE_MAX_SIDE / maxOf(width, height))
    val bmp = Bitmap.createBitmap((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    return try {
      bmp.eraseColor(Color.WHITE)
      page.render(bmp, null, Matrix().apply { setTranslate(-left, -top); postScale(scale, scale) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
      ocr(bmp, what)
    } finally {
      bmp.recycle()
    }
  }

  /** A picture drawn on a page that has a text layer: a pasted screenshot, a scanned table, a chart. Points, from the top left. */
  private class Picture(val page: Int, val left: Float, val top: Float, val width: Float, val height: Float)

  /**
   * PDFBox's text extraction (which handles ligatures, accents and right-to-left text), but each word keeps where it is,
   * so [PdfLayout] can tell table columns apart. Also keeps where each letter is, so a picture whose words are already in
   * the text layer (a scan with an OCR layer, a chart with real labels) isn't read twice.
   */
  private class LayoutStripper : PDFTextStripper() {
    private val lines = ArrayList<PdfLayout.Line>()
    private var segs = ArrayList<PdfLayout.Seg>()
    private var para = false

    /** x, y pairs of the letters and digits on the page */
    val letters = ArrayList<Float>()

    init {
      sortByPosition = true
    }

    fun page(doc: PDDocument, n: Int): List<PdfLayout.Line> {
      lines.clear()
      letters.clear()
      startPage = n
      endPage = n
      getText(doc)
      endLine()
      return lines.toList()
    }

    override fun writeString(text: String, positions: List<TextPosition>) {
      if (positions.isEmpty()) return
      val p0 = positions[0]
      val space = positions.map { it.widthOfSpace }.filter { it > 0f && it.isFinite() }.average().toFloat().takeIf { it.isFinite() } ?: (p0.heightDir * 0.35f)
      val wide = maxOf(space * WIDE_GAP, 1f)
      for (p in positions) if (p.unicode?.any(Char::isLetterOrDigit) == true) {
        letters += p.xDirAdj + p.widthDirAdj / 2
        letters += p.yDirAdj - p.heightDir / 2
      }
      // a "word" can hold a run of space characters that is really a column gap
      val pieces = ArrayList<PdfLayout.Seg>()
      if (text.length == positions.size) {
        val cur = StringBuilder()
        var x0 = 0f
        var x1 = 0f
        for ((i, p) in positions.withIndex()) {
          val ch = text[i]
          if (ch.isWhitespace()) {
            if (cur.isNotEmpty()) cur.append(ch)
            continue
          }
          if (cur.isNotEmpty() && p.xDirAdj - x1 > wide) {
            pieces += PdfLayout.Seg(x0, x1, cur.toString().trim())
            cur.clear()
          }
          if (cur.isEmpty()) x0 = p.xDirAdj
          cur.append(ch)
          x1 = maxOf(x1, p.xDirAdj + p.widthDirAdj)
        }
        if (cur.isNotEmpty()) pieces += PdfLayout.Seg(x0, x1, cur.toString().trim())
      }
      if (pieces.isEmpty()) {
        val x0 = positions.minOf { it.xDirAdj }
        pieces += PdfLayout.Seg(x0, positions.maxOf { it.xDirAdj + it.widthDirAdj }, text.trim())
      }
      for ((i, piece) in pieces.withIndex()) {
        val last = segs.lastOrNull()
        // right-to-left lines come in reading order, so the gap can be on either side
        val gap = if (last == null) 0f else maxOf(piece.x0 - last.x1, last.x0 - piece.x1)
        if (last != null && i == 0 && gap <= wide) segs[segs.lastIndex] = PdfLayout.Seg(minOf(last.x0, piece.x0), maxOf(last.x1, piece.x1), last.text + " " + piece.text)
        else if (piece.text.isNotEmpty()) segs += piece
      }
    }

    override fun writeLineSeparator() = endLine()

    override fun writeParagraphEnd() {
      endLine()
      para = true
    }

    override fun writePageEnd() = endLine()

    private fun endLine() {
      if (segs.isEmpty()) return
      lines += PdfLayout.Line(segs, para)
      segs = ArrayList()
      para = false
    }
  }

  /** Finds the pictures worth reading on a page: big enough to hold text, not already in the text layer, not seen before. */
  private class PictureFinder(page: PDPage) : PDFGraphicsStreamEngine(page) {
    private val boxes = ArrayList<Pair<Any, RectF>>()

    override fun drawImage(pdImage: PDImage) {
      // inline images are small (icons, bullets); only image objects can hold a screenshot
      val obj = (pdImage as? PDImageXObject)?.cosObject ?: return
      val m = graphicsState.currentTransformationMatrix
      val corners = listOf(m.transformPoint(0f, 0f), m.transformPoint(1f, 0f), m.transformPoint(0f, 1f), m.transformPoint(1f, 1f))
      boxes += obj to RectF(corners.minOf { it.x }, corners.minOf { it.y }, corners.maxOf { it.x }, corners.maxOf { it.y })
    }

    override fun appendRectangle(p0: PointF, p1: PointF, p2: PointF, p3: PointF) {}
    override fun clip(windingRule: Path.FillType) {}
    override fun moveTo(x: Float, y: Float) {}
    override fun lineTo(x: Float, y: Float) {}
    override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {}
    override fun getCurrentPoint() = PointF()
    override fun closePath() {}
    override fun endPath() {}
    override fun strokePath() {}
    override fun fillPath(windingRule: Path.FillType) {}
    override fun fillAndStrokePath(windingRule: Path.FillType) {}
    override fun shadingFill(shadingName: COSName) {}

    companion object {
      fun find(page: PDPage, index: Int, letters: List<Float>, seen: MutableSet<Any>): List<Picture> {
        // positions of turned pages don't match the rendered page; rare enough to skip
        if (page.rotation % 360 != 0) return emptyList()
        val finder = PictureFinder(page)
        runCatching { finder.processPage(page) }.onFailure { return emptyList() }
        val crop = page.cropBox
        val out = ArrayList<Picture>()
        for ((obj, b) in finder.boxes) {
          val left = maxOf(0f, b.left - crop.lowerLeftX)
          val top = maxOf(0f, crop.upperRightY - b.bottom)
          val width = minOf(crop.width, b.right - crop.lowerLeftX) - left
          val height = minOf(crop.height, crop.upperRightY - b.top) - top
          if (width < 72f || height < 36f || width * height < PICTURE_MIN_SHARE * crop.width * crop.height) continue
          // a letterhead or logo repeated on every page: read once
          if (!seen.add(obj)) continue
          var inside = 0
          for (i in letters.indices step 2) if (letters[i] in left..left + width && letters[i + 1] in top..top + height) inside++
          if (inside >= ALREADY_READ) continue
          out += Picture(index, left, top, width, height)
        }
        return out
      }
    }
  }

  /** .xlsx is a zip of XML parts: shared strings, the workbook's sheet list and one XML file per sheet. Output is CSV per sheet. */
  private fun xlsx(resolver: ContentResolver, uri: Uri): String {
    val parts = HashMap<String, ByteArray>()
    resolver.openInputStream(uri)!!.use { raw ->
      ZipInputStream(raw).use { zip ->
        while (true) {
          val e = zip.nextEntry ?: break
          val name = e.name.removePrefix("/")
          if (name == "xl/sharedStrings.xml" || name == "xl/workbook.xml" || name == "xl/_rels/workbook.xml.rels" || name.startsWith("xl/worksheets/")) parts[name] = zip.readBytes()
        }
      }
    }
    val shared = parts["xl/sharedStrings.xml"]?.let(::sharedStrings) ?: emptyList()
    // the workbook lists sheets in tab order, each pointing to its file through the relationships part; the file
    // numbers keep the order sheets were created in, so after a reorder sheet2.xml can be the first tab
    val targets = parts["xl/_rels/workbook.xml.rels"]?.let(::relationships) ?: emptyMap()
    val listed = parts["xl/workbook.xml"]?.let(::sheets).orEmpty().mapNotNull { (name, rid) ->
      val t = targets[rid] ?: return@mapNotNull null
      val path = if (t.startsWith("/")) t.removePrefix("/") else "xl/" + t.removePrefix("./")
      parts[path]?.let { name to it }
    }
    val sheets = listed.ifEmpty {
      parts.keys.filter { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }
        .sortedBy { it.removePrefix("xl/worksheets/sheet").removeSuffix(".xml").toIntOrNull() ?: 0 }
        .mapIndexed { i, key -> "Sheet${i + 1}" to parts[key]!! }
    }
    require(sheets.isNotEmpty()) { "No sheets found in the workbook" }
    return sheets.joinToString("\n\n") { (name, xml) -> "## Sheet: $name\n" + sheetCsv(xml, shared) }
  }

  private fun parser(bytes: ByteArray) = Xml.newPullParser().apply { setInput(bytes.inputStream(), "UTF-8") }

  private fun sharedStrings(bytes: ByteArray): List<String> {
    val out = ArrayList<String>()
    val p = parser(bytes)
    val cur = StringBuilder()
    var inT = false
    while (p.next() != XmlPullParser.END_DOCUMENT) {
      when (p.eventType) {
        XmlPullParser.START_TAG -> when (p.name) { "si" -> cur.clear(); "t" -> inT = true }
        XmlPullParser.TEXT -> if (inT) cur.append(p.text)
        XmlPullParser.END_TAG -> when (p.name) { "t" -> inT = false; "si" -> out += cur.toString() }
      }
    }
    return out
  }

  /** (name, relationship id) of each sheet, in tab order */
  private fun sheets(bytes: ByteArray): List<Pair<String, String>> {
    val out = ArrayList<Pair<String, String>>()
    val p = parser(bytes)
    while (p.next() != XmlPullParser.END_DOCUMENT) {
      if (p.eventType != XmlPullParser.START_TAG || p.name != "sheet") continue
      val rid = (0 until p.attributeCount).firstOrNull { p.getAttributeName(it) == "id" || p.getAttributeName(it).endsWith(":id") }?.let(p::getAttributeValue) ?: continue
      out += (p.getAttributeValue(null, "name") ?: "Sheet${out.size + 1}") to rid
    }
    return out
  }

  /** relationship id -> target path */
  private fun relationships(bytes: ByteArray): Map<String, String> {
    val out = HashMap<String, String>()
    val p = parser(bytes)
    while (p.next() != XmlPullParser.END_DOCUMENT) {
      if (p.eventType == XmlPullParser.START_TAG && p.name == "Relationship") {
        val id = p.getAttributeValue(null, "Id") ?: continue
        out[id] = p.getAttributeValue(null, "Target") ?: continue
      }
    }
    return out
  }

  private fun sheetCsv(bytes: ByteArray, shared: List<String>): String {
    val rows = StringBuilder()
    val p = parser(bytes)
    var row = ArrayList<String>()
    var col = 0
    var type: String? = null
    var value = StringBuilder()
    var inValue = false
    while (p.next() != XmlPullParser.END_DOCUMENT) {
      when (p.eventType) {
        XmlPullParser.START_TAG -> when (p.name) {
          "row" -> row = ArrayList()
          "c" -> {
            type = p.getAttributeValue(null, "t")
            col = p.getAttributeValue(null, "r")?.let(::columnIndex) ?: row.size
            value = StringBuilder()
          }
          "v", "t" -> inValue = true
        }
        XmlPullParser.TEXT -> if (inValue) value.append(p.text)
        XmlPullParser.END_TAG -> when (p.name) {
          "v", "t" -> inValue = false
          "c" -> {
            val raw = value.toString()
            val cell = when (type) {
              "s" -> raw.toIntOrNull()?.let { shared.getOrNull(it) } ?: raw
              "str", "inlineStr", "b", "e" -> raw
              else -> tidyNumber(raw)
            }
            while (row.size < col) row += ""
            row += cell
          }
          "row" -> {
            // empty styled cells at the end of a row are just commas to the model
            while (row.isNotEmpty() && row.last().isBlank()) row.removeAt(row.lastIndex)
            if (row.isNotEmpty()) rows.append(row.joinToString(",") { csvField(it) }).append('\n')
          }
        }
      }
    }
    return rows.toString()
  }

  /** Excel stores 0.1+0.2 as 0.30000000000000004: 10 significant digits are plenty and cost far fewer tokens. */
  private fun tidyNumber(raw: String): String {
    val d = raw.toDoubleOrNull() ?: return raw
    if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) return d.toLong().toString()
    return java.math.BigDecimal(d).round(java.math.MathContext(10)).stripTrailingZeros().toPlainString()
  }

  /** "C12" -> 2 */
  private fun columnIndex(ref: String): Int {
    var n = 0
    for (ch in ref) {
      if (!ch.isLetter()) break
      n = n * 26 + (ch.uppercaseChar() - 'A' + 1)
    }
    return n - 1
  }

  private fun csvField(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

  const val XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
}
