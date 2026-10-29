package com.saad.omnichat.ui.stats

import com.saad.omnichat.ui.theme.accentText
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saad.omnichat.stats.BackendKind
import com.saad.omnichat.stats.RunRecord
import com.saad.omnichat.ui.chat.formatMb
import com.saad.omnichat.ui.theme.LocalOmni
import com.saad.omnichat.ui.theme.Numeric
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow
import kotlinx.coroutines.launch
import com.saad.omnichat.ui.theme.glass
import com.saad.omnichat.ui.theme.veil

private enum class Metric(val label: String, val unit: String, val hint: String, val value: (RunRecord) -> Float) {
  GEN("Generation", "tok/s", "How fast the reply is written. Higher is better.", { it.genTps }),
  PROMPT("Prompt", "tok/s", "How fast your message and attachments are read. Higher is better.", { it.prefillTps }),
  TTFT("First token", "s", "Wait from Send to the first word. Lower is better.", { it.ttftMs / 1000f }),
  RAM("Peak RAM", "GB", "Most memory the app used during the run. Lower is better.", { it.ramPeakMb / 1024f }),
}

@Composable
fun backendColor(b: BackendKind): Color {
  val s = LocalOmni.current.series
  return when (b) {
    BackendKind.NPU -> s.npu
    BackendKind.OPENCL -> s.gpuOpenCl
    BackendKind.VULKAN -> s.gpuVulkan
    BackendKind.CPU -> s.cpu
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(runs: List<RunRecord>, onBack: () -> Unit, onClear: () -> Unit, onExport: suspend () -> String) {
  val snackbar = remember { SnackbarHostState() }
  val scope = rememberCoroutineScope()
  var confirmClear by remember { mutableStateOf(false) }

  Scaffold(
    containerColor = Color.Transparent,
    snackbarHost = { SnackbarHost(snackbar) },
    topBar = {
      TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent),
        title = { Text("Performance") },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to chat") } },
        actions = {
          IconButton(
            enabled = runs.isNotEmpty(),
            onClick = {
              scope.launch {
                val r = onExport()
                snackbar.showSnackbar(if (r.startsWith("!")) "Export failed: ${r.drop(1)}" else "Saved $r to Downloads")
              }
            },
          ) {
            Icon(Icons.Outlined.FileDownload, "Export CSV")
          }
          IconButton(enabled = runs.isNotEmpty(), onClick = { confirmClear = true }) { Icon(Icons.Outlined.DeleteSweep, "Clear history") }
        },
      )
    },
  ) { pad ->
    if (runs.isEmpty()) {
      Box(Modifier.padding(pad).fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
          "No runs yet. Send a message and its speed, timing and memory use are recorded here, on this phone only.",
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
        )
      }
    } else {
      LazyColumn(
        Modifier.padding(pad).fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
      ) {
        item { SectionTitle("By backend", "Averages over ${runs.size} ${if (runs.size == 1) "run" else "runs"}. Speeds in tokens per second.") }
        item { BackendTable(runs) }
        item { SectionTitle("Charts", "Pick a measure; both charts use the same scale starting at zero.") }
        item { ChartBlock(runs) }
        item { SectionTitle("Runs", "${runs.size} recorded, newest first") }
        items(runs.asReversed(), key = { it.timestamp }) { r ->
          RunRow(r)
          HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
        }
      }
    }
  }

  if (confirmClear) {
    AlertDialog(
      onDismissRequest = { confirmClear = false },
      title = { Text("Clear all runs?") },
      text = { Text("This deletes ${runs.size} recorded runs from this phone. Export a CSV first if you want to keep them.") },
      confirmButton = {
        TextButton(onClick = { confirmClear = false; onClear() }) { Text("Clear", color = MaterialTheme.colorScheme.error) }
      },
      dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
  }
}

@Composable
private fun SectionTitle(title: String, sub: String) {
  Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 10.dp)) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Composable
private fun BackendTable(runs: List<RunRecord>) {
  val groups = BackendKind.entries.mapNotNull { b -> runs.filter { it.backend == b }.takeIf { it.isNotEmpty() }?.let { b to it } }
  val num = MaterialTheme.typography.bodyMedium.merge(Numeric)
  val head = MaterialTheme.typography.labelMedium
  val weights = listOf(1.1f, 0.6f, 1.15f, 0.9f, 1.05f)
  Column(
    Modifier.padding(horizontal = 16.dp).glass(RoundedCornerShape(20.dp), lift = 8.dp).padding(vertical = 6.dp)
  ) {
    Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
      listOf("Backend", "Runs", "Generation", "Prompt", "Peak RAM").forEachIndexed { i, h ->
        Text(
          h,
          style = head,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = if (i == 0) TextAlign.Start else TextAlign.End,
          modifier = Modifier.weight(weights[i]),
        )
      }
    }
    groups.forEach { (b, rs) ->
      HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = MaterialTheme.colorScheme.outlineVariant)
      Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(weights[0]), verticalAlignment = Alignment.CenterVertically) {
          Box(Modifier.size(10.dp).clip(CircleShape).background(backendColor(b)))
          Spacer(Modifier.width(8.dp))
          Text(b.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
        val cells =
          listOf(
            "${rs.size}",
            "%.1f".format(rs.map { it.genTps }.average()),
            "%.0f".format(rs.map { it.prefillTps }.average()),
            formatMb(rs.maxOf { it.ramPeakMb }),
          )
        cells.forEachIndexed { i, c -> Text(c, style = num, textAlign = TextAlign.End, modifier = Modifier.weight(weights[i + 1])) }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChartBlock(runs: List<RunRecord>) {
  var metric by rememberSaveable { mutableStateOf(Metric.GEN) }
  Column(Modifier.padding(horizontal = 16.dp)) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
      Metric.entries.forEachIndexed { i, m ->
        SegmentedButton(
          selected = metric == m,
          onClick = { metric = m },
          shape = SegmentedButtonDefaults.itemShape(i, Metric.entries.size),
          icon = {},
          label = { Text(m.label, maxLines = 1, style = MaterialTheme.typography.labelMedium) },
        )
      }
    }
    Text(
      metric.hint,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
    )

    Text("Backends compared", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
    CompareBars(runs, metric)

    val shown = runs.takeLast(MAX_COLUMNS)
    Text(
      if (runs.size > shown.size) "Last ${shown.size} prompts, oldest to newest" else "Each prompt, oldest to newest",
      style = MaterialTheme.typography.labelLarge,
      modifier = Modifier.padding(top = 20.dp, bottom = 2.dp),
    )
    Text("Tap a column for details.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
    if (shown.map { it.backend }.distinct().size > 1) Legend(shown.map { it.backend }.distinct())
    ColumnChart(shown, runs.size - shown.size, metric)
  }
}

private const val MAX_COLUMNS = 40

@Composable
private fun Legend(backends: List<BackendKind>) {
  Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(bottom = 6.dp)) {
    BackendKind.entries.filter { it in backends }.forEach { b ->
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(backendColor(b)))
        Spacer(Modifier.width(6.dp))
        Text(b.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
  }
}

/** 0-based axis from the 1-2-5 series: about four steps cover [max]. Returns (step, top). */
private fun niceAxis(max: Float): Pair<Float, Float> {
  val m = if (max <= 0f) 1f else max
  val raw = m / 4f
  val mag = 10f.pow(kotlin.math.floor(log10(raw)))
  val n = raw / mag
  val step = mag * when { n <= 1f -> 1f; n <= 2f -> 2f; n <= 5f -> 5f; else -> 10f }
  return step to step * kotlin.math.ceil(m / step).coerceAtLeast(1f)
}

private fun formatValue(metric: Metric, v: Float) =
  when (metric) {
    Metric.GEN -> "%.1f".format(v)
    Metric.PROMPT -> if (v >= 100) "%.0f".format(v) else "%.1f".format(v)
    Metric.TTFT -> if (v >= 10) "%.1f".format(v) else "%.2f".format(v)
    Metric.RAM -> "%.2f".format(v)
  }

private fun formatTick(step: Float, v: Float) = if (step >= 1f) "%.0f".format(v) else "%.${kotlin.math.ceil(-log10(step)).toInt()}f".format(v)

/**
 * One horizontal bar per backend on a shared 0-based scale: the bar is the average, the thin whisker spans the
 * slowest to the fastest run, and the average is written at the bar's end so nothing depends on colour alone.
 */
@Composable
private fun CompareBars(runs: List<RunRecord>, metric: Metric) {
  val groups = BackendKind.entries.mapNotNull { b -> runs.filter { it.backend == b }.takeIf { it.isNotEmpty() }?.let { b to it.map(metric.value) } }
  // headroom past the fastest run so every value label fits beside its bar
  val (step, top) = niceAxis(groups.maxOf { (_, v) -> v.max() } * 1.3f)
  val ink = MaterialTheme.colorScheme.onSurface
  val muted = MaterialTheme.colorScheme.onSurfaceVariant
  val grid = MaterialTheme.colorScheme.outlineVariant
  val measurer = rememberTextMeasurer()
  val valueStyle = TextStyle(fontSize = 13.sp, color = ink, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum")
  val axisStyle = TextStyle(fontSize = 11.sp, color = muted, fontFeatureSettings = "tnum")
  val colors = BackendKind.entries.associateWith { backendColor(it) }
  val rowH = 34.dp
  val labelW = 64.dp
  // bars grow from zero once when the page opens (and on a metric change); a short ease-out, no bounce
  val reduce = LocalOmni.current.reduceMotion
  val grow = remember(metric) { androidx.compose.animation.core.Animatable(if (reduce) 1f else 0f) }
  LaunchedEffect(metric) { grow.animateTo(1f, tween(450, easing = com.saad.omnichat.ui.chat.ExpoOut)) }

  Column {
    groups.forEach { (b, values) ->
      Row(Modifier.fillMaxWidth().height(rowH), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(labelW)) {
          Text(b.label, style = MaterialTheme.typography.labelLarge)
          Text("${values.size} ${if (values.size == 1) "run" else "runs"}", style = MaterialTheme.typography.labelSmall, color = muted)
        }
        Canvas(Modifier.weight(1f).height(rowH).semantics {
          contentDescription = "${b.label}: average ${formatValue(metric, values.average().toFloat())} ${metric.unit}, from ${formatValue(metric, values.min())} to ${formatValue(metric, values.max())}"
        }) {
          val w = size.width - 4.dp.toPx()
          fun x(v: Float) = w * (v / top)
          val cy = size.height / 2f
          val barH = 14.dp.toPx()
          val avg = values.average().toFloat()
          // whisker: slowest to fastest run
          if (values.size > 1) {
            drawLine(ink.copy(alpha = 0.45f), Offset(x(values.min()), cy), Offset(x(values.max()), cy), strokeWidth = 1.5.dp.toPx())
            listOf(values.min(), values.max()).forEach { v ->
              drawLine(ink.copy(alpha = 0.45f), Offset(x(v), cy - 6.dp.toPx()), Offset(x(v), cy + 6.dp.toPx()), strokeWidth = 1.5.dp.toPx())
            }
          }
          val barW = (x(avg) * grow.value).coerceAtLeast(2.dp.toPx())
          drawRoundRect(colors.getValue(b), Offset(0f, cy - barH / 2f), androidx.compose.ui.geometry.Size(barW, barH), androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
          val label = measurer.measure("${formatValue(metric, avg)} ${metric.unit}", valueStyle)
          val end = maxOf(barW, if (values.size > 1) x(values.max()) else 0f)
          drawText(label, topLeft = Offset((end + 8.dp.toPx()).coerceAtMost(size.width - label.size.width), cy - label.size.height / 2f))
        }
      }
    }
    // shared axis under all bars
    Canvas(Modifier.fillMaxWidth().height(22.dp).padding(start = labelW)) {
      val w = size.width - 4.dp.toPx()
      var v = 0f
      while (v <= top + step / 2) {
        val xx = w * (v / top)
        drawLine(grid, Offset(xx, 0f), Offset(xx, 4.dp.toPx()), strokeWidth = 1.dp.toPx())
        val t = measurer.measure(formatTick(step, v), axisStyle)
        drawText(t, topLeft = Offset((xx - t.size.width / 2f).coerceIn(0f, size.width - t.size.width), 6.dp.toPx()))
        v += step
      }
    }
    Text("Bar: average · whisker: slowest to fastest run · ${metric.unit}", style = MaterialTheme.typography.labelSmall, color = muted, modifier = Modifier.padding(start = labelW, top = 2.dp))
  }
}

/** Columns in run order on a 0-based axis with the unit on top; colour = backend, tap shows the run. */
@Composable
private fun ColumnChart(runs: List<RunRecord>, offset: Int, metric: Metric) {
  val measurer = rememberTextMeasurer()
  val muted = MaterialTheme.colorScheme.onSurfaceVariant
  val axisStyle = TextStyle(fontSize = 11.sp, color = muted, fontFeatureSettings = "tnum")
  val grid = MaterialTheme.colorScheme.outlineVariant
  val ink = MaterialTheme.colorScheme.onSurface
  val tipBg = MaterialTheme.colorScheme.inverseSurface
  val tipInk = MaterialTheme.colorScheme.inverseOnSurface
  val colors = BackendKind.entries.associateWith { backendColor(it) }
  var selected by remember(metric, runs.size) { mutableIntStateOf(-1) }

  val values = runs.map(metric.value)
  val (step, top) = niceAxis(values.maxOrNull() ?: 1f)
  val leftPad = 40.dp
  val n = runs.size

  Column {
    Canvas(
      Modifier.fillMaxWidth().height(230.dp).semantics {
        contentDescription = "${metric.label} for each of the last $n prompts. The run list below has every value."
      }.pointerInput(runs, metric) {
        detectTapGestures { p ->
          val left = leftPad.toPx()
          val slot = (size.width - left) / n
          val idx = ((p.x - left) / slot).toInt()
          selected = if (idx !in 0 until n || selected == idx) -1 else idx
        }
      }
    ) {
      val left = leftPad.toPx()
      val topY = 22.dp.toPx()
      val bottom = size.height - 22.dp.toPx()
      fun y(v: Float) = bottom - (bottom - topY) * (v / top)

      // unit as the axis title, above the ticks
      drawText(measurer.measure(metric.unit, axisStyle.copy(fontWeight = FontWeight.Medium)), topLeft = Offset(0f, 0f))
      var v = 0f
      while (v <= top + step / 2) {
        val yy = y(v)
        drawLine(grid, Offset(left, yy), Offset(size.width, yy), strokeWidth = 1.dp.toPx(), pathEffect = if (v == 0f) null else PathEffect.dashPathEffect(floatArrayOf(4f, 6f)))
        val t = measurer.measure(formatTick(step, v), axisStyle)
        drawText(t, topLeft = Offset(left - 6.dp.toPx() - t.size.width, yy - t.size.height / 2f))
        v += step
      }

      val slot = (size.width - left) / n
      val gap = 2.dp.toPx()
      val colW = (slot - gap).coerceIn(2.dp.toPx(), 28.dp.toPx())
      val labelEvery = kotlin.math.ceil(n / 8f).toInt().coerceAtLeast(1)
      runs.forEachIndexed { i, run ->
        val cx = left + slot * i + slot / 2f
        val yy = y(values[i])
        val isSel = i == selected
        val c = colors.getValue(run.backend)
        val r = androidx.compose.ui.geometry.CornerRadius(minOf(4.dp.toPx(), colW / 2f))
        val column = androidx.compose.ui.graphics.Path().apply {
          addRoundRect(androidx.compose.ui.geometry.RoundRect(cx - colW / 2f, minOf(yy, bottom - 1.dp.toPx()), cx + colW / 2f, bottom, topLeftCornerRadius = r, topRightCornerRadius = r))
        }
        drawPath(column, if (selected == -1 || isSel) c else c.copy(alpha = 0.35f))
        if (i % labelEvery == 0 || i == n - 1) {
          val t = measurer.measure("#${offset + i + 1}", axisStyle)
          drawText(t, topLeft = Offset((cx - t.size.width / 2f).coerceIn(left, size.width - t.size.width), bottom + 5.dp.toPx()))
        }
      }

      if (selected in runs.indices) {
        val run = runs[selected]
        val cx = left + slot * selected + slot / 2f
        val yy = y(values[selected])
        val prompt = run.prompt.replace('\n', ' ').let { if (it.length > 34) it.take(33) + "…" else it }.ifBlank { "(attachment only)" }
        val tip = measurer.measure(
          "#${offset + selected + 1} · ${run.backend.label} · ${formatValue(metric, values[selected])} ${metric.unit}\n$prompt",
          TextStyle(fontSize = 12.sp, color = tipInk, fontFeatureSettings = "tnum", fontWeight = FontWeight.Medium),
        )
        val pad = 8.dp.toPx()
        val w = tip.size.width + pad * 2
        val h = tip.size.height + pad * 1.4f
        val tx = (cx - w / 2f).coerceIn(left, size.width - w)
        val ty = (yy - h - 8.dp.toPx()).coerceAtLeast(topY)
        drawLine(ink.copy(alpha = 0.4f), Offset(cx, topY), Offset(cx, bottom), strokeWidth = 1.dp.toPx())
        drawRoundRect(tipBg, Offset(tx, ty), androidx.compose.ui.geometry.Size(w, h), androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()))
        drawText(tip, topLeft = Offset(tx + pad, ty + pad * 0.7f))
      }
    }
  }
}

private val timeFmt = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

@Composable
private fun RunRow(r: RunRecord) {
  var open by remember { mutableStateOf(false) }
  Column(
    Modifier.fillMaxWidth().clickable { open = !open }.animateContentSize(tween(220)).padding(horizontal = 16.dp, vertical = 14.dp)
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Box(Modifier.size(10.dp).clip(CircleShape).background(backendColor(r.backend)))
      Spacer(Modifier.width(8.dp))
      Text(r.backend.label, style = MaterialTheme.typography.labelLarge)
      Spacer(Modifier.width(8.dp))
      Text(timeFmt.format(Date(r.timestamp)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Spacer(Modifier.weight(1f))
      if (r.images > 0) MediaTag(Icons.Outlined.Image, if (r.images == 1) "Image" else "${r.images} images")
      if (r.audios > 0) {
        Spacer(Modifier.width(6.dp))
        MediaTag(Icons.Default.GraphicEq, if (r.audios == 1) "Audio" else "${r.audios} audio")
      }
    }
    if (r.prompt.isNotBlank()) {
      Text(
        r.prompt,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = if (open) 6 else 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 6.dp),
      )
    }
    Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
      Figure("Generation", "%.1f".format(r.genTps), "tok/s", Modifier.weight(1f), emphasis = true)
      Figure("Prompt", "%.2f".format(r.prefillMs / 1000f), "s", Modifier.weight(1f))
      Figure("Total", "%.2f".format(r.totalMs / 1000f), "s", Modifier.weight(1f))
      Figure("Peak RAM", formatMb(r.ramPeakMb).substringBefore(' '), formatMb(r.ramPeakMb).substringAfter(' '), Modifier.weight(1f))
    }
    if (open) {
      Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Detail("Tokens in / out", "${r.promptTokens} / ${r.genTokens}")
        Detail("Prompt speed", "%.1f tok/s".format(r.prefillTps))
        Detail("First token after", "%.2f s".format(r.ttftMs / 1000f))
        Detail("Generation time", "%.2f s".format(r.genMs / 1000f))
        Detail("RAM before / peak", "${formatMb(r.ramBeforeMb)} / ${formatMb(r.ramPeakMb)}")
        Detail("Context", "${r.ctxUsed} / ${r.ctxSize}")
        Detail("Device", r.device)
        Detail("Model · threads", "${r.model} · ${r.threads}")
        Detail("Orb animation", if (r.animated) "on" else "off")
        if (r.stopped) Detail("Ended", "stopped early or hit the token limit")
      }
    }
  }
}

/** Media badge so image and audio runs stand out in the history. */
@Composable
private fun MediaTag(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
  Row(
    Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer).padding(start = 8.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
    Spacer(Modifier.width(4.dp))
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
  }
}

@Composable
private fun Figure(label: String, value: String, unit: String, modifier: Modifier, emphasis: Boolean = false) {
  Column(modifier) {
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(verticalAlignment = Alignment.Bottom) {
      Text(
        value,
        style = MaterialTheme.typography.titleMedium.merge(Numeric),
        color = if (emphasis) accentText() else MaterialTheme.colorScheme.onSurface,
      )
      Text(" $unit", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 2.dp))
    }
  }
}

@Composable
private fun Detail(k: String, v: String) {
  Row {
    Text(k, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(140.dp))
    Text(v, style = MaterialTheme.typography.bodySmall.merge(Numeric))
  }
}

