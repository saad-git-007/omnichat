package com.saad.omnichat.memory

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import com.saad.omnichat.AppLog
import com.saad.omnichat.llm.Embedder
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A photo or video on the phone. [taken] is epoch millis. */
data class GalleryItem(val id: Long, val video: Boolean, val taken: Long, val album: String, val name: String) {
  val uri: Uri
    get() = ContentUris.withAppendedId(if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
}

data class GalleryHit(val item: GalleryItem, val score: Float)

/**
 * Meaning search over the phone's photos and videos. Each one is seen once by EmbeddingGemma 2's image tower (videos
 * through their thumbnail frame) and kept as a vector in filesDir/index/gallery.idx, so a search is a quick scan of
 * those vectors: "my dog at the beach", "receipts", "snow" with no captions or tags. Indexing is resumable, works
 * newest first, handles one small thumbnail at a time and skips anything unreadable, so a broken file can't crash it.
 */
class GalleryIndex(private val context: Context, private val embedder: Embedder) {
  private val file = File(File(context.filesDir, "index").apply { mkdirs() }, "gallery.idx")
  // the item being embedded right now; still there on the next launch means that item took the app down with it
  private val current = File(file.parentFile, "gallery.current")
  private val lock = Mutex()
  // grown in place: copying the whole id array per photo was quadratic over a 30k-photo gallery
  private var ids = LongArray(0)
  private var count = 0
  private var vectors = ArrayList<FloatArray>()

  private fun addEntry(key: Long, v: FloatArray) {
    if (count == ids.size) ids = ids.copyOf(maxOf(256, count * 2))
    ids[count++] = key
    vectors.add(v)
  }
  private var loaded = false

  suspend fun indexedCount(): Int = lock.withLock { ensureLoaded(); count }

  /** Everything the gallery holds now, newest first (metadata only, no pixels). */
  suspend fun listMedia(): List<GalleryItem> =
    withContext(Dispatchers.IO) {
      val out = ArrayList<GalleryItem>()
      for (video in listOf(false, true)) {
        val uri = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val cols = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.BUCKET_DISPLAY_NAME, MediaStore.MediaColumns.DISPLAY_NAME)
        runCatching {
            context.contentResolver.query(uri, cols, null, null, null)?.use { c ->
              while (c.moveToNext()) {
                val taken = c.getLong(1).takeIf { it > 0 } ?: (c.getLong(2) * 1000)
                out += GalleryItem(c.getLong(0), video, taken, c.getString(3).orEmpty(), c.getString(4).orEmpty())
              }
            }
          }
          .onFailure { AppLog.w("gallery listing failed: ${it.message}") }
      }
      out.sortedByDescending { it.taken }
    }

  /**
   * Indexes what isn't indexed yet, among items taken after [since] (null = all). [paused] is polled between items (the chat is replying); [onProgress] gets
   * (done, total). Returns when everything is indexed or the coroutine is cancelled.
   */
  suspend fun indexAll(since: Long?, paused: () -> Boolean, onProgress: (Int, Int) -> Unit) {
    val media = listMedia().let { all -> if (since == null) all else all.filter { it.taken >= since } }
    val have = lock.withLock { ensureLoaded(); HashSet<Long>(count * 2).apply { for (i in 0 until count) add(ids[i]) } }
    // only a crash counts: Android closing the app in the background to free memory says nothing about the photo
    val culprit = runCatching { current.readText().trim().toLong() }.getOrNull()?.takeIf { com.saad.omnichat.CrashReports.lastExitWasCrash(context) }
    current.delete()
    if (culprit != null && culprit !in have) {
      // skip it for good instead of crashing on it every launch
      AppLog.w("gallery: skipping item $culprit, the app stopped while reading it last time")
      withContext(Dispatchers.IO) {
        DataOutputStream(FileOutputStream(file, true)).use { out ->
          if (file.length() == 0L) out.writeInt(MAGIC)
          out.writeLong(culprit)
          out.writeInt(0)
        }
      }
      lock.withLock { addEntry(culprit, FloatArray(0)) }
      have += culprit
    }
    val todo = media.filter { (if (it.video) -it.id else it.id) !in have }
    var done = media.size - todo.size
    onProgress(done, media.size)
    if (todo.isEmpty()) return
    AppLog.i("gallery: indexing ${todo.size} of ${media.size}")
    val t0 = System.currentTimeMillis()
    withContext(Dispatchers.IO) {
      DataOutputStream(FileOutputStream(file, true).buffered()).use { out ->
        if (file.length() == 0L) out.writeInt(MAGIC)
        for ((n, item) in todo.withIndex()) {
          currentCoroutineContext().ensureActive()
          while (paused()) delay(500)
          val key = if (item.video) -item.id else item.id
          current.writeText(key.toString())
          val v =
            runCatching { thumbnail(item)?.let { (rgb, w, h) -> embedder.image(rgb, w, h) } }
              .onFailure { AppLog.w("gallery: skipped ${item.name}: ${it.message}") }
              .getOrNull()
          // a failed item is still recorded (as an empty vector) so a bad file isn't retried every time
          out.writeLong(key)
          out.writeInt(v?.size ?: 0)
          v?.forEach(out::writeFloat)
          out.flush()
          lock.withLock { addEntry(key, v ?: FloatArray(0)) }
          done++
          if (n % 10 == 0) onProgress(done, media.size)
          if (n == 4 || (n + 1) % 100 == 0) AppLog.i("gallery: ${n + 1} indexed, ${(System.currentTimeMillis() - t0) / (n + 1)} ms per item")
        }
      }
    }
    current.delete()
    onProgress(done, media.size)
  }

  /**
   * Best matches for [query] (blank = any), newest first among equals; [from]/[to] are epoch millis bounds, [video]
   * null = both. Deleted items are dropped.
   */
  suspend fun search(query: String, from: Long?, to: Long?, video: Boolean?, limit: Int): List<GalleryHit> {
    val media = listMedia().associateBy { if (it.video) -it.id else it.id }
    val inRange = media.filterValues { (from == null || it.taken >= from) && (to == null || it.taken < to) && (video == null || it.video == video) }
    if (query.isBlank()) return inRange.values.take(limit).map { GalleryHit(it, 0f) }
    val q = embedder.query(query) ?: return emptyList()
    val scored = lock.withLock {
      ensureLoaded()
      (0 until count).mapNotNull { i ->
        val item = inRange[ids[i]] ?: return@mapNotNull null
        val v = vectors[i].takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        GalleryHit(item, Embedder.cosine(q, v))
      }
    }.sortedByDescending { it.score }
    // keep what is close to the best match: photo-to-text scores sit in a narrow band (~0.45-0.75)
    val best = scored.firstOrNull()?.score ?: return emptyList()
    return scored.filter { it.score >= best - 0.08f }.take(limit)
  }

  suspend fun clear() = lock.withLock {
    file.delete()
    ids = LongArray(0)
    count = 0
    vectors = ArrayList()
    loaded = true
  }

  /** A ~448 px thumbnail as RGB bytes (videos: their poster frame). */
  private fun thumbnail(item: GalleryItem): Triple<ByteArray, Int, Int>? {
    val bmp = context.contentResolver.loadThumbnail(item.uri, Size(THUMB, THUMB), null)
    try {
      val src = if (bmp.config == Bitmap.Config.ARGB_8888) bmp else bmp.copy(Bitmap.Config.ARGB_8888, false)
      val w = src.width
      val h = src.height
      val px = IntArray(w * h)
      src.getPixels(px, 0, w, 0, 0, w, h)
      if (src !== bmp) src.recycle()
      val rgb = ByteArray(w * h * 3)
      for (i in px.indices) {
        val p = px[i]
        rgb[i * 3] = (p shr 16).toByte()
        rgb[i * 3 + 1] = (p shr 8).toByte()
        rgb[i * 3 + 2] = p.toByte()
      }
      return Triple(rgb, w, h)
    } finally {
      bmp.recycle()
    }
  }

  private fun ensureLoaded() {
    if (loaded) return
    loaded = true
    if (!file.exists()) return
    // read in one go: 30k photos are ~90 MB, which float-by-float stream reads took many seconds to get through
    val buf = runCatching { java.io.RandomAccessFile(file, "r").use { f -> f.channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, f.length()) } }.getOrNull() ?: return
    val keys = ArrayList<Long>()
    val vecs = ArrayList<FloatArray>()
    var good = 0L
    runCatching {
        check(buf.int == MAGIC)
        good = 4
        while (buf.hasRemaining()) {
          val key = buf.long
          val n = buf.int
          check(n == 0 || n == Embedder.DIMS)
          val v = FloatArray(n)
          buf.asFloatBuffer().get(v)
          buf.position(buf.position() + 4 * n)
          vecs += v
          keys += key
          good += 12L + 4L * n
        }
      }
      .onFailure {
        // a torn tail (the app was killed mid-write): cut it off so later appends stay readable; it is indexed again
        AppLog.w("gallery index: kept ${keys.size} entries (${it.message})")
        runCatching { java.io.RandomAccessFile(file, "rw").use { f -> f.setLength(good) } }
      }
    ids = keys.toLongArray()
    count = ids.size
    vectors = vecs
  }

  companion object {
    private const val MAGIC = 0x4F4D4731 // "OMG1"
    private const val THUMB = 448

    fun describe(hits: List<GalleryHit>): String {
      val fmt = SimpleDateFormat("EEE d MMM yyyy HH:mm", Locale.US)
      return hits.withIndex().joinToString("\n") { (i, h) ->
        "${i + 1}. ${if (h.item.video) "video" else "photo"} ${h.item.name}, taken ${fmt.format(Date(h.item.taken))}, album ${h.item.album.ifEmpty { "?" }}"
      }
    }
  }
}
