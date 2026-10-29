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

import android.graphics.Paint
import android.graphics.RuntimeShader
import android.util.Log
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import com.saad.omnichat.ui.voiceglow.VoiceGlowEngine.Companion.bell
import com.saad.omnichat.ui.voiceglow.VoiceGlowEngine.Companion.cornerLift
import com.saad.omnichat.ui.voiceglow.VoiceGlowEngine.Companion.paintedRadius
import com.saad.omnichat.ui.voiceglow.VoiceGlowEngine.Companion.smooth
import com.saad.omnichat.ui.voiceglow.VoiceGlowEngine.Companion.tailLift
import com.saad.omnichat.ui.voiceglow.VoiceGlowSpec as G
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Lobe colours (0–1 RGB, 7 × 3) from the default palette for the theme, with [colors] over the first slots. */
internal fun resolvePalette(colors: List<Color>?, dark: Boolean): FloatArray {
  val base = if (dark) VoiceGlowDefaults.DarkPalette else VoiceGlowDefaults.LightPalette
  val out = FloatArray(G.LOBES * 3)
  for (i in 0 until G.LOBES) {
    val c = (colors?.getOrNull(i) ?: base[i]).convert(ColorSpaces.Srgb)
    out[i * 3] = c.red
    out[i * 3 + 1] = c.green
    out[i * 3 + 2] = c.blue
  }
  return out
}

/** The band's contour in px: an organic bell along the glow's ceiling, rising again into the corners (`bandPoints`). */
internal class BandLine {
  var centre = 0f; private set
  var half = 0f; private set
  var apex = 0f; private set
  var base = 0f; private set
  var tail = 0f; private set
  var over = 0f; private set
  var x0 = 0f; private set
  var x1 = 0f; private set
  var radius = 0f; private set
  var morph = 0f; private set
  var width = 0f; private set
  private var cfg = VoiceGlowConfig.of(VoiceGlowType.Default, true)

  fun update(cfg: VoiceGlowConfig, e: VoiceGlowEngine, d: Float, cw: Float, ch: Float, radiusPx: Float) {
    this.cfg = cfg
    width = cw
    morph = e.morph
    centre = cw / 2f + e.cx * e.w * d
    half = G.CEILING_HALF_WIDTH * cfg.rangeWidth * e.w * e.maskWidth * d
    apex = min(ch * 0.82f * min(1f, cfg.scale), (G.CEILING_HEIGHT * cfg.rangeHeight * e.h + e.lift) * d * cfg.bandPosition)
    base = ch - cfg.bandOffset * d
    tail = cfg.bandTail * (1f - smooth(min(1f, e.morph * 4f)))
    over = if (tail > 0.001f) cfg.bandTailOverflow * d else 0f
    x0 = if (tail > 0.001f) -over else centre - half
    x1 = if (tail > 0.001f) cw + over else centre + half
    radius = paintedRadius(radiusPx, cw, ch)
  }

  fun y(x: Float): Float {
    val t = ((x - centre) / max(1f, half)).coerceIn(-1f, 1f)
    val edge = (if (x < centre) centre else width - centre) + over
    val v = bell(t, cfg.bandCurve, cfg.bandSpread, cfg.bandSkew) +
      tailLift(abs(x - centre), edge, tail, cfg.bandTailPosition, cfg.bandTailCurve)
    val arc = if (morph > 0f) cornerLift(x, width, radius) * morph else 0f
    return base - apex * v - arc
  }
}

/** Draws one frame of the glow from the engine's state. Holds every object reused across frames. */
internal class VoiceGlowRenderer {
  private val glowShader: RuntimeShader? = compile(VoiceGlowShaders.GLOW)
  private val glowBrush = glowShader?.let { ShaderBrush(it) }
  private var washShader: RuntimeShader? = null
  private var washBrush: ShaderBrush? = null

  private val clip = Path()
  private var clipW = -1f
  private var clipH = -1f
  private var clipR = -1f

  private val line = BandLine()
  private val bandPath = android.graphics.Path()
  private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeCap = Paint.Cap.ROUND
    strokeJoin = Paint.Join.ROUND
  }
  private var blurDensity = -1f
  private var blurWidth = -1f

  private val lobeI = FloatArray(G.LOBES * 4)
  private val lobeS = FloatArray(G.LOBES * 4)
  private val lobeB = FloatArray(G.LOBES * 4)
  private val lobeC = FloatArray(G.LOBES * 4)
  private val rgb = FloatArray(12)

  fun DrawScope.drawGlow(
    e: VoiceGlowEngine,
    cfg: VoiceGlowConfig,
    palette: FloatArray,
    radiusPx: Float,
    halo: GraphicsLayer,
    ridge: GraphicsLayer,
  ) {
    val cw = size.width
    val ch = size.height
    if (cw <= 0f || ch <= 0f) return
    val r = paintedRadius(radiusPx, cw, ch)
    if (cw != clipW || ch != clipH || r != clipR) {
      clipW = cw; clipH = ch; clipR = r
      clip.rewind()
      clip.addRoundRect(RoundRect(0f, 0f, cw, ch, CornerRadius(r)))
    }
    if (density != blurDensity || cfg.bandWidth != blurWidth) {
      blurDensity = density
      blurWidth = cfg.bandWidth
      val sigma = 3.5f * cfg.bandWidth / 2f * density
      ridge.renderEffect = BlurEffect(blurRadius(sigma), blurRadius(sigma), TileMode.Decal)
      halo.renderEffect = BlurEffect(blurRadius(sigma * 3f), blurRadius(sigma * 3f), TileMode.Decal)
    }
    line.update(cfg, e, density, cw, ch, radiusPx)
    clipPath(clip) {
      drawLayers(e, cfg, palette, r)
      drawBand(e, cfg, halo, ridge)
      if (cfg.coreLight > 0f) drawWash(e, cfg)
    }
  }

  private fun DrawScope.drawLayers(e: VoiceGlowEngine, cfg: VoiceGlowConfig, palette: FloatArray, r: Float) {
    val shader = glowShader ?: return
    val brush = glowBrush ?: return
    val d = density
    val cw = size.width
    val ch = size.height
    val fade = (70f * cfg.softness).roundToInt().coerceIn(40, 95) / 100f
    val bloomFade = min(95f, fade * 100f + 2f) / 100f
    // CSS blur(10px) is a Gaussian of that std-dev; a linear cone of radius a has per-axis variance 3a²/20
    val spreadSq = (2.58f * 10f * cfg.glowSize * d).let { it * it }
    val beamX = cw / 2f + e.cx * e.w * d

    for (i in 0 until G.LOBES) {
      val o = i * 4
      val x = cw / 2f + (e.cx + e.lobeX[i]) * e.w * d
      val yy = ch + e.lobeY[i] * d
      val lw = G.lobeW[i]
      val lh = G.lobeH[i]
      val hl = e.h * e.lobeL[i] * d
      lobeI[o] = x; lobeI[o + 1] = yy
      lobeI[o + 2] = (lw * cfg.glowWidth * 0.9f * cfg.innerScale).roundToInt() * e.w * d
      lobeI[o + 3] = (lh * cfg.glowHeight * 0.9f * cfg.innerScale * cfg.innerHeight).roundToInt() * hl
      lobeS[o] = x; lobeS[o + 1] = yy + 2f * d
      lobeS[o + 2] = (lw * cfg.glowWidth * cfg.strokeScale).roundToInt() * e.w * d
      lobeS[o + 3] = (lh * cfg.glowHeight * cfg.strokeScale).roundToInt() * hl
      val rx = (lw * cfg.glowWidth * 1.15f * cfg.bloomScale).roundToInt() * e.w * d
      val ry = (lh * cfg.glowHeight * 1.5f * cfg.bloomScale * cfg.bloomHeight).roundToInt() * hl
      val rx2 = sqrt(rx * bloomFade * rx * bloomFade + spreadSq) / bloomFade
      val ry2 = sqrt(ry * bloomFade * ry * bloomFade + spreadSq) / bloomFade
      lobeB[o] = x; lobeB[o + 1] = yy; lobeB[o + 2] = rx2; lobeB[o + 3] = ry2
      VoiceColorMatrix.apply(e.matrix, palette[i * 3], palette[i * 3 + 1], palette[i * 3 + 2], lobeC, o)
      lobeC[o + 3] = if (rx > 0f && ry > 0f) (rx * ry) / (rx2 * ry2) else 0f
    }

    val base = e.presence * e.glow * cfg.strength
    val sg = cfg.shadowGray
    VoiceColorMatrix.apply(e.matrix, sg, sg, sg, rgb, 0)
    val hi = if (cfg.dark) 1f else 0f
    VoiceColorMatrix.apply(e.matrix, hi, hi, hi, rgb, 3)

    shader.setFloatUniform("size", cw, ch)
    shader.setFloatUniform("radius", r)
    shader.setFloatUniform("border", G.BORDER * d)
    shader.setFloatUniform("fades", fade, bloomFade)
    shader.setFloatUniform("innerEdge", 28f * cfg.scale * d, 9f * cfg.scale * d)
    shader.setFloatUniform("shadow", rgb[0], rgb[1], rgb[2], cfg.shadowAlpha)
    shader.setFloatUniform(
      "opacity",
      min(1f, base * cfg.innerOpacity),
      min(1f, base * cfg.strokeOpacity),
      min(1f, base * cfg.bloomOpacity),
    )
    shader.setFloatUniform("lobeAlpha", 0.46f, 1f, cfg.bloomLobeAlpha)
    val maskY = ch + e.cy * d
    val ry0 = e.h * cfg.rangeHeight
    val rw = cfg.rangeWidth * e.w * e.maskWidth * d
    shader.setFloatUniform("maskA", beamX, maskY, 170f * rw, (64f * ry0 + e.lift) * d)
    shader.setFloatUniform("maskB", beamX, maskY, 200f * rw, (130f * ry0 + e.lift) * d)
    shader.setFloatUniform("lobeI", lobeI)
    shader.setFloatUniform("lobeS", lobeS)
    shader.setFloatUniform("lobeB", lobeB)
    shader.setFloatUniform("lobeC", lobeC)
    val core = cfg.coreSize * d
    shader.setFloatUniform(
      "hiGeom", beamX, ch + (2f + e.cy) * d, (if (cfg.dark) 30f else 40f) * core * e.w, 30f * core * e.h,
    )
    shader.setFloatUniform("hiColor", rgb[3], rgb[4], rgb[5])
    if (cfg.dark) {
      shader.setFloatUniform("hiStops", 0.45f, 0.30f, 0.14f, 0.65f)
    } else {
      shader.setFloatUniform("hiStops", 0.55f, 0.35f, 0.22f, 0.70f)
    }
    drawRect(brush)
  }

  private fun DrawScope.drawBand(e: VoiceGlowEngine, cfg: VoiceGlowConfig, halo: GraphicsLayer, ridge: GraphicsLayer) {
    val alpha = min(1f, 0.6f * cfg.bandStrength * e.bendA)
    if (alpha < 0.005f) return
    val d = density
    bandPath.rewind()
    for (i in 0..BAND_SAMPLES) {
      val x = line.x0 + (line.x1 - line.x0) * i / BAND_SAMPLES
      val y = line.y(x)
      if (i == 0) bandPath.moveTo(x, y) else bandPath.lineTo(x, y)
    }
    for (k in 0 until 4) {
      val c = cfg.bandColors[k]
      VoiceColorMatrix.apply(
        e.matrix, (c shr 16 and 0xFF) / 255f, (c shr 8 and 0xFF) / 255f, (c and 0xFF) / 255f, rgb, k * 3,
      )
    }
    val bw = cfg.bandWidth * (1f + 0.35f * e.level)
    val split = cfg.bandAberration * (0.35f + 0.65f * e.level)
    val dy = (4f + 12f * split) * cfg.scale * d
    val dx = 4f * split * cfg.scale * d
    val base = cfg.bandBase * alpha * e.presence * cfg.strength
    val thickness = 14f * bw * d

    // Halo: wide and hazy, three times the ridge's blur
    halo.record {
      drawIntoCanvas { canvas ->
        paint.strokeWidth = thickness * 2.2f
        paint.color = argb(base * 0.3f, 0)
        canvas.nativeCanvas.drawPath(bandPath, paint)
      }
    }
    drawLayer(halo)

    // The ridge: stacked strokes of shrinking width, red riding above, blue below, a faint green between
    ridge.record {
      drawIntoCanvas { canvas ->
        val nc = canvas.nativeCanvas
        for (k in 0 until 4) {
          // above, mid, below, core
          val colorAt = RIDGE_COLOR[k] * 3
          nc.save()
          nc.translate(dx * RIDGE_DX[k], dy * RIDGE_DY[k])
          for (j in 0 until 4) {
            paint.strokeWidth = max(0.6f * d, thickness * RAMP_WIDTH[j])
            paint.color = argb(base * RIDGE_ALPHA[k] * RAMP_ALPHA[j], colorAt)
            nc.drawPath(bandPath, paint)
          }
          nc.restore()
        }
      }
    }
    drawLayer(ridge)
  }

  private fun DrawScope.drawWash(e: VoiceGlowEngine, cfg: VoiceGlowConfig) {
    val coreLight = cfg.coreLight.coerceIn(0f, 3f)
    val boost = (coreLight - 1f).coerceIn(0f, 2f)
    val opacity = e.presence * min(1f, e.glow * min(1f, coreLight) * (1.6f + 1.4f * boost))
    if (opacity <= 0.005f) return
    val shader = washShader ?: compile(VoiceGlowShaders.WASH)?.also {
      washShader = it
      washBrush = ShaderBrush(it)
    } ?: return
    val d = density
    val b1 = min(1f, boost)
    val b2 = max(0f, boost - 1f)
    val grow = 1f + 0.3f * boost
    shader.setFloatUniform("lineA", line.centre, line.half, line.apex, line.base)
    shader.setFloatUniform("lineB", cfg.bandCurve, cfg.bandSpread, cfg.bandSkew, line.tail)
    shader.setFloatUniform("lineC", cfg.bandTailPosition, cfg.bandTailCurve, line.over, line.morph)
    shader.setFloatUniform("lineD", line.x0, line.x1, line.radius, line.width)
    shader.setFloatUniform(
      "wash",
      size.width / 2f + e.cx * e.w * d,
      size.height + e.cy * d,
      120f * cfg.coreLightWidth * grow * cfg.scale * e.w * d,
      (70f * cfg.coreLightHeight * grow * cfg.scale * e.h + e.lift) * d,
    )
    shader.setFloatUniform(
      "stops",
      (45f * b1 + 27f * b2) / 100f,
      (40f + 25f * b1 + 15f * b2) / 100f,
      min(1f, 0.55f + 0.35f * b1 + 0.1f * b2),
      (72f + 14f * b1 + 8f * b2) / 100f,
    )
    shader.setFloatUniform("washK", opacity, 8f * cfg.glowSize * d)
    drawRect(washBrush ?: return)
  }

  private fun argb(a: Float, at: Int): Int =
    android.graphics.Color.argb(a.coerceIn(0f, 1f), rgb[at], rgb[at + 1], rgb[at + 2])

  private companion object {
    const val BAND_SAMPLES = 56
    val RAMP_WIDTH = floatArrayOf(1f, 0.72f, 0.46f, 0.22f)
    val RAMP_ALPHA = floatArrayOf(0.16f, 0.2f, 0.26f, 0.34f)
    // rgb slots: 0 core, 1 above, 2 mid, 3 below
    val RIDGE_COLOR = intArrayOf(1, 2, 3, 0)
    val RIDGE_ALPHA = floatArrayOf(1f, 0.55f, 1f, 0.9f)
    val RIDGE_DX = floatArrayOf(1f, 0.35f, -1f, 0f)
    val RIDGE_DY = floatArrayOf(-1f, -0.35f, 1f, 0f)

    /** Canvas `blur(σ)` to the radius RenderEffect expects (it converts with σ = 0.57735·r + 0.5). */
    fun blurRadius(sigma: Float): Float = max(0.5f, (sigma - 0.5f) / 0.57735f)

    fun compile(src: String): RuntimeShader? = try {
      RuntimeShader(src)
    } catch (t: IllegalArgumentException) {
      Log.e("VoiceGlow", "AGSL compile failed", t)
      null
    }
  }
}
