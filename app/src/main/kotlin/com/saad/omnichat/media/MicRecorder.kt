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
