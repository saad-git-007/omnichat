package com.saad.omnichat.memory

import android.content.Context
import com.saad.omnichat.AppLog
import com.saad.omnichat.history.SavedChat
import com.saad.omnichat.llm.Embedder
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One searchable passage: [key] is its place in the source (turn or chunk number). */
class Passage(val key: Int, val text: String, val vector: FloatArray)

data class Found(val source: String, val key: Int, val text: String, val score: Float)

/**
 * Meaning search over saved chats and long documents, with EmbeddingGemma vectors kept in small binary files under
 * filesDir/index (one per chat or document). Nothing leaves the phone.
 */
class SemanticIndex(context: Context, private val embedder: Embedder) {
  private val dir = File(context.filesDir, "index").apply { mkdirs() }
  private val cache = HashMap<String, List<Passage>>()
  private val lock = Mutex()

  // ---- chats ----

  /** Adds the turns of [chat] not indexed yet: each user message with the reply to it. */
  suspend fun indexChat(chat: SavedChat) {
    val name = "chat-${chat.id}"
    val have = load(name)
    val known = have.mapTo(HashSet()) { it.key }
    val added = ArrayList<Passage>()
    chat.messages.forEachIndexed { i, m ->
      if (m.role != "user" || i in known) return@forEachIndexed
      val answer = chat.messages.getOrNull(i + 1)?.takeIf { it.role == "assistant" }?.text ?: return@forEachIndexed
      val docs = m.attachments.joinToString("") { "[${it.label}] " }
      val text = ("User: $docs${m.text}\nAssistant: $answer").take(PASSAGE_CHARS)
      embedder.passage(text, chat.title)?.let { added += Passage(i, text, it) }
    }
    if (added.isNotEmpty()) save(name, have + added)
  }

  suspend fun deleteChat(id: Long) = lock.withLock {
    cache.remove("chat-$id")
    File(dir, "chat-$id.idx").delete()
  }

  fun indexedChats(): Set<Long> = dir.list().orEmpty().filter { it.startsWith("chat-") }.mapNotNull { it.removePrefix("chat-").removeSuffix(".idx").toLongOrNull() }.toSet()

  /** The best-matching turns across saved chats, best first; [source] is the chat id. */
  suspend fun searchChats(query: String, exclude: Long?, k: Int = 4): List<Found> {
    val q = embedder.query(query) ?: return emptyList()
    return indexedChats().filter { it != exclude }.flatMap { id ->
      load("chat-$id").map { Found(id.toString(), it.key, it.text, Embedder.cosine(q, it.vector)) }
    }.sortedByDescending { it.score }.take(k).filter { it.score >= MIN_SCORE }
  }

  // ---- documents ----

  fun documentKey(name: String, text: String): String =
    MessageDigest.getInstance("SHA-1").digest((name + text.length + text.take(4000)).toByteArray()).joinToString("") { "%02x".format(it) }.take(20)

  fun hasDocument(key: String) = File(dir, "doc-$key.idx").exists()

  /** Splits [text] into overlapping passages and embeds them; [onProgress] gets (done, total). */
  suspend fun indexDocument(key: String, name: String, text: String, onProgress: (Int, Int) -> Unit) {
    if (hasDocument(key)) return
    val chunks = chunk(text)
    val out = ArrayList<Passage>(chunks.size)
    for ((i, c) in chunks.withIndex()) {
      currentCoroutineContext().ensureActive()
      embedder.passage(c, name)?.let { out += Passage(i, c, it) }
      if (i % 4 == 0) onProgress(i, chunks.size)
    }
    save("doc-$key", out)
    AppLog.i("indexed $name: ${out.size} passages")
  }

  /** The passages of a document that best answer [query], in reading order. */
  suspend fun searchDocument(key: String, query: String, k: Int = 6): List<Found> {
    val q = embedder.query(query) ?: return emptyList()
    return load("doc-$key").map { Found(key, it.key, it.text, Embedder.cosine(q, it.vector)) }
      .sortedByDescending { it.score }.take(k).sortedBy { it.key }
  }

  // ---- storage ----

  private suspend fun load(name: String): List<Passage> = lock.withLock {
    cache[name]?.let { return@withLock it }
    val f = File(dir, "$name.idx")
    val list =
      if (!f.exists()) emptyList()
      else withContext(Dispatchers.IO) {
        runCatching {
          DataInputStream(f.inputStream().buffered()).use { inp ->
            check(inp.readInt() == MAGIC)
            val dims = inp.readInt()
            check(dims == Embedder.DIMS) { "made with another model" }
            List(inp.readInt()) {
              val key = inp.readInt()
              val text = inp.readUTF()
              Passage(key, text, FloatArray(dims) { inp.readFloat() })
            }
          }
        }.onFailure { AppLog.w("index $name unreadable: ${it.message}") }.getOrDefault(emptyList())
      }
    cache[name] = list
    list
  }

  private suspend fun save(name: String, list: List<Passage>) = lock.withLock {
    cache[name] = list
    withContext(Dispatchers.IO) {
      val tmp = File(dir, "$name.idx.part")
      DataOutputStream(tmp.outputStream().buffered()).use { out ->
        out.writeInt(MAGIC)
        out.writeInt(Embedder.DIMS)
        out.writeInt(list.size)
        list.forEach { p ->
          out.writeInt(p.key)
          out.writeUTF(p.text.take(PASSAGE_CHARS))
          p.vector.forEach(out::writeFloat)
        }
      }
      tmp.renameTo(File(dir, "$name.idx"))
    }
  }

  companion object {
    private const val MAGIC = 0x4F4D4931 // "OMI1"
    private const val PASSAGE_CHARS = 1600
    private const val CHUNK = 1200
    private const val OVERLAP = 200
    private const val MIN_SCORE = 0.55f // EmbeddingGemma 2: related turns score ~0.7, unrelated ~0.5

    /** ~1200-character passages that end at a paragraph or sentence break where possible, overlapping a little. */
    fun chunk(text: String): List<String> {
      val out = ArrayList<String>()
      var start = 0
      while (start < text.length) {
        var end = minOf(start + CHUNK, text.length)
        if (end < text.length) {
          val window = text.substring(start + CHUNK / 2, end)
          val cut = maxOf(window.lastIndexOf("\n\n"), window.lastIndexOf(". "), window.lastIndexOf('\n'))
          if (cut > 0) end = start + CHUNK / 2 + cut + 1
        }
        text.substring(start, end).trim().takeIf { it.isNotEmpty() }?.let(out::add)
        if (end >= text.length) break
        start = maxOf(end - OVERLAP, start + 1)
      }
      return out
    }
  }
}
