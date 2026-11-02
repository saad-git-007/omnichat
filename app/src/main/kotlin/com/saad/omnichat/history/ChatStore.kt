package com.saad.omnichat.history

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** One attachment as remembered: images and audio by name only, documents with their extracted text. */
data class SavedAttachment(val kind: String, val label: String, val text: String? = null, val index: String? = null)

data class SavedMessage(val role: String, val text: String, val attachments: List<SavedAttachment> = emptyList())

data class SavedChat(val id: Long, val title: String, val updated: Long, val messages: List<SavedMessage>)

data class ChatSummary(val id: Long, val title: String, val updated: Long)

/** Conversations as one JSON file each in filesDir/chats; they never leave the phone. */
class ChatStore(context: Context) {
  private val dir = File(context.filesDir, "chats").apply { mkdirs() }

  private fun file(id: Long) = File(dir, "$id.json")

  suspend fun list(): List<ChatSummary> =
    withContext(Dispatchers.IO) {
      dir.listFiles { f -> f.extension == "json" }.orEmpty().mapNotNull { f ->
        runCatching {
            // only the header fields, which save() writes before the messages: a chat with documents attached is
            // megabytes of JSON, and parsing all of it for a title made the list slow to appear
            val o = header(f) ?: JSONObject(f.readText())
            ChatSummary(o.getLong("id"), o.optString("title", "Chat"), o.optLong("updated"))
          }
          .getOrNull()
      }.sortedByDescending { it.updated }
    }

  /** The fields before "messages", read from the start of [f]; null when they aren't there. */
  private fun header(f: File): JSONObject? {
    val start = f.bufferedReader().use { r -> CharArray(HEADER_CHARS).let { b -> String(b, 0, maxOf(0, r.read(b))) } }
    val at = start.indexOf(",\"messages\":")
    if (at < 0) return null
    return runCatching { JSONObject(start.substring(0, at) + "}") }.getOrNull()?.takeIf { it.has("id") }
  }

  suspend fun load(id: Long): SavedChat? =
    withContext(Dispatchers.IO) {
      runCatching {
          val o = JSONObject(file(id).readText())
          val msgs = o.getJSONArray("messages")
          SavedChat(
            o.getLong("id"),
            o.optString("title", "Chat"),
            o.optLong("updated"),
            (0 until msgs.length()).map { i ->
              val m = msgs.getJSONObject(i)
              val atts = m.optJSONArray("attachments") ?: JSONArray()
              SavedMessage(
                m.getString("role"),
                m.getString("text"),
                (0 until atts.length()).map { j ->
                  val a = atts.getJSONObject(j)
                  SavedAttachment(a.getString("kind"), a.getString("label"), if (a.has("text")) a.getString("text") else null, a.optString("index").ifEmpty { null })
                },
              )
            },
          )
        }
        .getOrNull()
    }

  suspend fun save(chat: SavedChat) =
    withContext(Dispatchers.IO) {
      val msgs = JSONArray()
      chat.messages.forEach { m ->
        val atts = JSONArray()
        m.attachments.forEach { a ->
          atts.put(JSONObject().put("kind", a.kind).put("label", a.label).apply { a.text?.let { put("text", it) }; a.index?.let { put("index", it) } })
        }
        msgs.put(JSONObject().put("role", m.role).put("text", m.text).put("attachments", atts))
      }
      val o = JSONObject().put("id", chat.id).put("title", chat.title).put("updated", chat.updated).put("messages", msgs)
      val tmp = File(dir, "${chat.id}.json.tmp")
      tmp.writeText(o.toString())
      tmp.renameTo(file(chat.id))
    }

  suspend fun delete(id: Long) = withContext(Dispatchers.IO) { file(id).delete() }

  private companion object {
    const val HEADER_CHARS = 8192 // a title is a line of text
  }
}
