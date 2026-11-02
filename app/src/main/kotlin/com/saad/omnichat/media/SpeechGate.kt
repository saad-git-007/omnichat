package com.saad.omnichat.media

/**
 * Whether a block of microphone audio holds speech. Given a quiet room the model still writes something, mostly
 * "Thank you." again and again, so live transcribe only sends blocks where people are talking.
 */
object SpeechGate {
  private const val MIN_LEVEL = 0.005 // RMS: below this nothing is loud enough to be speech (a lecture across a room is ~0.01)
  private const val ABOVE_NOISE = 3.0 // a speech frame is this many times louder than the block's background
  const val MIN_SHARE = 0.05 // of the block: half a second of a 10 s block

  // what the model writes for silence and room noise
  private val SILENCE_PHRASES = setOf("thank you", "thank you very much", "thanks", "thanks for watching", "thank you for watching", "bye", "you", "okay", "ok")

  /** The share of 10 ms frames clearly louder than the block's own background noise (its quietest fifth). */
  fun speechShare(pcm: FloatArray, rate: Int): Double {
    val frame = (rate / 100).coerceAtLeast(1)
    val n = pcm.size / frame
    if (n == 0) return 0.0
    val rms = DoubleArray(n) { f ->
      var e = 0.0
      for (j in f * frame until (f + 1) * frame) e += pcm[j] * pcm[j]
      kotlin.math.sqrt(e / frame)
    }
    val floor = rms.sorted()[n / 5]
    val threshold = maxOf(MIN_LEVEL, floor * ABOVE_NOISE)
    return rms.count { it > threshold }.toDouble() / n
  }

  fun hasSpeech(pcm: FloatArray, rate: Int) = speechShare(pcm, rate) >= MIN_SHARE

  /** "Thank you." and the like, written for a block with little speech in it. */
  fun isSilencePhrase(text: String) = text.lowercase().replace(Regex("[^\\p{L} ]"), " ").trim().replace(Regex(" +"), " ") in SILENCE_PHRASES
}
