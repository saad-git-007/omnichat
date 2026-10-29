package com.saad.omnichat.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saad.omnichat.ui.theme.veil

private sealed interface Block {
  data class Para(val text: String) : Block

  data class Heading(val level: Int, val text: String) : Block

  data class Item(val marker: String, val indent: Int, val text: String) : Block

  data class Code(val text: String) : Block
}

private val bulletRe = Regex("""^(\s*)([-*+])\s+(.*)$""")
private val numberRe = Regex("""^(\s*)(\d{1,3})[.)]\s+(.*)$""")
private val headingRe = Regex("""^(#{1,6})\s+(.*)$""")

private fun parse(src: String): List<Block> {
  val out = ArrayList<Block>()
  val para = StringBuilder()
  fun flush() {
    if (para.isNotBlank()) out += Block.Para(para.toString().trim())
    para.clear()
  }
  val lines = src.lines()
  var i = 0
  while (i < lines.size) {
    val line = lines[i]
    when {
      line.trimStart().startsWith("```") -> {
        flush()
        val code = StringBuilder()
        i++
        while (i < lines.size && !lines[i].trimStart().startsWith("```")) code.appendLine(lines[i++])
        out += Block.Code(code.toString().trimEnd())
      }
      line.isBlank() -> flush()
      headingRe.matches(line) -> {
        flush()
        val m = headingRe.find(line)!!
        out += Block.Heading(m.groupValues[1].length, m.groupValues[2])
      }
      bulletRe.matches(line) -> {
        flush()
        val m = bulletRe.find(line)!!
        out += Block.Item("•", m.groupValues[1].length / 2, m.groupValues[3])
      }
      numberRe.matches(line) -> {
        flush()
        val m = numberRe.find(line)!!
        out += Block.Item(m.groupValues[2] + ".", m.groupValues[1].length / 2, m.groupValues[3])
      }
      else -> para.append(if (para.isEmpty()) line else "\n$line")
    }
    i++
  }
  flush()
  return out
}

/** Inline **bold**, *italic* / _italic_ and `code`. Unclosed markers stay literal, so streaming text never jumps. */
private fun inline(src: String, codeBg: Color): AnnotatedString = buildAnnotatedString {
  var i = 0
  while (i < src.length) {
    fun closeAt(token: String, from: Int) = src.indexOf(token, from).takeIf { it > from }
    when {
      src.startsWith("**", i) && closeAt("**", i + 2) != null -> {
        val end = closeAt("**", i + 2)!!
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(inline(src.substring(i + 2, end), codeBg)) }
        i = end + 2
      }
      src[i] == '`' && closeAt("`", i + 1) != null -> {
        val end = closeAt("`", i + 1)!!
        withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg, fontSize = 14.sp)) { append(src.substring(i + 1, end)) }
        i = end + 1
      }
      (src[i] == '*' || src[i] == '_') && i + 1 < src.length && !src[i + 1].isWhitespace() &&
        (i == 0 || !src[i - 1].isLetterOrDigit()) && closeAt(src[i].toString(), i + 1) != null -> {
        val end = closeAt(src[i].toString(), i + 1)!!
        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(src.substring(i + 1, end)) }
        i = end + 1
      }
      else -> append(src[i++])
    }
  }
}

@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
  val blocks = remember(text) { parse(text) }
  val codeBg = veil(1.6f)
  val body = MaterialTheme.typography.bodyLarge
  // while a reply streams, only its last block changes: keep the styled text of the others instead of rebuilding
  // every block on every token
  @Composable
  fun styled(src: String) = remember(src, codeBg) { inline(src, codeBg) }
  Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
    for (b in blocks) {
      when (b) {
        is Block.Para -> Text(styled(b.text), style = body)
        is Block.Heading ->
          Text(
            styled(b.text),
            style = if (b.level <= 2) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 6.dp),
          )
        is Block.Item ->
          Row(Modifier.padding(start = (b.indent * 16).dp)) {
            Text(
              b.marker,
              style = body,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.width(if (b.marker == "•") 18.dp else 26.dp),
            )
            Text(styled(b.text), style = body)
          }
        is Block.Code ->
          Text(
            b.text,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, lineHeight = 20.sp),
            softWrap = false,
            modifier =
              Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(codeBg).horizontalScroll(rememberScrollState()).padding(12.dp),
          )
      }
    }
  }
}
