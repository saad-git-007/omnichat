/*
 * Compose renderer for the Kotlin port of "thinking-orbs" by Jakub Antalik
 * (MIT License, Copyright (c) 2026 Jakub Antalik — full text in OrbEngine.kt).
 */
package com.saad.omnichat.ui.orbs

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.math.roundToInt

// a stalled clock (backgrounded, long GC) resumes instead of jumping ahead
private const val MAX_STEP_S = 0.25

/**
 * A dotted 3D "thinking" orb. Geometry is the library's 64- or 20-unit
 * tuning, scaled to fit this composable (aspect kept, centred).
 *
 * [dark] selects light ink for dark backgrounds. [tint] recolours the ink
 * using the library's ramp (toward black with depth on dark, toward white on
 * light), keeping per-dot alpha. [frameIntervalMs] > 0 sleeps between frames
 * to cap the frame rate. [paused] or system "Remove animations" shows the
 * library's static reduced-motion frame and runs no loop.
 */
@Composable
fun ThinkingOrbs(
  state: OrbState,
  modifier: Modifier = Modifier,
  size: OrbSize = OrbSize.Large,
  dark: Boolean,
  speed: Float = 1f,
  paused: Boolean = false,
  tint: Color? = null,
  frameIntervalMs: Long = 0L,
) {
  val preset = remember(state, size) { resolvePreset(state, size) }
  val engine = remember { OrbEngine() }
  val frame = remember { OrbFrame() }
  val still = paused || rememberAnimationsDisabled()
  // engine-time seconds (already × preset speed × speed); starts on the static frame
  val time = remember { mutableDoubleStateOf(REDUCED_MOTION_T) }
  val effSpeed by rememberUpdatedState(preset.speed * speed)

  if (!still) {
    LaunchedEffect(frameIntervalMs) {
      var last = -1L
      while (true) {
        withFrameNanos { now ->
          if (last >= 0) time.doubleValue += min((now - last) / 1e9, MAX_STEP_S) * effSpeed
          last = now
        }
        if (frameIntervalMs > 0) delay(frameIntervalMs)
      }
    }
  }

  Canvas(modifier.semantics { contentDescription = state.label }) {
    // reading time here invalidates only the draw phase, not composition
    val t = if (still) REDUCED_MOTION_T else time.doubleValue
    engine.frame(preset, t, frame)
    val units = size.units.toFloat()
    val k = min(this.size.width, this.size.height) / units
    val ox = (this.size.width - units * k) / 2f
    val oy = (this.size.height - units * k) / 2f

    // lines first so nodes sit on top of their edges
    for (i in 0 until frame.lineCount) {
      drawLine(
        color = ink(frame.lineWhite[i], frame.lineAlpha[i], dark, tint),
        start = Offset(ox + frame.lineX1[i].toFloat() * k, oy + frame.lineY1[i].toFloat() * k),
        end = Offset(ox + frame.lineX2[i].toFloat() * k, oy + frame.lineY2[i].toFloat() * k),
        strokeWidth = frame.lineWidth[i].toFloat() * k,
      )
    }
    // dots are already z-sorted far→near
    for (i in 0 until frame.dotCount) {
      drawCircle(
        color = ink(frame.dotWhite[i], frame.dotAlpha[i], dark, tint),
        radius = frame.dotR[i].toFloat() * k,
        center = Offset(ox + frame.dotX[i].toFloat() * k, oy + frame.dotY[i].toFloat() * k),
      )
    }
  }
}

/** The library's paint contract: 8-bit grey (mirrored on dark) or a tint ramp, plus alpha. */
private fun ink(white: Double, alpha: Double, dark: Boolean, tint: Color?): Color {
  val w = white.coerceIn(0.0, 1.0)
  val a = alpha.toFloat().coerceIn(0f, 1f)
  if (tint == null) {
    val g = Math.round((if (dark) 1 - w else w) * 255).toInt()
    return Color(g, g, g, (a * 255).roundToInt())
  }
  fun ramp(c: Float): Int {
    val v = c * 255.0
    return Math.round(if (dark) v * (1 - w) else v + (255 - v) * w).toInt()
  }
  return Color(ramp(tint.red), ramp(tint.green), ramp(tint.blue), (a * tint.alpha * 255).roundToInt())
}

/** True while Settings › Developer options › animator duration scale is 0 ("Remove animations"). */
@Composable
private fun rememberAnimationsDisabled(): Boolean {
  val resolver = LocalContext.current.contentResolver
  var off by remember(resolver) { mutableStateOf(animatorScaleIsZero(resolver)) }
  DisposableEffect(resolver) {
    val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
      override fun onChange(selfChange: Boolean) {
        off = animatorScaleIsZero(resolver)
      }
    }
    resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
    onDispose { resolver.unregisterContentObserver(observer) }
  }
  return off
}

private fun animatorScaleIsZero(resolver: ContentResolver): Boolean =
  Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
