package com.saad.omnichat.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Plays the model's speech as it is generated: chunks are queued and written to a streaming AudioTrack on an IO
 * thread, so playback starts with the first frames instead of after the whole reply.
 */
class SpeechPlayer(private val scope: CoroutineScope) {
  private var track: AudioTrack? = null
  private var rate = 0
  private var queue = Channel<FloatArray>(Channel.UNLIMITED)
  private var writer: Job? = null

  /** Loudness of the chunk being played, 0..1, for the glow. */
  @Volatile var level = 0f
    private set

  fun play(pcm: FloatArray, sampleRate: Int) {
    if (track == null || rate != sampleRate) start(sampleRate)
    queue.trySend(pcm)
  }

  /** Lets queued speech finish, then releases the track. */
  fun finish() {
    queue.close()
  }

  /** Stops at once (the user interrupted). */
  fun stop() {
    queue.close()
    writer?.cancel()
    track?.runCatching { pause(); flush(); stop(); release() }
    track = null
    level = 0f
  }

  val isPlaying: Boolean get() = writer?.isActive == true

  private fun start(sampleRate: Int) {
    stop()
    rate = sampleRate
    queue = Channel(Channel.UNLIMITED)
    val t =
      AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT), sampleRate * 4 / 2))
        .build()
    track = t
    val q = queue
    writer =
      scope.launch(Dispatchers.IO) {
        // Speech is generated about as fast as it plays, so playback waits for a lead first. If it catches up with
        // the generator anyway, it pauses cleanly and waits for twice the lead (up to 4 s) instead of stuttering.
        var lead = sampleRate * 8 / 10
        val held = ArrayList<FloatArray>()
        var heldSamples = 0
        var written = 0L
        var playing = false
        var closed = false
        fun write(chunk: FloatArray) {
          var sum = 0.0
          for (s in chunk) sum += s * s
          level = (sqrt(sum / chunk.size.coerceAtLeast(1)) * 6).toFloat().coerceIn(0f, 1f)
          t.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
          written += chunk.size
        }
        fun ahead() = written - (t.playbackHeadPosition.toLong() and 0xFFFFFFFFL)
        while (true) {
          val r = q.tryReceive()
          val chunk = r.getOrNull()
          if (r.isClosed) closed = true
          when {
            chunk != null && playing -> write(chunk)
            chunk != null -> {
              held += chunk
              heldSamples += chunk.size
            }
            closed && !playing && held.isEmpty() -> {
              // paused for a lead that will never come: play what is left
              if (ahead() > 0) {
                t.play()
                playing = true
              }
              break
            }
            closed && playing -> break
            playing && ahead() < sampleRate / 10 -> {
              // about to run dry: pause until there is a longer lead
              t.pause()
              playing = false
              lead = minOf(lead * 2, sampleRate * 4)
            }
            else -> kotlinx.coroutines.delay(20)
          }
          if (!playing && held.isNotEmpty() && (heldSamples >= lead || closed)) {
            t.play()
            playing = true
            held.forEach(::write)
            held.clear()
            heldSamples = 0
          }
        }
        // let the buffered tail play out
        while (playing && ahead() > 0 && isActive) kotlinx.coroutines.delay(50)
        level = 0f
        t.runCatching { stop(); release() }
        if (track === t) track = null
      }
  }
}
