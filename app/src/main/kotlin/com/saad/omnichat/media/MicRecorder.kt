package com.saad.omnichat.media

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.concurrent.thread

/** Records mono float PCM from the mic. Caller must hold RECORD_AUDIO. */
class MicRecorder(private val rate: Int) {
  @Volatile private var running = false
  private var worker: Thread? = null
  private val chunks = ArrayList<FloatArray>()

  val isRecording get() = running

  // Voice detection, done on every 50 ms chunk and timed in samples (a busy UI thread mustn't miss a word):
  // a quarter second of voice well above the room's level starts an utterance; before that only the last second is
  // kept, so a long wait doesn't become audio for the model to read.
  /** Samples recorded since [start]. */
  @Volatile var position = 0L
    private set
  /** Where an utterance was first heard, or -1. */
  @Volatile var heardAt = -1L
    private set
  /** Where the voice was last heard. */
  @Volatile var lastVoiceAt = 0L
    private set
  private var floor = 1f
  private var voiced = 0
  private var unvoiced = 0

  /** Live input level 0..1 (dBFS mapped from -50..-10 dB), for visualisers. */
  @Volatile
  var level = 0f
    private set

  /**
   * Starts recording; false when the mic can't be opened (permission revoked, or another app such as a call holds
   * it), which used to throw and close the app.
   */
  @SuppressLint("MissingPermission")
  fun start(): Boolean {
    if (running) return true
    val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
    val rec =
      runCatching {
          AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, maxOf(minBuf, rate))
            .also {
              check(it.state == AudioRecord.STATE_INITIALIZED) { "mic not initialized" }
              it.startRecording()
              check(it.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "mic busy" }
            }
        }
        .onFailure { com.saad.omnichat.AppLog.w("mic: ${it.message}") }
        .getOrNull() ?: return false
    chunks.clear()
    position = 0L
    heardAt = -1L
    lastVoiceAt = 0L
    floor = 1f
    voiced = 0
    unvoiced = 0
    running = true
    worker =
      thread(name = "mic") {
        val buf = FloatArray(rate / 20)
        while (running) {
          val n = rec.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
          if (n > 0) {
            synchronized(chunks) { chunks.add(buf.copyOf(n)) }
            var sum = 0.0
            for (i in 0 until n) sum += buf[i] * buf[i]
            val db = 10 * kotlin.math.log10(sum / n + 1e-12)
            level = ((db + 50) / 40).toFloat().coerceIn(0f, 1f)
            detectVoice(level, n)
          } else if (n < 0) {
            // the audio server went away (ERROR_DEAD_OBJECT): reading again would spin at full speed, getting nothing
            com.saad.omnichat.AppLog.w("mic: read failed ($n), stopping")
            running = false
          }
        }
        level = 0f
        rec.stop()
        rec.release()
      }
    return true
  }

  /** Stops and returns everything recorded since the last [take]. */
  fun stop(): FloatArray {
    end()
    return take()
  }

  /** Stops recording; what is left can still be collected with [take]. */
  fun end() {
    running = false
    worker?.join()
    worker = null
  }

  private fun detectVoice(level: Float, n: Int) {
    // the room's level follows quiet at once and noise only slowly, so a long sentence doesn't become the floor
    floor = if (level < floor) level else floor + (level - floor) * 0.01f
    position += n
    if (level > 0.3f && level > floor + 0.2f) {
      voiced += n
      unvoiced = 0
      lastVoiceAt = position
      if (heardAt < 0 && voiced >= rate / 4) heardAt = position - voiced
    } else {
      unvoiced += n
      // a gap between syllables doesn't restart the count; a third of a second of quiet does
      if (unvoiced > rate / 3) voiced = 0
      if (heardAt < 0 && voiced == 0) keepLast(rate)
    }
  }

  /** Forgets all but the last [samples] recorded (the quiet before someone starts talking). */
  fun keepLast(samples: Int) {
    synchronized(chunks) {
      var kept = 0
      var i = chunks.size
      while (i > 0 && kept < samples) kept += chunks[--i].size
      repeat(i) { chunks.removeAt(0) }
    }
  }

  /** Returns and forgets what was recorded so far, without stopping (live transcription reads it in pieces). */
  fun take(): FloatArray {
    synchronized(chunks) {
      val out = FloatArray(chunks.sumOf { it.size })
      var off = 0
      for (c in chunks) {
        c.copyInto(out, off)
        off += c.size
      }
      chunks.clear()
      return out
    }
  }
}
