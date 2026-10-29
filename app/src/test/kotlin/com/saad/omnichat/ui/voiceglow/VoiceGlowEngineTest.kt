package com.saad.omnichat.ui.voiceglow

import com.saad.omnichat.ui.voiceglow.VoiceGlowEngine.Companion.bell
import com.saad.omnichat.ui.voiceglow.VoiceGlowEngine.Companion.cornerLift
import com.saad.omnichat.ui.voiceglow.VoiceGlowEngine.Companion.follow
import com.saad.omnichat.ui.voiceglow.VoiceGlowEngine.Companion.shape
import com.saad.omnichat.ui.voiceglow.VoiceGlowEngine.Companion.wrapX
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp

class VoiceGlowEngineTest {
  private val eps = 1e-4f
  private val dark = VoiceGlowConfig.of(VoiceGlowType.Default, true)

  @Test fun shapeGatesAndSaturates() {
    assertEquals(0f, shape(0.015f, 0.015f), eps)
    assertEquals(1f, shape(1f, 0.015f), eps)
    // half way past the gate: (1 - e^-1.5) / (1 - e^-3)
    val mid = 0.015f + (1f - 0.015f) / 2f
    assertEquals(((1 - exp(-1.5)) / (1 - exp(-3.0))).toFloat(), shape(mid, 0.015f), eps)
  }

  @Test fun followUsesAttackUpAndReleaseDown() {
    // one time constant reaches 1 - 1/e
    assertEquals(1f - exp(-1f), follow(0f, 1f, 0.325f, 0.325f, 0.86f), eps)
    assertEquals(exp(-1f), follow(1f, 0f, 0.86f, 0.325f, 0.86f), eps)
  }

  @Test fun wrapKeepsLobesOnTheRing() {
    assertEquals(-100f, wrapX(100f + 200f, 200f), eps)
    assertEquals(99f, wrapX(-101f, 200f), eps)
    assertEquals(0f, wrapX(0f, 200f), eps)
  }

  @Test fun bellIsOneAtCentreAndZeroAtEnds() {
    assertEquals(1f, bell(0f, 1.75f, 0.87f, 0.12f), eps)
    assertEquals(0f, bell(1f, 1.75f, 0.87f, 0.12f), eps)
    assertEquals(0f, bell(-1f, 1.75f, 0.87f, 0.12f), eps)
    // positive skew widens the right side
    assertTrue(bell(0.5f, 1.75f, 0.87f, 0.12f) > bell(-0.5f, 1.75f, 0.87f, 0.12f))
  }

  @Test fun cornerLiftFollowsTheArc() {
    assertEquals(0f, cornerLift(100f, 300f, 28f), eps)
    assertEquals(28f, cornerLift(0f, 300f, 28f), eps)
    assertEquals(28f, cornerLift(14f, 300f, 28f, influence = 20f), eps)
  }

  @Test fun envelopeRisesHoldsAndFadesOut() {
    val e = VoiceGlowEngine().apply { widthDp = 360f; heightDp = 64f; radiusDp = 28f }
    val dt = 1f / 60f
    repeat(120) { e.step(dt, 1f, active = true, processing = false, reducedMotion = false, dark) }
    assertTrue(e.level > 0.95f)
    assertEquals(1f, e.presence, eps)
    assertTrue(e.glow > 0.95f)
    // silence: release (0.86 s) lets it settle
    repeat(240) { e.step(dt, 0f, active = true, processing = false, reducedMotion = false, dark) }
    assertTrue(e.level < 0.02f)
    // inactive: gone within the 0.5 s fade
    repeat(31) { e.step(dt, 0f, active = false, processing = false, reducedMotion = false, dark) }
    assertFalse(e.visible)
  }

  @Test fun processingGathersAndSweeps() {
    val e = VoiceGlowEngine().apply { widthDp = 360f; heightDp = 64f; radiusDp = 28f }
    val dt = 1f / 60f
    var maxCx = 0f
    repeat(240) {
      e.step(dt, 0f, active = true, processing = true, reducedMotion = false, dark)
      maxCx = maxOf(maxCx, abs(e.cx * e.w))
    }
    assertEquals(1f, e.morph, eps)
    // the beam's centre reaches SWEEP_INSET from the sides
    assertEquals(180f - dark.sweepInset, maxCx, 2f)
  }

  @Test fun reducedMotionHoldsTheBeamCentred() {
    val e = VoiceGlowEngine().apply { widthDp = 360f; heightDp = 64f; radiusDp = 28f }
    repeat(240) { e.step(1f / 60f, 0.5f, active = true, processing = true, reducedMotion = true, dark) }
    assertEquals(0f, e.cx, eps)
  }

  @Test fun defaultTypeMatchesTheTunedDefaults() {
    val g = VoiceGeometry.resolve(VoiceGlowType.Default, dark = true)
    assertEquals(VoiceGeometry(), g)
    assertEquals(1.15f, dark.brightness, eps)
    assertEquals(1.2f, dark.saturation, eps)
    val light = VoiceGeometry.resolve(VoiceGlowType.Default, dark = false)
    assertEquals(1.8f, light.reach, eps)
    assertEquals(0.8f, light.spread, eps)
    assertEquals(1.8f, light.coreLight, eps)
    assertEquals(1.7f, light.bandStrength, eps)
    assertEquals(0.8f, VoiceGlowConfig.of(VoiceGlowType.Default, false).strength, eps)
  }

  @Test fun mobileResolvesLikePresetsTs() {
    val g = VoiceGeometry.resolve(VoiceGlowType.Mobile, dark = true)
    assertEquals(1.25f, g.scale, eps)
    assertEquals(3f, g.reach, eps)
    assertEquals(0.45f, g.spread, eps)
    assertEquals(60f, g.flow, eps)
    assertEquals(70f, g.bend, eps)
    assertEquals(1.8f, g.bandStrength, eps)
    assertEquals(-50f, g.bandOffset, eps)
    assertEquals(0.62f, g.bandTail, eps)
    assertEquals(0.42f, g.bandTailPosition, eps)
    assertEquals(22f, g.bandTailOverflow, eps)
    assertEquals(2.1f, g.glowHeight, eps)
    assertEquals(1.25f, g.rangeWidth, eps)
    assertEquals(1.2f, g.rangeHeight, eps)
    assertEquals(0.18f, g.idle, eps)

    val c = VoiceGlowConfig.of(VoiceGlowType.Mobile, true)
    assertEquals(1f, c.strength, eps)
    assertEquals(1.2f, c.brightness, eps)
    assertEquals(1.5f, c.saturation, eps)
    // scale multiplies every px dimension, as VoiceBeam.tsx does
    assertEquals(2.1f * 1.25f, c.glowHeight, eps)
    assertEquals(1.25f * 1.25f, c.rangeWidth, eps)
    assertEquals(-50f * 1.25f, c.bandOffset, eps)
    assertEquals(60f * 1.25f, c.flow, eps)

    val light = VoiceGeometry.resolve(VoiceGlowType.Mobile, dark = false)
    assertEquals(3f, light.reach, eps) // the type's reach wins over the light theme's
    assertEquals(0.45f, light.spread, eps)
    assertEquals(1.7f, light.bandStrength, eps)
    assertEquals(1.8f, light.coreLight, eps)
    val lc = VoiceGlowConfig.of(VoiceGlowType.Mobile, false)
    assertEquals(1f, lc.strength, eps)
    assertEquals(0.95f, lc.brightness, eps)
    assertEquals(1.6f, lc.saturation, eps)
  }

  @Test fun pillResolvesLikePresetsTs() {
    val g = VoiceGeometry.resolve(VoiceGlowType.Pill, dark = false)
    assertEquals(0.45f, g.scale, eps)
    assertEquals(0f, g.flow, eps)
    assertEquals(0f, g.bandTail, eps)
    assertEquals(2f, g.bandStrength, eps)
    assertEquals(1.35f, g.reach, eps)
    assertEquals(0.95f, VoiceGlowConfig.of(VoiceGlowType.Pill, false).brightness, eps)
    assertEquals(1.35f, VoiceGlowConfig.of(VoiceGlowType.Pill, true).brightness, eps)
  }

  @Test fun mobileRisesHighOnAPhoneScreen() {
    val c = VoiceGlowConfig.of(VoiceGlowType.Mobile, true)
    val e = VoiceGlowEngine().apply { widthDp = 400f; heightDp = 860f; radiusDp = 0f }
    repeat(120) { e.step(1f / 60f, 1f, active = true, processing = false, reducedMotion = false, c) }
    // the bloom's mask ceiling (130 dp × rangeHeight × h + lift) reaches well past a quarter of the screen
    val ceiling = 130f * c.rangeHeight * e.h + e.lift
    assertTrue(ceiling > 860f / 4f)
    var maxCx = 0f
    repeat(400) {
      e.step(1f / 60f, 0f, active = true, processing = true, reducedMotion = false, c)
      maxCx = maxOf(maxCx, abs(e.cx * e.w))
    }
    assertEquals(200f - c.sweepInset, maxCx, 2f)
  }
}
