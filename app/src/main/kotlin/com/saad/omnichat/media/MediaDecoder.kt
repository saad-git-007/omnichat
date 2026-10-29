package com.saad.omnichat.media

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.saad.omnichat.llm.Media
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

object MediaDecoder {
  /** Decodes any image Android can read (JPEG, PNG, HEIC, WebP...) to packed RGB, longest side <= [maxSide]. */
  /** The picture at [uri], scaled down to at most [maxSide] pixels a side. */
  fun bitmap(resolver: ContentResolver, uri: Uri, maxSide: Int): Bitmap =
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
      decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
      val (w, h) = info.size.width to info.size.height
      val scale = min(1f, maxSide.toFloat() / max(w, h))
      if (scale < 1f) decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
    }

  fun image(resolver: ContentResolver, uri: Uri, maxSide: Int = 1024): Media.Image {
    val bmp = bitmap(resolver, uri, maxSide)
    val argb = if (bmp.config == Bitmap.Config.ARGB_8888) bmp else bmp.copy(Bitmap.Config.ARGB_8888, false)
    val w = argb.width
    val h = argb.height
    val px = IntArray(w * h)
    argb.getPixels(px, 0, w, 0, 0, w, h)
    val rgb = ByteArray(w * h * 3)
    for (i in px.indices) {
      val c = px[i]
      rgb[i * 3] = (c shr 16).toByte()
      rgb[i * 3 + 1] = (c shr 8).toByte()
      rgb[i * 3 + 2] = c.toByte()
    }
    return Media.Image(rgb, w, h)
  }

  /** Decodes any audio Android can read (WAV, MP3, M4A/AAC, FLAC, OGG/Opus...) to mono float PCM at [rate] Hz. */
  fun audio(resolver: ContentResolver, uri: Uri, rate: Int, maxSeconds: Int = 600): Media.Audio =
    AudioStream(resolver, uri, rate).use { stream ->
      val pcm = stream.read(rate * maxSeconds) ?: throw IllegalArgumentException("No audio could be decoded")
      Media.Audio(pcm, truncated = !stream.finished)
    }

  /** Length in seconds from the container, without decoding (0 if unknown). */
  fun audioSeconds(resolver: ContentResolver, uri: Uri): Double {
    val extractor = MediaExtractor()
    return try {
      resolver.openFileDescriptor(uri, "r")!!.use { extractor.setDataSource(it.fileDescriptor) }
      (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
        .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        ?.takeIf { it.containsKey(MediaFormat.KEY_DURATION) }
        ?.getLong(MediaFormat.KEY_DURATION)?.div(1e6) ?: 0.0
    } finally {
      extractor.release()
    }
  }

  fun resample(src: FloatArray, from: Int, to: Int): FloatArray {
    if (from == to || src.isEmpty()) return src
    val n = (src.size.toLong() * to / from).toInt()
    val step = from.toDouble() / to
    return FloatArray(n) { i ->
      val p = i * step
      val j = p.toInt()
      val f = (p - j).toFloat()
      val a = src[j]
      val b = if (j + 1 < src.size) src[j + 1] else a
      a + (b - a) * f
    }
  }
}

/** Linear-interpolation resampler fed one sample at a time. */
private class StreamResampler(val from: Int, to: Int) {
  private val step = from.toDouble() / to
  private var t = 0.0 // next output position, in input samples
  private var i = -1L
  private var prev = 0f

  inline fun push(x: Float, emit: (Float) -> Unit) {
    i++
    if (i == 0L) prev = x
    while (t <= i) {
      val frac = (t - (i - 1)).toFloat()
      emit(prev + (x - prev) * frac)
      t += step
    }
    prev = x
  }
}

/**
 * Pull-based decoder: [read] returns the next chunk of mono float PCM at [rate] Hz, so a 2-hour recording can be
 * processed piece by piece without ever holding it all in memory.
 */
class AudioStream(resolver: ContentResolver, uri: Uri, private val rate: Int) : AutoCloseable {
  private val extractor = MediaExtractor()
  private val codec: MediaCodec
  private val info = MediaCodec.BufferInfo()
  private var inputDone = false
  private var resampler: StreamResampler? = null
  private var carry = FloatArray(0) // resampled samples decoded past the last chunk's end
  var finished = false
    private set

  init {
    resolver.openFileDescriptor(uri, "r")!!.use { extractor.setDataSource(it.fileDescriptor) }
    val track =
      (0 until extractor.trackCount).firstOrNull {
        extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
      } ?: run { extractor.release(); throw IllegalArgumentException("No audio track found") }
    extractor.selectTrack(track)
    val format = extractor.getTrackFormat(track)
    codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
    format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
    codec.configure(format, null, null, 0)
    codec.start()
  }

  /** Up to [maxSamples] samples, or null once the stream is exhausted. */
  fun read(maxSamples: Int): FloatArray? {
    if (finished && carry.isEmpty()) return null
    val out = FloatArray(maxSamples)
    var n = minOf(carry.size, maxSamples)
    carry.copyInto(out, 0, 0, n)
    carry = carry.copyOfRange(n, carry.size)
    val extra = ArrayList<Float>()
    val emit = { v: Float -> if (n < maxSamples) out[n++] = v else extra += v }
    while (n < maxSamples && !finished) {
      if (!inputDone) {
        val inIdx = codec.dequeueInputBuffer(10_000)
        if (inIdx >= 0) {
          val size = extractor.readSampleData(codec.getInputBuffer(inIdx)!!, 0)
          if (size < 0) {
            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            inputDone = true
          } else {
            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
            extractor.advance()
          }
        }
      }
      val outIdx = codec.dequeueOutputBuffer(info, 10_000)
      if (outIdx >= 0) {
        // decoders may ignore the float request; the per-buffer format is authoritative (no key = 16-bit)
        val of = codec.getOutputFormat(outIdx)
        val srcRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val rs = resampler?.takeIf { it.from == srcRate } ?: StreamResampler(srcRate, rate).also { resampler = it }
        val floatOut =
          of.containsKey(MediaFormat.KEY_PCM_ENCODING) && of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
        val buf = codec.getOutputBuffer(outIdx)!!.order(ByteOrder.nativeOrder())
        buf.position(info.offset)
        buf.limit(info.offset + info.size)
        if (floatOut) {
          val fb = buf.asFloatBuffer()
          while (fb.remaining() >= channels) {
            var s = 0f
            repeat(channels) { s += fb.get() }
            rs.push(s / channels, emit)
          }
        } else {
          val sb = buf.asShortBuffer()
          while (sb.remaining() >= channels) {
            var s = 0f
            repeat(channels) { s += sb.get() / 32768f }
            rs.push(s / channels, emit)
          }
        }
        codec.releaseOutputBuffer(outIdx, false)
        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) finished = true
      }
    }
    if (extra.isNotEmpty()) carry += extra.toFloatArray()
    return if (n == 0) null else out.copyOf(n)
  }

  override fun close() {
    runCatching { codec.stop() }
    codec.release()
    extractor.release()
  }
}
