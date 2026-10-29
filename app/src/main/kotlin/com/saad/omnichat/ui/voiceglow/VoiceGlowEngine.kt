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

import com.saad.omnichat.ui.voiceglow.VoiceGlowSpec as G
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The voice driver, ported from `voiceDriver.ts`: shapes the level (noise gate, soft saturation),
 * follows it with an attack/release envelope, advances the flow, gathers the lobes into a beam for
 * the processing sweep, folds in the idle breathing and the hue drift. Pure JVM; lengths in dp.
 */
internal class VoiceGlowEngine {
  // Envelope state
  private var t = 0.0
  private var phase = 0.0
  private var sweepT = 0.0
  private var presenceLin = 0f
  private var gatherLin = 0f
  private val bands = FloatArray(3)

  // Host geometry, written by the draw pass
  var widthDp = 0f
  var heightDp = 0f
  var radiusDp = 0f

  // Frame outputs (the web's CSS custom properties)
  var level = 0f; private set
  /** Eased presence of the whole effect, 0–1. */
  var presence = 0f; private set
  var glow = 0f; private set
  var h = 0.5f; private set
  var w = 0.85f; private set
  /** Beam travel (dp, multiplied by [w] when placed) and its corner lift. */
  var cx = 0f; private set
  var cy = 0f; private set
  var maskWidth = 1f; private set
  var lift = 0f; private set
  var bendA = 0f; private set
  var morph = 0f; private set
  val lobeX = FloatArray(G.LOBES)
  val lobeL = FloatArray(G.LOBES)
  val lobeY = FloatArray(G.LOBES)
  /** The hue / brightness / saturate chain for this frame. */
  val matrix = FloatArray(9)

  /** True while the effect is on screen or fading. */
  val visible: Boolean get() = presenceLin > 0f

  fun step(
    dt: Float,
    rawLevel: Float,
    active: Boolean,
    processing: Boolean,
    reducedMotion: Boolean,
    c: VoiceGlowConfig,
  ) {
    t += dt

    // Presence: 0.6 s fade in, 0.5 s out
    presenceLin = if (active) min(1f, presenceLin + dt / G.FADE_IN) else max(0f, presenceLin - dt / G.FADE_OUT)
    presence = smooth(presenceLin)

    // Raw level; no spectrum, so the bands get slow out-of-phase wobbles scaled by the level
    val raw = if (rawLevel.isNaN()) 0f else rawLevel.coerceIn(0f, 1f)
    val ts = t.toFloat()
    val b1 = raw * (0.72f + 0.28f * sin(t * 9.1).toFloat())
    val b2 = raw * (0.6f + 0.4f * sin(t * 13.7 + 2).toFloat())

    level = follow(level, shape(raw, G.THRESHOLD), dt, G.ATTACK, G.RELEASE)
    val bt = G.THRESHOLD * 0.6f
    bands[0] = follow(bands[0], shape(raw, bt), dt, G.ATTACK, G.RELEASE * 1.15f)
    bands[1] = follow(bands[1], shape(b1, bt), dt, G.ATTACK, G.RELEASE * 1.15f)
    bands[2] = follow(bands[2], shape(b2, bt), dt, G.ATTACK, G.RELEASE * 1.15f)

    // Motion: gathered into one beam while processing, sweeping side to side
    val target = if (processing) 1f else 0f
    gatherLin = if (gatherLin < target) min(target, gatherLin + dt / G.GATHER_TIME) else max(target, gatherLin - dt / G.GATHER_TIME)
    morph = smooth(gatherLin)
    if (morph <= 0f) sweepT = 0.0 else if (!reducedMotion) sweepT += dt
    val sweep = 2.0 * PI * sweepT / G.SWEEP_PERIOD
    val stretch = if (reducedMotion) 0f else abs(cos(sweep)).toFloat()

    val span = G.LOBE_SPAN * c.lobeSpacing
    val gather = 1f - morph * 0.6f
    maskWidth = 1f - morph * 0.45f
    val passWidth = 1f + morph * 0.3f * stretch

    // Idle breathing folded under the voice
    val breathe = if (reducedMotion) 0.5f else 0.5f + 0.5f * sin(2.0 * PI * t / G.BREATHE_DURATION).toFloat()
    val voiced = level + (1f - level) * c.idle * breathe
    val heldT = ((morph - 0.25f) / 0.75f).coerceIn(0f, 1f)
    val held = heldT * heldT * (3f - 2f * heldT)
    val eff = max(voiced, G.HELD_LEVEL * held)

    glow = 0.15f + 0.85f * eff
    h = 0.5f + c.reach * eff
    w = (0.85f + c.spread * eff) * passWidth

    // The sweep: sideways travel so the beam's centre reaches SWEEP_INSET from each side
    cx = if (reducedMotion || morph <= 0f) 0f else {
      val travel = max(0f, widthDp / 2f - c.sweepInset)
      sin(sweep).toFloat() * morph * travel / w
    }

    // Flow: the spectrum slides sideways as the voice comes in
    if (c.flow != 0f && !reducedMotion) {
      phase = ((phase + c.flow * eff * dt) % span + span) % span
    }

    lift = max(0f, c.bend) * eff
    bendA = if (c.bend > 0f) min(1f, lift / c.bend) else 0f

    // Corner arcs while gathered
    val arcRadius = paintedRadius(radiusDp, widthDp, heightDp)
    val cornerBlend = morph * G.CORNER_FOLLOW
    val lobeReach = 30f * c.scale * w
    cy = -cornerLift(widthDp / 2f + cx * w, widthDp, arcRadius, lobeReach * 1.4f) * cornerBlend
    for (i in 0 until G.LOBES) {
      val x = wrapX(G.lobeX[i] * c.lobeSpacing + phase.toFloat(), span)
      lobeX[i] = x * gather
      lobeL[i] = (0.6f + 0.7f * bands[G.lobeBand[i]]) * edgeEnvelope(x, span)
      lobeY[i] = if (cornerBlend > 0f) {
        -cornerLift(widthDp / 2f + (cx + x * gather) * w, widthDp, arcRadius, lobeReach) * cornerBlend
      } else {
        0f
      }
    }

    // Hue drift
    val hue = if (reducedMotion || c.hueRange == 0f) 0f else {
      -c.hueRange + 2f * c.hueRange * pingPong(ts / c.hueDuration)
    }
    VoiceColorMatrix.compose(matrix, c.hueBase + hue, c.brightness, c.saturation)
  }

  companion object {
    /** Noise gate then soft saturation, so a shout rounds off instead of clipping. */
    fun shape(raw: Float, threshold: Float): Float {
      if (raw <= threshold) return 0f
      val t = (raw - threshold) / max(0.001f, 1f - threshold)
      return ((1f - exp(-3f * t)) / (1f - exp(-3f))).coerceIn(0f, 1f)
    }

    /** One-pole follower: fast up (attack), slow down (release). */
    fun follow(prev: Float, target: Float, dt: Float, attack: Float, release: Float): Float {
      val tau = if (target > prev) attack else release
      return prev + (target - prev) * (1f - exp(-dt / max(0.001f, tau)))
    }

    /** Wraps a lobe offset into [-span/2, span/2). */
    fun wrapX(x: Float, span: Float): Float {
      val half = span / 2f
      val m = (x + half) % span
      return (if (m < 0f) m + span else m) - half
    }

    /** Full at the centre, gone at the wrap edge so a lobe never pops sides. */
    fun edgeEnvelope(x: Float, span: Float): Float {
      val t = x / (span / 2f + 4f)
      return max(0f, 1f - t * t)
    }

    fun pingPong(phase: Float): Float = (1f - cos(2f * PI.toFloat() * phase)) / 2f

    fun smooth(x: Float): Float = x * x * (3f - 2f * x)

    /** The radius the host actually paints (clamped to half its box). */
    fun paintedRadius(radius: Float, cw: Float, ch: Float): Float = max(0f, min(radius, min(cw / 2f, ch / 2f)))

    /** How far above the bottom edge the outline sits at [x]. */
    fun cornerLift(x: Float, cw: Float, radius: Float, influence: Float = 0f): Float {
      if (radius <= 0f) return 0f
      val d = min(x, cw - x) - influence
      if (d >= radius) return 0f
      if (d <= 0f) return radius
      val dx = radius - d
      return radius - sqrt(max(0f, radius * radius - dx * dx))
    }

    /** The band's organic bell, exp(-(|t| / σ)^p): 1 at the centre and exactly 0 at the ends. */
    fun bell(t: Float, p: Float, sigma: Float, skew: Float): Float {
      val side = if (t < 0f) 1f - skew else 1f + skew
      val s = max(0.05f, sigma * side)
      val v = exp(-(abs(t) / s).pow(p))
      val tail = exp(-(1f / s).pow(p))
      return max(0f, (v - tail) / (1f - tail))
    }

    /** The band's ends rise again toward the corners. */
    fun tailLift(dist: Float, edge: Float, lift: Float, position: Float, curve: Float): Float {
      if (lift <= 0f || edge <= 0f) return 0f
      val start = edge * position.coerceIn(0f, 0.98f)
      if (dist <= start) return 0f
      val u = min(1f, (dist - start) / max(1f, edge - start))
      return lift * u.pow(max(0.5f, curve))
    }
  }
}
