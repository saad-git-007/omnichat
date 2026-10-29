/*
 * Ported from voice-glow (github.com/Jakubantalik/Libraries.dev, packages/voice-glow).
 *
 * MIT License
 *
 * Copyright (c) 2026 Jakub Antalik
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.saad.omnichat.ui.voiceglow

import android.animation.ValueAnimator
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.max

/**
 * Wraps [content] (the chat input pill) and draws the glow along its bottom edge, auto-using [cornerRadius].
 *
 * A Compose port of `voice-glow` by Jakub Antalik: a centred, colourful glow that rises and blooms
 * with the voice. Like the original, every layer is drawn over the content and clipped to its
 * rounded shape.
 *
 * @param level 0..1 mic level, polled every frame. Fed straight into the noise gate (threshold
 *   0.015) like the library's `level` getter; for raw RMS use [VoiceGlowDefaults.normalizeRms].
 * @param active false fades the glow out (0.5 s); once it is gone no frame loop runs.
 * @param processing true gathers the lobes into one beam that sweeps side to side.
 * @param colors up to 7 lobe colours (centre, then pairs outward); missing slots keep the default palette.
 * @param frameIntervalMs >0 adds that delay between frames to cap the frame rate.
 * @param type the library's host preset. [VoiceGlowType.Mobile] is for wrapping a whole screen
 *   (pass its display corner radius, or 0.dp): the glow rises from the bottom edge, centred.
 */
@Composable
fun VoiceGlow(
  level: () -> Float,
  active: Boolean,
  processing: Boolean = false,
  cornerRadius: Dp = 28.dp,
  modifier: Modifier = Modifier,
  dark: Boolean = true,
  colors: List<Color>? = null,
  frameIntervalMs: Long = 0L,
  type: VoiceGlowType = VoiceGlowType.Default,
  content: @Composable () -> Unit,
) {
  val engine = remember { VoiceGlowEngine() }
  val renderer = remember { VoiceGlowRenderer() }
  val halo = rememberGraphicsLayer()
  val ridge = rememberGraphicsLayer()
  val frame = remember { mutableLongStateOf(0L) }
  val palette = remember(colors, dark) { resolvePalette(colors, dark) }
  val config = VoiceGlowConfig.of(type, dark)

  val currentLevel by rememberUpdatedState(level)
  val currentProcessing by rememberUpdatedState(processing)
  val currentConfig by rememberUpdatedState(config)
  val currentInterval by rememberUpdatedState(frameIntervalMs)

  LaunchedEffect(active) {
    if (!active && !engine.visible) return@LaunchedEffect
    var last = Long.MIN_VALUE
    while (true) {
      val now = withFrameNanos { it }
      val interval = currentInterval
      val maxDt = max(0.05f, (interval + 20L) / 1000f)
      val dt = if (last == Long.MIN_VALUE) 1f / 60f else ((now - last) / 1e9f).coerceIn(0f, maxDt)
      last = now
      // "Remove animations" (animator duration scale 0): no breathing, flow, hue drift or sweep
      val reducedMotion = !ValueAnimator.areAnimatorsEnabled()
      engine.step(dt, currentLevel(), active, currentProcessing, reducedMotion, currentConfig)
      frame.longValue = now
      if (!active && !engine.visible) break
      if (interval > 0L) delay(interval)
    }
  }

  Box(
    modifier = modifier.drawWithContent {
      drawContent()
      frame.longValue // invalidate the draw (not composition) on every engine step
      engine.widthDp = size.width / density
      engine.heightDp = size.height / density
      engine.radiusDp = cornerRadius.value
      if (engine.visible) {
        with(renderer) { drawGlow(engine, config, palette, cornerRadius.toPx(), halo, ridge) }
      }
    },
    propagateMinConstraints = true,
  ) {
    content()
  }
}
