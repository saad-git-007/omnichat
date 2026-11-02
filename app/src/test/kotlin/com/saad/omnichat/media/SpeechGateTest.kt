package com.saad.omnichat.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechGateTest {
  private val rate = 16000
  private val rnd = java.util.Random(1)
  private fun noise(seconds: Int, level: Double) = FloatArray(rate * seconds) { (rnd.nextGaussian() * level).toFloat() }

  @Test
  fun steadyRoomNoiseIsNotSpeech() {
    // louder than the old single-peak check let through (0.01), but the same all the way
    assertFalse(SpeechGate.hasSpeech(noise(12, 0.02), rate))
    assertFalse(SpeechGate.hasSpeech(FloatArray(rate * 12), rate))
  }

  @Test
  fun talkingOverNoiseIsSpeech() {
    val pcm = noise(12, 0.01)
    // syllables: 150 ms bursts of a voiced tone, four a second, for the middle six seconds
    for (i in rate * 3 until rate * 9) {
      val inSyllable = (i % (rate / 4)) < rate * 15 / 100
      if (inSyllable) pcm[i] += (0.2 * kotlin.math.sin(2 * Math.PI * 180 * i / rate)).toFloat()
    }
    assertTrue(SpeechGate.speechShare(pcm, rate).toString(), SpeechGate.hasSpeech(pcm, rate))
  }

  @Test
  fun silencePhrases() {
    assertTrue(SpeechGate.isSilencePhrase("Thank you."))
    assertTrue(SpeechGate.isSilencePhrase(" Thanks for watching! "))
    assertFalse(SpeechGate.isSilencePhrase("Thank you to the support team for that work."))
  }
}
