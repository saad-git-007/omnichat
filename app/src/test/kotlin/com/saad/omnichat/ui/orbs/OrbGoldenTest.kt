package com.saad.omnichat.ui.orbs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Checks the Kotlin engine against the library's golden vectors
 * (spec/orbs-golden.json, thinking-orbs 0.3.1): resolved preset opts, and
 * every dot/line of 72 frames within the spec's tolerance.
 *
 * Dots are compared positionally first. If that fails, the case still passes
 * when the dot MULTISET matches and the frame is z-sorted far→near: the
 * upstream iOS port documents that `breathing`@64 has a lane exactly on the
 * view plane (z ≈ ±1e-17 libm noise), so tie order there is platform-
 * dependent and cannot change a pixel. Such cases are reported.
 */
class OrbGoldenTest {
  @Suppress("UNCHECKED_CAST")
  private val golden: Map<String, Any?> by lazy {
    val text = javaClass.classLoader!!.getResourceAsStream("orbs-golden.json")!!.bufferedReader().readText()
    MiniJson(text).parse() as Map<String, Any?>
  }

  private fun stateOf(name: String) = OrbState.valueOf(name.uppercase())

  private fun sizeOf(units: Int) = OrbSize.entries.first { it.units == units }

  @Suppress("UNCHECKED_CAST")
  @Test
  fun resolvedPresetsMatch() {
    val resolved = golden["resolved"] as Map<String, Map<String, Any?>>
    assertEquals(18, resolved.size)
    for ((key, v) in resolved) {
      val (state, size) = key.split("-")
      val r = resolvePreset(stateOf(state), sizeOf(size.toInt()))
      assertEquals("$key mode", v["mode"], r.mode.name.lowercase())
      assertEquals("$key speed", v["speed"] as Double, r.speed, 0.0)
      val opts = v["opts"] as Map<String, Double>
      assertEquals("$key opt keys", opts.keys, r.opts.keys)
      for ((k, x) in opts) assertEquals("$key.$k", x, r.opts.getValue(k), 1e-12)
    }
  }

  @Suppress("UNCHECKED_CAST")
  @Test
  fun framesMatchGoldenVectors() {
    val tol = golden["tolerance"] as Double
    val cases = golden["cases"] as List<Map<String, Any?>>
    val engine = OrbEngine()
    val frame = OrbFrame()
    val failures = ArrayList<String>()
    val orderOnly = ArrayList<String>()
    var passed = 0
    var values = 0

    for (c in cases) {
      val key = c["key"] as String
      val preset = resolvePreset(stateOf(c["state"] as String), sizeOf((c["size"] as Double).toInt()))
      engine.frame(preset, c["t"] as Double, frame)
      val gd = (c["dots"] as List<Double>).toDoubleArray()
      val gl = (c["lines"] as List<Double>).toDoubleArray()
      val dotCount = (c["dotCount"] as Double).toInt()
      val lineCount = (c["lineCount"] as Double).toInt()
      if (frame.dotCount != dotCount || frame.lineCount != lineCount) {
        failures += "$key: ${frame.dotCount}/${frame.lineCount} dots/lines, expected $dotCount/$lineCount"
        continue
      }

      var caseOk = true
      for (i in 0 until lineCount) {
        val mine = doubleArrayOf(
          frame.lineX1[i], frame.lineY1[i], frame.lineX2[i], frame.lineY2[i],
          frame.lineWhite[i], frame.lineAlpha[i], frame.lineWidth[i],
        )
        for (f in 0 until 7) {
          values++
          if (kotlin.math.abs(mine[f] - gl[i * 7 + f]) > tol) {
            if (caseOk) failures += "$key line$i field$f: got ${mine[f]}, expected ${gl[i * 7 + f]}"
            caseOk = false
          }
        }
      }

      val mine = Array(dotCount) { i ->
        doubleArrayOf(
          frame.dotX[i], frame.dotY[i], frame.dotZ[i], frame.dotR[i], frame.dotWhite[i], frame.dotAlpha[i],
        )
      }
      fun near(i: Int, j: Int) = (0 until 6).all { f -> kotlin.math.abs(mine[i][f] - gd[j * 6 + f]) <= tol }
      values += dotCount * 6
      var firstBad = -1
      for (i in 0 until dotCount) if (!near(i, i)) { firstBad = i; break }
      if (firstBad >= 0) {
        // order-independent fallback: greedy multiset match + z-monotonicity
        val used = BooleanArray(dotCount)
        var multisetOk = true
        for (j in 0 until dotCount) {
          val i = (0 until dotCount).firstOrNull { !used[it] && near(it, j) }
          if (i == null) {
            multisetOk = false
            if (caseOk) {
              failures += "$key dot$j: no match (positional got ${mine[j].toList()}, " +
                "expected ${gd.copyOfRange(j * 6, j * 6 + 6).toList()})"
            }
            break
          }
          used[i] = true
        }
        val sorted = (1 until dotCount).all { frame.dotZ[it] >= frame.dotZ[it - 1] }
        if (multisetOk && sorted) orderOnly += key
        else {
          if (multisetOk && caseOk) failures += "$key: dots not z-sorted"
          caseOk = false
        }
      }
      if (caseOk) passed++
    }

    println("golden: $passed/${cases.size} cases passed, $values values checked, tolerance $tol")
    if (orderOnly.isNotEmpty()) println("golden: z-tie order differs (multiset identical) in: $orderOnly")
    if (failures.isNotEmpty()) fail("${failures.size} failing case(s):\n" + failures.joinToString("\n"))
    assertTrue(passed == cases.size)
  }
}

/** Minimal JSON reader: objects → LinkedHashMap, arrays → List, numbers → Double. */
private class MiniJson(private val s: String) {
  private var i = 0

  fun parse(): Any? = value().also { ws(); check(i == s.length) { "trailing data at $i" } }

  private fun ws() {
    while (i < s.length && s[i].isWhitespace()) i++
  }

  private fun value(): Any? {
    ws()
    return when (val c = s[i]) {
      '{' -> obj()
      '[' -> arr()
      '"' -> str()
      't' -> lit("true", true)
      'f' -> lit("false", false)
      'n' -> lit("null", null)
      else -> if (c == '-' || c.isDigit()) num() else error("unexpected '$c' at $i")
    }
  }

  private fun lit(word: String, v: Any?): Any? {
    check(s.startsWith(word, i)) { "bad literal at $i" }
    i += word.length
    return v
  }

  private fun obj(): Map<String, Any?> {
    val m = LinkedHashMap<String, Any?>()
    i++
    ws()
    if (s[i] == '}') return m.also { i++ }
    while (true) {
      ws()
      val k = str()
      ws()
      check(s[i++] == ':')
      m[k] = value()
      ws()
      when (s[i++]) {
        ',' -> continue
        '}' -> return m
        else -> error("bad object at ${i - 1}")
      }
    }
  }

  private fun arr(): List<Any?> {
    val l = ArrayList<Any?>()
    i++
    ws()
    if (s[i] == ']') return l.also { i++ }
    while (true) {
      l += value()
      ws()
      when (s[i++]) {
        ',' -> continue
        ']' -> return l
        else -> error("bad array at ${i - 1}")
      }
    }
  }

  private fun str(): String {
    check(s[i++] == '"')
    val sb = StringBuilder()
    while (true) {
      val c = s[i++]
      when (c) {
        '"' -> return sb.toString()
        '\\' -> {
          when (val e = s[i++]) {
            'n' -> sb.append('\n')
            't' -> sb.append('\t')
            'r' -> sb.append('\r')
            'b' -> sb.append('\b')
            'f' -> sb.append('\u000C')
            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
            else -> sb.append(e)
          }
        }
        else -> sb.append(c)
      }
    }
  }

  private fun num(): Double {
    val start = i
    while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
    return s.substring(start, i).toDouble()
  }
}
