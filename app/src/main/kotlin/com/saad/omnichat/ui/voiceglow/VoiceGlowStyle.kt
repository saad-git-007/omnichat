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

import androidx.compose.ui.graphics.Color
import kotlin.math.cos
import kotlin.math.sin

/** Palettes and input helpers for [VoiceGlow]. */
object VoiceGlowDefaults {
  /** The library's default `colorful` palette, dark theme: 7 lobes, centre first, then pairs outward. */
  val DarkPalette: List<Color> = listOf(
    Color(255, 70, 120), Color(60, 190, 255), Color(175, 70, 255), Color(60, 220, 130),
    Color(255, 150, 40), Color(90, 100, 255), Color(40, 200, 190),
  )

  /** The `colorful` palette on the light theme (gold, sky, violet, rose, peach…). */
  val LightPalette: List<Color> = listOf(
    Color(255, 201, 21), Color(126, 196, 255), Color(180, 40, 230), Color(235, 100, 160),
    Color(255, 176, 122), Color(154, 160, 255), Color(127, 217, 238),
  )

  /** The library's other dark-theme variants, for the `colors` override. */
  val Ocean: List<Color> = listOf(
    Color(80, 140, 255), Color(40, 200, 230), Color(120, 90, 255), Color(30, 170, 210),
    Color(160, 80, 240), Color(60, 110, 255), Color(40, 190, 180),
  )
  val Sunset: List<Color> = listOf(
    Color(255, 110, 60), Color(255, 180, 40), Color(255, 60, 90), Color(255, 210, 80),
    Color(240, 70, 140), Color(255, 140, 50), Color(230, 50, 110),
  )
  val Forest: List<Color> = listOf(
    Color(70, 220, 120), Color(40, 200, 180), Color(140, 230, 80), Color(30, 170, 140),
    Color(190, 235, 70), Color(50, 190, 110), Color(30, 150, 120),
  )
  val Candy: List<Color> = listOf(
    Color(255, 90, 170), Color(255, 120, 220), Color(210, 80, 255), Color(255, 150, 190),
    Color(180, 110, 255), Color(255, 70, 140), Color(230, 100, 240),
  )
  val Ice: List<Color> = listOf(
    Color(150, 230, 255), Color(90, 200, 255), Color(190, 240, 255), Color(120, 190, 255),
    Color(160, 220, 250), Color(80, 170, 255), Color(200, 235, 255),
  )
  val Gold: List<Color> = listOf(
    Color(255, 200, 70), Color(255, 170, 40), Color(255, 220, 110), Color(240, 150, 30),
    Color(255, 235, 140), Color(230, 160, 40), Color(250, 210, 90),
  )

  /**
   * Raw microphone RMS (≈0.03–0.2 for speech at a phone) to the 0–1 `level` the glow expects:
   * the gain the library applies to its own analyser before the noise gate.
   */
  fun normalizeRms(rms: Float, sensitivity: Float = VoiceGlowSpec.SENSITIVITY): Float =
    (rms * VoiceGlowSpec.BASE_GAIN * sensitivity).coerceIn(0f, 1f)
}

/** Host preset, as the library's `type`: retunes the glow's geometry for the view it wraps. */
enum class VoiceGlowType {
  /** A ~350 dp chat input or card. */
  Default,
  /** A ~150×44 dp recording pill: glow pulled in, shallow bend, thin band. */
  Pill,
  /** The bottom edge of a phone screen: wider range, taller rise, broad band. */
  Mobile,
}

/** Fixed lobe layout and response of the driver. Lengths in dp at scale 1. */
internal object VoiceGlowSpec {
  const val LOBES = 7
  val lobeX = floatArrayOf(0f, -36f, 36f, -72f, 72f, -108f, 108f)
  val lobeW = floatArrayOf(74f, 54f, 54f, 48f, 48f, 42f, 42f)
  val lobeH = floatArrayOf(46f, 40f, 40f, 32f, 32f, 26f, 26f)
  /** Centre lobe follows the lows, neighbours the mids, outer pair the highs, far pair the mids. */
  val lobeBand = intArrayOf(0, 1, 1, 2, 2, 1, 1)
  const val LOBE_SPAN = 36f * LOBES

  const val CEILING_HALF_WIDTH = 170f
  const val CEILING_HEIGHT = 64f
  const val BORDER = 1f

  // Response (web defaults)
  const val BASE_GAIN = 5f
  const val SENSITIVITY = 3.1f
  const val THRESHOLD = 0.015f
  const val ATTACK = 0.325f
  const val RELEASE = 0.86f
  const val BREATHE_DURATION = 5.2f
  const val FADE_IN = 0.6f
  const val FADE_OUT = 0.5f

  // Processing (the Pro sweep is not in the open-source repo; this drives the public `motion` model)
  const val GATHER_TIME = 0.6f
  const val SWEEP_PERIOD = 2.6f
  const val HELD_LEVEL = 0.55f
  const val CORNER_FOLLOW = 0.7f
  /** dp the gathered beam's centre stays clear of each side, for the default range width (0.75). */
  const val SWEEP_INSET = 56f
}

/** The geometry knobs of `presets.ts` (`VoiceGeometry`), unscaled. */
internal data class VoiceGeometry(
  val scale: Float = 1f,
  val glowSize: Float = 1f,
  val strokeOpacity: Float = 1f,
  val innerOpacity: Float = 1f,
  val bloomOpacity: Float = 1f,
  val idle: Float = 0.18f,
  val reach: Float = 1.2f,
  val spread: Float = 1.05f,
  val flow: Float = 48f,
  val bend: Float = 60f,
  val bandStrength: Float = 1.55f,
  val bandWidth: Float = 2.15f,
  val bandPosition: Float = 0.35f,
  val bandCurve: Float = 1.75f,
  val bandSpread: Float = 0.87f,
  val bandSkew: Float = 0.12f,
  val bandOffset: Float = -27f,
  val bandTail: Float = 0.59f,
  val bandTailPosition: Float = 0.67f,
  val bandTailCurve: Float = 2.4f,
  val bandTailOverflow: Float = 15f,
  val bandAberration: Float = 0.89f,
  val glowWidth: Float = 0.65f,
  val glowHeight: Float = 1.25f,
  val lobeSpacing: Float = 0.85f,
  val rangeWidth: Float = 0.75f,
  val rangeHeight: Float = 1f,
  val softness: Float = 1.07f,
  val coreSize: Float = 1f,
  val coreLight: Float = 0f,
  val coreLightWidth: Float = 1f,
  val coreLightHeight: Float = 1f,
  val strokeScale: Float = 1f,
  val innerScale: Float = 1f,
  val innerHeight: Float = 1f,
  val bloomScale: Float = 1f,
  val bloomHeight: Float = 1f,
) {
  companion object {
    /**
     * `resolveVoiceDefaults(type, theme)`: defaults, the light theme's own reach / spread / coreLight
     * where the type leaves them alone, the type preset, then `lightTypeOverrides`.
     */
    fun resolve(type: VoiceGlowType, dark: Boolean): VoiceGeometry {
      var g = when (type) {
        VoiceGlowType.Default -> VoiceGeometry()
        VoiceGlowType.Pill -> VoiceGeometry(
          scale = 0.45f, glowSize = 0.95f, strokeOpacity = 1.2f, innerOpacity = 0.85f,
          reach = 1.35f, spread = 1.1f, flow = 0f, bend = 23f, bandStrength = 1.55f, bandWidth = 1.85f,
          bandCurve = 1.95f, bandSpread = 0.38f, bandOffset = -16f, bandTail = 0f,
          glowWidth = 0.65f, glowHeight = 0.95f, lobeSpacing = 0.45f, rangeWidth = 0.8f, rangeHeight = 0.7f,
          softness = 0.88f, coreSize = 0.25f, strokeScale = 1.25f, innerScale = 0.95f,
          bloomScale = 1.05f, bloomHeight = 2.25f,
        )
        VoiceGlowType.Mobile -> VoiceGeometry(
          scale = 1.25f, spread = 0.45f, reach = 3f, flow = 60f, bend = 70f, bandWidth = 2.4f,
          bandCurve = 1.55f, bandSpread = 0.9f, bandOffset = -50f, bandTail = 0.62f,
          bandTailPosition = 0.42f, bandTailCurve = 2.7f, bandTailOverflow = 22f, bandStrength = 1.8f,
          glowWidth = 1.15f, glowHeight = 2.1f, lobeSpacing = 1.35f, rangeWidth = 1.25f, rangeHeight = 1.2f,
          softness = 1.1f,
        )
      }
      if (!dark) {
        // No type preset sets coreLight; only the default type leaves reach / spread alone.
        g = g.copy(coreLight = 1.8f)
        if (type == VoiceGlowType.Default) g = g.copy(reach = 1.8f, spread = 0.8f)
        g = g.copy(bandStrength = if (type == VoiceGlowType.Pill) 2f else 1.7f)
      }
      return g
    }
  }
}

/**
 * Everything a frame needs for one type on one theme: the theme preset (`themePresets`), the type's
 * colour tuning (`resolveVoiceStyle`) and the geometry with `scale` multiplied into every dp length,
 * as `VoiceBeam.tsx` does. Resolved once per (type, theme).
 */
internal class VoiceGlowConfig(val type: VoiceGlowType, val dark: Boolean) {
  val geometry = VoiceGeometry.resolve(type, dark)
  private val g = geometry

  // Theme preset × the type's per-layer multipliers
  val strokeOpacity = (if (dark) 1.16f else 1.2f) * g.strokeOpacity
  val innerOpacity = (if (dark) 0.47f else 0.85f) * g.innerOpacity
  val bloomOpacity = (if (dark) 0.89f else 0.5f) * g.bloomOpacity
  val shadowGray = if (dark) 1f else 0f
  val shadowAlpha = if (dark) 0.1f else 0.08f
  val hueRange = if (dark) 24f else 40f
  val hueDuration = if (dark) 12f else 8.5f
  val hueBase = if (dark) 0f else 5f

  // resolveVoiceStyle(type, theme) over the theme's brightness / saturation / strength
  val brightness: Float
  val saturation: Float
  val strength: Float
  init {
    var b = if (dark) 1.1f else 0.95f
    var s = if (dark) 1.2f else 1.6f
    var st = if (dark) 1f else 0.8f
    if (dark) {
      when (type) {
        VoiceGlowType.Default -> b = 1.15f
        VoiceGlowType.Pill -> { b = 1.35f; s = 1.5f }
        VoiceGlowType.Mobile -> { st = 1f; b = 1.2f; s = 1.5f }
      }
    } else if (type == VoiceGlowType.Mobile) {
      st = 1f
    }
    brightness = b
    saturation = s
    strength = st
  }

  val scale = maxOf(0.05f, g.scale)
  val idle = g.idle.coerceIn(0f, 1f)
  val reach = g.reach
  val spread = g.spread
  val bandStrength = g.bandStrength
  val bandPosition = g.bandPosition
  val bandCurve = g.bandCurve
  val bandSpread = g.bandSpread
  val bandSkew = g.bandSkew
  val bandTail = g.bandTail
  val bandTailPosition = g.bandTailPosition
  val bandTailCurve = g.bandTailCurve
  val bandAberration = g.bandAberration
  val softness = g.softness
  val coreLight = g.coreLight
  val coreLightWidth = g.coreLightWidth
  val coreLightHeight = g.coreLightHeight
  val strokeScale = g.strokeScale
  val innerScale = g.innerScale
  val innerHeight = g.innerHeight
  val bloomScale = g.bloomScale
  val bloomHeight = g.bloomHeight

  // dp lengths and px-authored multipliers, × scale
  val flow = g.flow * scale
  val bend = g.bend * scale
  val bandWidth = g.bandWidth * scale
  val bandOffset = g.bandOffset * scale
  val bandTailOverflow = g.bandTailOverflow * scale
  val glowWidth = g.glowWidth * scale
  val glowHeight = g.glowHeight * scale
  val lobeSpacing = g.lobeSpacing * scale
  val rangeWidth = g.rangeWidth * scale
  val rangeHeight = g.rangeHeight * scale
  val coreSize = g.coreSize * scale
  val glowSize = g.glowSize * scale

  val bloomLobeAlpha = if (dark) 0.9f else 0.7f
  val bandBase = if (dark) 0.42f else 0.4f
  /** Band core, above, mid, below as packed RGB 0–255. */
  val bandColors: IntArray = if (dark) {
    intArrayOf(0xFFFFFF, 0xFF4650, 0x5AFF96, 0x508CFF)
  } else {
    intArrayOf(0xC58BFF, 0xFF7AB6, 0x7EC4FF, 0x2DFFAB)
  }
  /** dp the swept beam's centre keeps from each side, grown with the range the type masks the glow to. */
  val sweepInset = VoiceGlowSpec.SWEEP_INSET * rangeWidth / 0.75f

  companion object {
    private val cache = HashMap<Pair<VoiceGlowType, Boolean>, VoiceGlowConfig>()

    fun of(type: VoiceGlowType, dark: Boolean): VoiceGlowConfig =
      synchronized(cache) { cache.getOrPut(type to dark) { VoiceGlowConfig(type, dark) } }
  }
}

/** CSS `hue-rotate` · `brightness` · `saturate` as one 3×3 matrix (row-major), applied on the CPU per colour. */
internal object VoiceColorMatrix {
  fun compose(out: FloatArray, hueDegrees: Float, brightness: Float, saturation: Float) {
    val rad = Math.toRadians(hueDegrees.toDouble())
    val c = cos(rad).toFloat()
    val s = sin(rad).toFloat()
    val b = brightness
    val h0 = (0.213f + c * 0.787f - s * 0.213f) * b
    val h1 = (0.715f - c * 0.715f - s * 0.715f) * b
    val h2 = (0.072f - c * 0.072f + s * 0.928f) * b
    val h3 = (0.213f - c * 0.213f + s * 0.143f) * b
    val h4 = (0.715f + c * 0.285f + s * 0.140f) * b
    val h5 = (0.072f - c * 0.072f - s * 0.283f) * b
    val h6 = (0.213f - c * 0.213f - s * 0.787f) * b
    val h7 = (0.715f - c * 0.715f + s * 0.715f) * b
    val h8 = (0.072f + c * 0.928f + s * 0.072f) * b
    val sa = saturation
    val s0 = 0.213f + 0.787f * sa; val s1 = 0.715f - 0.715f * sa; val s2 = 0.072f - 0.072f * sa
    val s3 = 0.213f - 0.213f * sa; val s4 = 0.715f + 0.285f * sa; val s5 = 0.072f - 0.072f * sa
    val s6 = 0.213f - 0.213f * sa; val s7 = 0.715f - 0.715f * sa; val s8 = 0.072f + 0.928f * sa
    out[0] = s0 * h0 + s1 * h3 + s2 * h6; out[1] = s0 * h1 + s1 * h4 + s2 * h7; out[2] = s0 * h2 + s1 * h5 + s2 * h8
    out[3] = s3 * h0 + s4 * h3 + s5 * h6; out[4] = s3 * h1 + s4 * h4 + s5 * h7; out[5] = s3 * h2 + s4 * h5 + s5 * h8
    out[6] = s6 * h0 + s7 * h3 + s8 * h6; out[7] = s6 * h1 + s7 * h4 + s8 * h7; out[8] = s6 * h2 + s7 * h5 + s8 * h8
  }

  /** Writes m·rgb (0–1 components), clamped as the browser clamps after the chain, into out[at..at+2]. */
  fun apply(m: FloatArray, r: Float, g: Float, b: Float, out: FloatArray, at: Int) {
    out[at] = (m[0] * r + m[1] * g + m[2] * b).coerceIn(0f, 1f)
    out[at + 1] = (m[3] * r + m[4] * g + m[5] * b).coerceIn(0f, 1f)
    out[at + 2] = (m[6] * r + m[7] * g + m[8] * b).coerceIn(0f, 1f)
  }
}
