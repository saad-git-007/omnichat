package com.saad.omnichat.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.saad.omnichat.AppLog
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** One web page the answer may cite. [n] is its 1-based citation number. */
data class Source(val n: Int, val title: String, val url: String, val published: String?, val content: String)

/**
 * Web search for the on-device model: Exa finds pages (with text and highlights), Firecrawl reads the top hit and
 * any link in the message as clean markdown. Works without keys on both services' free tiers. The model only reads what comes back; it never calls the network.
 */
object WebSearch {
  private const val EXA_URL = "https://api.exa.ai/search"
  private const val EXA_MCP_URL = "https://mcp.exa.ai/mcp"
  private const val FIRECRAWL_URL = "https://api.firecrawl.dev/v2/scrape"
  private const val FIRECRAWL_SEARCH_URL = "https://api.firecrawl.dev/v2/search"
  private val urlRe = Regex("""https?://[^\s)>\]"']+""")

  /** A validated internet connection (Wi-Fi or mobile data that actually reaches the internet). */
  fun isOnline(context: Context): Boolean =
    runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
      }
      .getOrDefault(false)

  /**
   * Keys are optional. Without an Exa key the search goes through Exa's free hosted MCP endpoint; without a Firecrawl
   * key Firecrawl's keyless tier is used. Both free tiers are rate-limited per network, so if Exa fails or finds
   * nothing, Firecrawl's own search is tried before giving up.
   */
  suspend fun search(query: String, exaKey: String, firecrawlKey: String?, results: Int = 5): List<Source> =
    withContext(Dispatchers.IO) {
      coroutineScope {
        val linked = urlRe.findAll(query).map { it.value.trimEnd('.', ',') }.distinct().take(2).toList()
        val question = urlRe.replace(query, "").trim()
        val pages = ArrayList<Triple<String, String, String?>>() // title, url, published
        val texts = LinkedHashMap<String, String>()
        val errors = ArrayList<String>()

        if (question.isNotEmpty()) {
          runCatching { if (exaKey.isNotBlank()) exaApi(question, exaKey, results) else exaFree(question, results) }
            .onFailure { errors += "Exa: ${it.message}"; AppLog.w("exa: ${it.message}") }
            .getOrNull()
            ?.forEach { (title, url, published, text) -> pages += Triple(title, url, published); texts[url] = text }
          if (pages.isEmpty()) {
            runCatching { firecrawlSearch(question, firecrawlKey, results) }
              .onFailure { errors += "Firecrawl: ${it.message}"; AppLog.w("firecrawl search: ${it.message}") }
              .getOrNull()
              ?.forEach { (title, url, _, text) -> pages += Triple(title, url, null); texts[url] = text }
          }
        }

        // Firecrawl: full main content for links in the message and for the top search hit
        val toScrape = (linked + pages.take(1).map { it.second }).distinct()
        toScrape.map { url ->
          async {
            runCatching {
              val body = JSONObject().put("url", url).put("formats", JSONArray().put("markdown")).put("onlyMainContent", true).put("timeout", 20000)
              val data = JSONObject(post(FIRECRAWL_URL, body, firecrawlAuth(firecrawlKey))).getJSONObject("data")
              val title = data.optJSONObject("metadata")?.opt("title")?.let { if (it is JSONArray) it.optString(0) else it.toString() }
              Triple(url, title, data.optString("markdown"))
            }.onFailure { AppLog.w("firecrawl $url: ${it.message}") }.getOrNull()
          }
        }.awaitAll().filterNotNull().forEach { (url, title, md) ->
          if (pages.none { it.second == url }) pages.add(0, Triple(title ?: url, url, null))
          if (md.isNotBlank()) texts[url] = md.take(if (url in linked) 6000 else 4000)
        }

        val found = pages.mapIndexedNotNull { i, (title, url, published) ->
          texts[url]?.takeIf { it.isNotBlank() }?.let { Source(i + 1, title, url, published, it) }
        }.mapIndexed { i, s -> s.copy(n = i + 1) }
        if (found.isEmpty() && errors.isNotEmpty()) throw IllegalStateException(errors.joinToString("; "))
        found
      }
    }

  private data class Hit(val title: String, val url: String, val published: String?, val text: String)

  private fun exaApi(question: String, key: String, results: Int): List<Hit> {
    val body = JSONObject()
      .put("query", question)
      .put("numResults", results)
      .put("contents", JSONObject().put("text", true).put("highlights", true))
    val res = JSONObject(post(EXA_URL, body, mapOf("x-api-key" to key))).optJSONArray("results") ?: JSONArray()
    return (0 until res.length()).mapNotNull { i ->
      val r = res.getJSONObject(i)
      val url = r.optString("url").ifEmpty { return@mapNotNull null }
      val highlights = r.optJSONArray("highlights")?.let { h -> (0 until h.length()).joinToString(" … ") { h.getString(it) } }.orEmpty()
      Hit(r.optString("title").ifEmpty { url }, url, r.optString("publishedDate").takeIf { it.isNotEmpty() && it != "null" }, (highlights + "\n" + r.optString("text").take(1500)).trim())
    }
  }

  /** Exa's hosted MCP server: a JSON-RPC tool call answered as server-sent events, results as plain text blocks. */
  private fun exaFree(question: String, results: Int): List<Hit> {
    val call = JSONObject()
      .put("jsonrpc", "2.0")
      .put("id", 1)
      .put("method", "tools/call")
      .put("params", JSONObject().put("name", "web_search_exa").put("arguments", JSONObject().put("query", question).put("numResults", results)))
    val raw = post(EXA_MCP_URL, call, mapOf("Accept" to "application/json, text/event-stream"))
    val json = raw.lineSequence().firstOrNull { it.startsWith("data:") }?.removePrefix("data:") ?: raw
    val msg = JSONObject(json)
    msg.optJSONObject("error")?.let { throw IllegalStateException(it.optString("message")) }
    val result = msg.getJSONObject("result")
    val text = result.getJSONArray("content").getJSONObject(0).optString("text")
    if (result.optBoolean("isError")) throw IllegalStateException(text.take(200))
    return text.split(Regex("""(?m)^Title: """)).drop(1).mapNotNull { block ->
      val title = block.lineSequence().first().trim()
      val url = Regex("""(?m)^URL: (\S+)""").find(block)?.groupValues?.get(1) ?: return@mapNotNull null
      val published = Regex("""(?m)^Published: (.+)""").find(block)?.groupValues?.get(1)?.trim()?.takeIf { it != "N/A" }
      val body = block.substringAfter("Highlights:", block.substringAfter("Text:", "")).removeSuffix("---").trim().take(2500)
      Hit(title.ifEmpty { url }, url, published, body)
    }
  }

  private fun firecrawlSearch(question: String, key: String?, results: Int): List<Hit> {
    val data = JSONObject(post(FIRECRAWL_SEARCH_URL, JSONObject().put("query", question).put("limit", results), firecrawlAuth(key))).getJSONObject("data")
    val web = data.optJSONArray("web") ?: JSONArray()
    return (0 until web.length()).mapNotNull { i ->
      val r = web.getJSONObject(i)
      val url = r.optString("url").ifEmpty { return@mapNotNull null }
      Hit(r.optString("title").ifEmpty { url }, url, null, r.optString("description"))
    }
  }

  private fun firecrawlAuth(key: String?) = if (key.isNullOrBlank()) emptyMap() else mapOf("Authorization" to "Bearer $key")

  /** The block the model reads before the question. */
  fun promptBlock(sources: List<Source>, today: String): String = buildString {
    append("Web search results (today is ").append(today).append("). Answer the question using them and cite sources as [1], [2]. ")
    append("If they don't contain the answer, say so.\n\n")
    sources.forEach { s ->
      append("[").append(s.n).append("] ").append(s.title).append(" — ").append(s.url)
      s.published?.let { append(" (").append(it.take(10)).append(")") }
      append("\n").append(s.content.trim()).append("\n\n")
    }
  }

  private fun post(url: String, body: JSONObject, headers: Map<String, String>): String {
    val conn = URL(url).openConnection() as HttpURLConnection
    try {
      conn.requestMethod = "POST"
      conn.connectTimeout = 15_000
      conn.readTimeout = 45_000
      conn.doOutput = true
      conn.setRequestProperty("Content-Type", "application/json")
      headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
      conn.outputStream.use { it.write(body.toString().toByteArray()) }
      val code = conn.responseCode
      val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
      if (code !in 200..299) throw IllegalStateException("${URL(url).host} returned $code: ${text.take(200)}")
      return text
    } finally {
      conn.disconnect()
    }
  }
}
