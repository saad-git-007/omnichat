/*
 * Kotlin port of the "thinking-orbs" engine (packages/thinking-orbs/src/engine
 * and src/presets.ts in github.com/Jakubantalik/Libraries.dev).
 *
 * MIT License
 *
 * Copyright (c) 2026 Jakub Antalik
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.saad.omnichat.ui.orbs

// Pure geometry: no Android deps. Math is done in Double with StrictMath
// (fdlibm, the same lineage as V8's Math.*) so output tracks the JS engine
// to the last few ulps. Expression order is transcribed verbatim on purpose.

private fun sin(x: Double) = StrictMath.sin(x)
private fun cos(x: Double) = StrictMath.cos(x)
private fun atan2(y: Double, x: Double) = StrictMath.atan2(y, x)
private fun acos(x: Double) = StrictMath.acos(x)
private fun exp(x: Double) = StrictMath.exp(x)
private fun pow(a: Double, b: Double) = StrictMath.pow(a, b)
private fun hypot(a: Double, b: Double) = StrictMath.hypot(a, b)
private fun sqrt(x: Double) = StrictMath.sqrt(x)
private fun floor(x: Double) = StrictMath.floor(x)
private fun abs(x: Double) = kotlin.math.abs(x)
private fun max(a: Double, b: Double) = kotlin.math.max(a, b)
private fun min(a: Double, b: Double) = kotlin.math.min(a, b)

/** JS `Math.round`: half rounds toward +∞. */
private fun jsRound(x: Double): Double = Math.round(x).toDouble()

private const val PI = Math.PI

enum class OrbState(val label: String) {
  WORKING("Working…"),
  SEARCHING("Searching…"),
  SOLVING("Solving…"),
  LISTENING("Listening…"),
  CONNECTING("Connecting…"),
  WEAVING("Weaving…"),
  COMPOSING("Composing…"),
  BREATHING("Thinking…"),
  SHAPING("Shaping…"),
}

/** The two hand-tuned designs; geometry is laid out in a [units]×[units] box. */
enum class OrbSize(val units: Int) { Large(64), Small(20) }

enum class OrbMode { ORBITS, GLOBE, RUBIK, WAVE, WEB, BRAID, RIBBON, RING, MORPH }

typealias ModeOpts = Map<String, Double>

/** The static frame shown for reduced motion / paused, in raw engine seconds. */
const val REDUCED_MOTION_T = 0.6

fun OrbState.mode(): OrbMode =
  when (this) {
    OrbState.WORKING -> OrbMode.ORBITS
    OrbState.SEARCHING -> OrbMode.GLOBE
    OrbState.SOLVING -> OrbMode.RUBIK
    OrbState.LISTENING -> OrbMode.WAVE
    OrbState.CONNECTING -> OrbMode.WEB
    OrbState.WEAVING -> OrbMode.BRAID
    OrbState.COMPOSING -> OrbMode.RIBBON
    OrbState.BREATHING -> OrbMode.RING
    OrbState.SHAPING -> OrbMode.MORPH
  }

// ---------------------------------------------------------------- profiles

private val COUNT_PAIRS = listOf("latRings" to "lonDensity", "rings" to "lonDensity", "lanes" to "segs")
private val COUNT_KEYS = listOf("orbitN", "ghostN", "nodeN", "strandN", "signals")
private val ICON_DENSITY_KEYS = listOf("iconD")
private val RADIUS_KEYS =
  listOf("rBase", "rDepth", "rActive", "rDot", "ghostR", "partR", "partRDepth", "nodeR", "nodeRDepth")

fun scaleCounts(opts: ModeOpts, scale: Double): ModeOpts {
  val out = LinkedHashMap(opts)
  val done = HashSet<String>()
  val rt = sqrt(scale)
  for ((a, b) in COUNT_PAIRS) {
    val va = out[a]
    val vb = out[b]
    if (va != null && vb != null && a !in done && b !in done) {
      out[a] = max(2.0, jsRound(va * rt))
      out[b] = max(2.0, jsRound(vb * rt))
      done += a
      done += b
    }
  }
  for (k in COUNT_KEYS) {
    val v = out[k]
    // 0 = the mode opted out of that layer; scaling must not resurrect it
    if (v != null && v != 0.0 && k !in done) out[k] = max(1.0, jsRound(v * scale))
  }
  for (k in ICON_DENSITY_KEYS) {
    val v = out[k]
    if (v != null) out[k] = max(0.02, v * scale)
  }
  return out
}

fun scaleRadii(opts: ModeOpts, scale: Double): ModeOpts {
  val out = LinkedHashMap(opts)
  for (k in RADIUS_KEYS) {
    val v = out[k]
    if (v != null) out[k] = v * scale
  }
  out["rSizeMul"] = (out["rSizeMul"] ?: 1.0) * scale
  return out
}

private val BASE_PROFILES: Map<OrbMode, ModeOpts> =
  mapOf(
    OrbMode.GLOBE to linkedMapOf(
      "latRings" to 17.0, "lonDensity" to 44.0, "rBase" to 0.6, "rDepth" to 1.7, "rBoost" to 1.0,
      "inkFar" to 0.62, "inkSpan" to 0.54, "rsPow" to 0.6, "rMin" to 0.3,
    ),
    OrbMode.ORBITS to linkedMapOf(
      "orbitN" to 12.0, "ghostN" to 40.0, "ghostR" to 0.9, "ghostA" to 0.5, "particles" to 3.0,
      "partR" to 1.2, "partRDepth" to 1.6, "rsPow" to 0.6, "rMin" to 0.3,
    ),
    OrbMode.RUBIK to linkedMapOf(
      "latRings" to 15.0, "lonDensity" to 40.0, "moveCount" to 14.0, "rBase" to 0.6, "rDepth" to 1.7,
      "rActive" to 0.3, "inkFar" to 0.62, "inkSpan" to 0.54, "rsPow" to 0.6, "rMin" to 0.3,
    ),
    OrbMode.WAVE to linkedMapOf(
      "rings" to 15.0, "lonDensity" to 40.0, "rBase" to 0.6, "rDepth" to 1.7, "rsPow" to 0.6, "rMin" to 0.3,
    ),
    OrbMode.WEB to linkedMapOf(
      "nodeN" to 30.0, "thr" to 0.72, "signals" to 5.0, "nodeR" to 1.4, "nodeRDepth" to 1.8, "lineW" to 0.8,
      "rsPow" to 0.6, "rMin" to 0.3,
    ),
    OrbMode.BRAID to linkedMapOf(
      "strandN" to 52.0, "turns" to 3.0, "ghostN" to 150.0, "rBase" to 1.2, "rDepth" to 1.8, "rsPow" to 0.6,
      "rMin" to 0.3,
    ),
    OrbMode.RIBBON to linkedMapOf(
      "lanes" to 5.0, "segs" to 88.0, "ghostN" to 150.0, "rBase" to 1.1, "rDepth" to 1.7, "rsPow" to 0.6,
      "rMin" to 0.3,
    ),
    // ring shares ribbon's painter; faceOn makes it a face-on morphing ring with no ghost sphere
    OrbMode.RING to linkedMapOf(
      "lanes" to 5.0, "segs" to 88.0, "ghostN" to 0.0, "faceOn" to 1.0, "rBase" to 1.1, "rDepth" to 1.7,
      "rsPow" to 0.6, "rMin" to 0.3,
    ),
    OrbMode.MORPH to linkedMapOf("rDot" to 0.021, "iconD" to 1.0, "rMin" to 0.25),
  )

// ----------------------------------------------------------------- presets

private class Preset(val speed: Double, val count: Double, val size: Double, val extra: ModeOpts? = null)

// (mode → 64-unit preset, 20-unit preset), baked from the library's tuning session
private val PRESETS: Map<OrbMode, Pair<Preset, Preset>> =
  mapOf(
    OrbMode.ORBITS to (Preset(1.885, 1.0, 1.0) to Preset(3.9, 0.238, 2.4)),
    OrbMode.GLOBE to (
      Preset(2.015, 0.42, 1.15, mapOf("scanMul" to 4.08, "dimBase" to 0.45)) to
        Preset(2.665, 0.105, 1.75, mapOf("scanMul" to 4.335, "dimBase" to 0.45))
      ),
    OrbMode.RUBIK to (Preset(1.82, 0.35, 1.05) to Preset(1.95, 0.088, 1.9)),
    OrbMode.WAVE to (Preset(4.388, 0.341, 1.0) to Preset(3.998, 0.105, 1.6)),
    OrbMode.WEB to (Preset(3.315, 1.35, 0.95) to Preset(6.63, 0.25, 1.52)),
    OrbMode.BRAID to (Preset(1.625, 0.5, 1.0) to Preset(2.75, 0.1125, 1.36)),
    OrbMode.RIBBON to (
      Preset(2.34, 0.25, 0.85, mapOf("spin" to 0.0, "bandMul" to 3.9, "wobMul" to 1.0)) to
        Preset(3.12, 0.051, 1.073, mapOf("spin" to 0.0, "bandMul" to 4.94, "wobMul" to 1.0))
      ),
    OrbMode.RING to (
      Preset(3.24, 0.25, 0.956, mapOf("spin" to 0.0, "bandMul" to 3.627, "wobMul" to 0.368)) to
        Preset(3.78, 0.028, 1.622, mapOf("spin" to 0.0, "bandMul" to 3.968, "wobMul" to 0.565))
      ),
    OrbMode.MORPH to (
      Preset(2.405, 0.702, 0.395, mapOf("spread" to 1.45)) to
        Preset(2.08, 0.53, 1.011, mapOf("spread" to 1.45))
      ),
  )

class ResolvedPreset(val mode: OrbMode, val size: OrbSize, val speed: Double, val opts: ModeOpts)

private val resolvedCache = java.util.concurrent.ConcurrentHashMap<Pair<OrbState, OrbSize>, ResolvedPreset>()

/** Resolve a (state, size) pair to its mode + fully-scaled draw options (cached). */
fun resolvePreset(state: OrbState, size: OrbSize): ResolvedPreset =
  resolvedCache.getOrPut(state to size) {
    val mode = state.mode()
    val pair = PRESETS.getValue(mode)
    val preset = if (size == OrbSize.Large) pair.first else pair.second
    var opts: ModeOpts = LinkedHashMap(BASE_PROFILES.getValue(mode))
    if (preset.count != 1.0) opts = scaleCounts(opts, preset.count)
    if (preset.size != 1.0) opts = scaleRadii(opts, preset.size)
    if (preset.extra != null) opts = LinkedHashMap(opts).apply { putAll(preset.extra) }
    ResolvedPreset(mode, size, preset.speed, opts)
  }

// ------------------------------------------------------------------- frame

/**
 * A finished draw list: dots already culled, radius-clamped and z-sorted
 * far→near (draw in index order); lines are drawn before dots. `white` is
 * the paper-theme ink value; a dark renderer mirrors it (1 - white).
 * Buffers are reused across frames — read only `[0, dotCount)` / `[0, lineCount)`.
 */
class OrbFrame {
  var dotCount = 0
    internal set
  var lineCount = 0
    internal set
  var dotX = DoubleArray(0); internal set
  var dotY = DoubleArray(0); internal set
  var dotZ = DoubleArray(0); internal set
  var dotR = DoubleArray(0); internal set
  var dotWhite = DoubleArray(0); internal set
  var dotAlpha = DoubleArray(0); internal set
  var lineX1 = DoubleArray(0); internal set
  var lineY1 = DoubleArray(0); internal set
  var lineX2 = DoubleArray(0); internal set
  var lineY2 = DoubleArray(0); internal set
  var lineWhite = DoubleArray(0); internal set
  var lineAlpha = DoubleArray(0); internal set
  var lineWidth = DoubleArray(0); internal set

  internal fun ensureDots(n: Int) {
    if (dotX.size >= n) return
    val c = kotlin.math.max(n, dotX.size * 2)
    dotX = DoubleArray(c); dotY = DoubleArray(c); dotZ = DoubleArray(c)
    dotR = DoubleArray(c); dotWhite = DoubleArray(c); dotAlpha = DoubleArray(c)
  }

  internal fun ensureLines(n: Int) {
    if (lineX1.size >= n) return
    val c = kotlin.math.max(n, lineX1.size * 2)
    lineX1 = DoubleArray(c); lineY1 = DoubleArray(c); lineX2 = DoubleArray(c); lineY2 = DoubleArray(c)
    lineWhite = DoubleArray(c); lineAlpha = DoubleArray(c); lineWidth = DoubleArray(c)
  }
}

/** Spin + tilt + orthographic projection (`makeProj`); results land in px/py/pz. */
private class Projector {
  private var st = 0.0
  private var ct = 1.0
  private var sy = 0.0
  private var cyw = 1.0
  private var cx = 0.0
  private var cy = 0.0
  private var scale = 1.0
  var px = 0.0
  var py = 0.0
  var pz = 0.0

  fun set(yaw: Double, tilt: Double, cx: Double, cy: Double, scale: Double) {
    st = sin(tilt)
    ct = cos(tilt)
    sy = sin(yaw)
    cyw = cos(yaw)
    this.cx = cx
    this.cy = cy
    this.scale = scale
  }

  fun project(x: Double, y: Double, z: Double) {
    val x1 = x * cyw + z * sy
    val z1 = -x * sy + z * cyw
    val y1 = y * ct - z1 * st
    val z2 = y * st + z1 * ct
    px = cx + x1 * scale
    py = cy - y1 * scale
    pz = z2
  }
}

private fun frac(x: Double): Double = x - floor(x)

private fun lerp(a: Double, b: Double, f: Double): Double = a + (b - a) * f

/** Deterministic hash in [0, 1). */
internal fun hashD(a: Double, b: Double): Double {
  val h = sin(a * 12.9898 + b * 78.233) * 43758.5453
  return h - floor(h)
}

/** Value noise on a 2D lattice. */
private fun vnoise(x: Double, y: Double): Double {
  val xi = floor(x)
  val yi = floor(y)
  var fx = x - xi
  var fy = y - yi
  fx = fx * fx * (3 - 2 * fx)
  fy = fy * fy * (3 - 2 * fy)
  val a = hashD(xi, yi)
  val b = hashD(xi + 1, yi)
  val c = hashD(xi, yi + 1)
  val d = hashD(xi + 1, yi + 1)
  return a + (b - a) * fx + (c - a) * fy + (a - b - c + d) * fx * fy
}

private fun angleDelta(a: Double, b: Double): Double = atan2(sin(a - b), cos(a - b))

private fun radiusScale(size: Double, p: Double): Double = pow(size / 300, p)

private val GOLDEN_ANGLE = PI * (3 - sqrt(5.0))

private fun smoothE(x: Double): Double = x * x * (3 - 2 * x)

/** Closed outline parameterised by arc length; [eval] writes into ox/oy. */
private abstract class Shape {
  var ox = 0.0
  var oy = 0.0
  abstract fun eval(f: Double)
}

private class CircleShape : Shape() {
  override fun eval(f: Double) {
    val a = -PI / 2 + f * 2 * PI
    ox = cos(a) * 0.24
    oy = sin(a) * 0.24
  }
}

private class PolyShape(private val vx: DoubleArray, private val vy: DoubleArray) : Shape() {
  private val v = vx.size
  private val len = DoubleArray(v)
  private var total = 0.0

  init {
    for (i in 0 until v) {
      val j = (i + 1) % v
      val l = hypot(vx[j] - vx[i], vy[j] - vy[i])
      len[i] = l
      total += l
    }
  }

  override fun eval(f: Double) {
    var target = f * total
    var i = 0
    while (target > len[i] && i < v - 1) {
      target -= len[i]
      i++
    }
    val j = (i + 1) % v
    val ff = if (len[i] != 0.0) min(1.0, target / len[i]) else 0.0
    ox = vx[i] + (vx[j] - vx[i]) * ff
    oy = vy[i] + (vy[j] - vy[i]) * ff
  }
}

private const val HOLD = 1.4
private const val MORPH = 0.9
private const val SEG = HOLD + MORPH
private const val MORPH_SAMPLES = 160

/**
 * Stateful frame builder. Owns every scratch buffer so steady-state frames
 * allocate nothing; not thread-safe — use one per renderer.
 */
class OrbEngine {
  private val proj = Projector()

  // raw (pre-finalize) dots
  private var n = 0
  private var rx = DoubleArray(256)
  private var ry = DoubleArray(256)
  private var rz = DoubleArray(256)
  private var rr = DoubleArray(256)
  private var rw = DoubleArray(256)
  private var ra = DoubleArray(256)
  private var order = IntArray(256)
  private var sortTmp = IntArray(256)

  // raw lines
  private var ln = 0
  private var lx1 = DoubleArray(128)
  private var ly1 = DoubleArray(128)
  private var lx2 = DoubleArray(128)
  private var ly2 = DoubleArray(128)
  private var lw = DoubleArray(128)
  private var la = DoubleArray(128)
  private var lwid = DoubleArray(128)

  // rubik
  private var moveCountCached = -1
  private var mvAxis = IntArray(0)
  private var mvLo = DoubleArray(0)
  private var mvHi = DoubleArray(0)
  private var mvAng = DoubleArray(0)
  private var amount = DoubleArray(0)
  private var active = -1
  private var ax = 0.0
  private var ay = 0.0
  private var az = 0.0

  // web
  private var nodeX = DoubleArray(0)
  private var nodeY = DoubleArray(0)
  private var nodeZ = DoubleArray(0)

  // morph
  private val cycle: Array<Shape> =
    arrayOf(
      CircleShape(),
      PolyShape(doubleArrayOf(0.0, 0.24, -0.24), doubleArrayOf(-0.26, 0.16, 0.16)),
      // 5-vertex walk so the square starts at top-centre like the others
      PolyShape(doubleArrayOf(0.0, 0.2, 0.2, -0.2, -0.2), doubleArrayOf(-0.2, -0.2, 0.2, 0.2, -0.2)),
    )
  private val ptsX = DoubleArray(MORPH_SAMPLES)
  private val ptsY = DoubleArray(MORPH_SAMPLES)
  private val segLen = DoubleArray(MORPH_SAMPLES)

  /** Frame for a resolved preset at raw engine time [t] (seconds × speed). */
  fun frame(preset: ResolvedPreset, t: Double, out: OrbFrame = OrbFrame()): OrbFrame =
    frame(preset.mode, preset.size.units.toDouble(), t, preset.opts, out)

  fun frame(mode: OrbMode, size: Double, t: Double, o: ModeOpts, out: OrbFrame = OrbFrame()): OrbFrame {
    n = 0
    ln = 0
    when (mode) {
      OrbMode.ORBITS -> orbits(size, t, o)
      OrbMode.GLOBE -> globe(size, t, o)
      OrbMode.RUBIK -> rubik(size, t, o)
      OrbMode.WAVE -> wave(size, t, o)
      OrbMode.WEB -> web(size, t, o)
      OrbMode.BRAID -> braid(size, t, o)
      OrbMode.RIBBON, OrbMode.RING -> ribbon(size, t, o)
      OrbMode.MORPH -> morph(size, t, o)
    }
    finalize(o["rMin"] ?: 0.3, out)
    return out
  }

  private fun dot(x: Double, y: Double, z: Double, r: Double, white: Double, a: Double = 1.0) {
    if (n == rx.size) {
      val c = n * 2
      rx = rx.copyOf(c); ry = ry.copyOf(c); rz = rz.copyOf(c)
      rr = rr.copyOf(c); rw = rw.copyOf(c); ra = ra.copyOf(c)
      order = IntArray(c); sortTmp = IntArray(c)
    }
    rx[n] = x; ry[n] = y; rz[n] = z; rr[n] = r; rw[n] = white; ra[n] = a
    n++
  }

  private fun line(x1: Double, y1: Double, x2: Double, y2: Double, white: Double, a: Double, w: Double) {
    if (ln == lx1.size) {
      val c = ln * 2
      lx1 = lx1.copyOf(c); ly1 = ly1.copyOf(c); lx2 = lx2.copyOf(c); ly2 = ly2.copyOf(c)
      lw = lw.copyOf(c); la = la.copyOf(c); lwid = lwid.copyOf(c)
    }
    lx1[ln] = x1; ly1[ln] = y1; lx2[ln] = x2; ly2[ln] = y2; lw[ln] = white; la[ln] = a; lwid[ln] = w
    ln++
  }

  /** `finalizeFrame`: cull a < 0.02, clamp r to rMin, stable z-sort far→near. */
  private fun finalize(rMin: Double, out: OrbFrame) {
    var m = 0
    for (i in 0 until n) {
      if (ra[i] < 0.02) continue
      rr[i] = max(rMin, rr[i])
      order[m++] = i
    }
    mergeSort(m)
    out.ensureDots(m)
    for (k in 0 until m) {
      val i = order[k]
      out.dotX[k] = rx[i]; out.dotY[k] = ry[i]; out.dotZ[k] = rz[i]
      out.dotR[k] = rr[i]; out.dotWhite[k] = rw[i]; out.dotAlpha[k] = ra[i]
    }
    out.dotCount = m
    out.ensureLines(ln)
    var q = 0
    for (i in 0 until ln) {
      if (la[i] < 0.02) continue
      out.lineX1[q] = lx1[i]; out.lineY1[q] = ly1[i]; out.lineX2[q] = lx2[i]; out.lineY2[q] = ly2[i]
      out.lineWhite[q] = lw[i]; out.lineAlpha[q] = la[i]; out.lineWidth[q] = lwid[i]
      q++
    }
    out.lineCount = q
  }

  // Stable bottom-up merge sort of order[0, m) by rz, matching JS's stable Array.sort.
  private fun mergeSort(m: Int) {
    var src = order
    var dst = sortTmp
    var width = 1
    while (width < m) {
      var lo = 0
      while (lo < m) {
        val mid = kotlin.math.min(lo + width, m)
        val hi = kotlin.math.min(lo + 2 * width, m)
        var i = lo
        var j = mid
        var k = lo
        while (i < mid && j < hi) dst[k++] = if (rz[src[j]] < rz[src[i]]) src[j++] else src[i++]
        while (i < mid) dst[k++] = src[i++]
        while (j < hi) dst[k++] = src[j++]
        lo += 2 * width
      }
      val s = src
      src = dst
      dst = s
      width *= 2
    }
    if (src !== order) System.arraycopy(src, 0, order, 0, m)
  }

  // --- orbits: particles on tilted orbits — working ---------------------

  private fun orbits(size: Double, t: Double, o: ModeOpts) {
    val cx = size / 2
    val cy = size / 2
    val radius = (size / 2) * 0.82
    proj.set(t * 0.12, 0.3, cx, cy, 1.0)
    val rs = radiusScale(size, o["rsPow"] ?: 0.6)
    val orbitN = o["orbitN"] ?: 12.0
    val ghostN = o["ghostN"] ?: 40.0
    val particles = o["particles"] ?: 3.0
    val ghostR = o["ghostR"] ?: 0.9
    val ghostA = o["ghostA"] ?: 0.5
    val partR = o["partR"] ?: 1.2
    val partRDepth = o["partRDepth"] ?: 1.6

    var orb = 0.0
    while (orb < orbitN) {
      val h1 = hashD(orb, 1.7)
      val h2 = hashD(orb, 5.2)
      val h3 = hashD(orb, 8.9)
      val ro = radius * (0.45 + 0.52 * h1)
      val th = h1 * 2 * PI
      val phi = acos(2 * h2 - 1)
      val nx = sin(phi) * cos(th)
      val ny = cos(phi)
      val nz = sin(phi) * sin(th)
      var ux = -ny
      var uy = nx
      val uz = 0.0
      val ul = max(1e-6, sqrt(ux * ux + uy * uy))
      ux /= ul
      uy /= ul
      val vx = ny * uz - nz * uy
      val vy = nz * ux - nx * uz
      val vz = nx * uy - ny * ux
      val speed = (0.25 + 0.55 * h3) * (if (h3 > 0.5) 1 else -1)

      var k = 0.0
      while (k < ghostN) {
        val a = (k / ghostN) * 2 * PI
        proj.project(
          (ux * cos(a) + vx * sin(a)) * ro,
          (uy * cos(a) + vy * sin(a)) * ro,
          (uz * cos(a) + vz * sin(a)) * ro,
        )
        val z = proj.pz
        val depth = (z / ro + 1) / 2
        dot(proj.px, proj.py, z, ghostR * rs, 0.72, ghostA * (0.4 + 0.6 * depth))
        k++
      }
      var m = 0.0
      while (m < particles) {
        val a = t * speed + (m / particles) * 2 * PI + h2 * 6
        proj.project(
          (ux * cos(a) + vx * sin(a)) * ro,
          (uy * cos(a) + vy * sin(a)) * ro,
          (uz * cos(a) + vz * sin(a)) * ro,
        )
        val z = proj.pz
        val depth = (z / ro + 1) / 2
        dot(proj.px, proj.py, z, (partR + partRDepth * depth) * rs, 0.3 - 0.22 * depth)
        m++
      }
      orb++
    }
  }

  // --- globe: a scan meridian sweeps a lat/long field — searching -------

  private fun globe(size: Double, t: Double, o: ModeOpts) {
    val spin = 0.5
    val cx = size / 2
    val cy = size / 2
    val radius = (size / 2) * 0.82
    val tilt = 0.4 + 0.06 * sin(t * 0.35)
    proj.set(t * spin, tilt, cx, cy, radius)
    val scan = t * (spin + (1.7 - spin) * (o["scanMul"] ?: 1.0))
    val rs = radiusScale(size, o["rsPow"] ?: 0.6)
    val dimBase = o["dimBase"] ?: 1.0
    val rBase = o["rBase"] ?: 0.6
    val rDepth = o["rDepth"] ?: 1.7
    val rBoost = o["rBoost"] ?: 1.0
    val inkFar = o["inkFar"] ?: 0.62
    val inkSpan = o["inkSpan"] ?: 0.54

    val latRings = o["latRings"] ?: 17.0
    val lonDensity = o["lonDensity"] ?: 44.0
    var li = 0.0
    while (li <= latRings) {
      val lat = -PI / 2 + (li / latRings) * PI
      val cosLat = cos(lat)
      val sinLat = sin(lat)
      val lonCount = max(1.0, jsRound(abs(cosLat) * lonDensity))
      var lj = 0.0
      while (lj < lonCount) {
        val lon = (lj / lonCount) * 2 * PI
        proj.project(cosLat * cos(lon), sinLat, cosLat * sin(lon))
        val z = proj.pz
        val depth = (z + 1) / 2
        val d = angleDelta(lon + t * spin, scan)
        val boost = exp(-(d * d) / 0.18) * max(0.0, z)
        dot(
          proj.px,
          proj.py,
          z,
          (rBase + rDepth * depth + rBoost * boost) * rs,
          inkFar - inkSpan * depth,
          dimBase + (1 - dimBase) * min(1.0, boost),
        )
        lj++
      }
      li++
    }
  }

  // --- rubik: bands twist in quarter turns, scramble → solve — solving --

  private fun ensureMoves(count: Int) {
    if (count == moveCountCached) return
    moveCountCached = count
    mvAxis = IntArray(count)
    mvLo = DoubleArray(count)
    mvHi = DoubleArray(count)
    mvAng = DoubleArray(count)
    amount = DoubleArray(count)
    for (i in 0 until count) {
      val di = i.toDouble()
      mvAxis[i] = kotlin.math.min(2.0, floor(hashD(di, 2.3) * 3)).toInt()
      val lo = -1.0 + 0.5 * min(3.0, floor(hashD(di, 5.9) * 4))
      val dir = if (hashD(di, 7.7) < 0.5) 1 else -1
      mvLo[i] = lo
      mvHi[i] = lo + 0.5
      mvAng[i] = (dir * PI) / 2
    }
  }

  private fun solveCycle(time: Double, count: Int, slotDur: Double, rest: Double) {
    val cyc = 2 * count * slotDur + rest
    val tc = time % cyc
    java.util.Arrays.fill(amount, 0.0)
    active = -1
    if (tc < 2 * count * slotDur) {
      val slot = floor(tc / slotDur).toInt()
      val p = (tc - slot * slotDur) / slotDur
      val cl = min(1.0, p / 0.7)
      val ep = 1 - pow(1 - cl, 3.0)
      if (slot < count) {
        for (i in 0 until slot) amount[i] = 1.0
        amount[slot] = ep
        active = slot
      } else {
        val u = 2 * count - 1 - slot
        for (i in 0 until u) amount[i] = 1.0
        amount[u] = 1 - ep
        active = u
      }
    }
  }

  /** Applies the active moves to (x, y, z) → ax/ay/az; returns whether the point is in the active band. */
  private fun applyMoves(x0: Double, y0: Double, z0: Double): Boolean {
    var x = x0
    var y = y0
    var z = z0
    var inActive = false
    for (i in mvAxis.indices) {
      if (amount[i] <= 0) continue
      val axis = mvAxis[i]
      val coord = if (axis == 0) x else if (axis == 1) y else z
      if (coord < mvLo[i] || coord >= mvHi[i]) continue
      if (i == active) inActive = true
      val a = mvAng[i] * amount[i]
      val ca = cos(a)
      val sa = sin(a)
      if (axis == 0) {
        val y2 = y * ca - z * sa
        z = y * sa + z * ca
        y = y2
      } else if (axis == 1) {
        val x2 = x * ca + z * sa
        z = -x * sa + z * ca
        x = x2
      } else {
        val x2 = x * ca - y * sa
        y = x * sa + y * ca
        x = x2
      }
    }
    ax = x
    ay = y
    az = z
    return inActive
  }

  private fun rubik(size: Double, t: Double, o: ModeOpts) {
    val cx = size / 2
    val cy = size / 2
    val radius = (size / 2) * 0.82
    proj.set(t * 0.55, 0.35 + 0.1 * sin(t * 0.9), cx, cy, radius)
    val rs = radiusScale(size, o["rsPow"] ?: 0.6)
    val moveCount = (o["moveCount"] ?: 14.0).toInt()
    ensureMoves(moveCount)
    solveCycle(t, moveCount, 0.42, 1.2)
    val rBase = o["rBase"] ?: 0.6
    val rDepth = o["rDepth"] ?: 1.7
    val rActive = o["rActive"] ?: 0.3
    val inkFar = o["inkFar"] ?: 0.62
    val inkSpan = o["inkSpan"] ?: 0.54

    val latRings = o["latRings"] ?: 15.0
    val lonDensity = o["lonDensity"] ?: 40.0
    var li = 0.0
    while (li <= latRings) {
      val lat = -PI / 2 + (li / latRings) * PI
      val cosLat = cos(lat)
      val sinLat = sin(lat)
      val lonCount = max(1.0, jsRound(abs(cosLat) * lonDensity))
      var lj = 0.0
      while (lj < lonCount) {
        val lon = (lj / lonCount) * 2 * PI
        val inActive = applyMoves(cosLat * cos(lon), sinLat, cosLat * sin(lon))
        proj.project(ax, ay, az)
        val zr = proj.pz
        val depth = (zr + 1) / 2
        dot(
          proj.px,
          proj.py,
          zr,
          (rBase + rDepth * depth + (if (inActive) rActive else 0.0)) * rs,
          inkFar - inkSpan * depth - (if (inActive) 0.14 else 0.0),
        )
        lj++
      }
      li++
    }
  }

  // --- wave: a waveform rolls through the rings — listening -------------

  private fun wave(size: Double, t: Double, o: ModeOpts) {
    val cx = size / 2
    val cy = size / 2
    val radius = (size / 2) * 0.874
    proj.set(t * 0.18, 0.38, cx, cy, 1.0)
    val rs = radiusScale(size, o["rsPow"] ?: 0.6)
    val rBase = o["rBase"] ?: 0.6
    val rDepth = o["rDepth"] ?: 1.7

    val rings = o["rings"] ?: 15.0
    val lonDensity = o["lonDensity"] ?: 40.0
    var ri = 0.0
    while (ri <= rings) {
      val lat = -PI / 2 + (ri / rings) * PI
      val cosLat = cos(lat)
      val sinLat = sin(lat)
      val w = 0.62 * sin(t * 2.1 - ri * 0.52) + 0.38 * sin(t * 1.27 + ri * 0.83)
      val rr = radius * (0.88 + 0.105 * w)
      val lonCount = max(1.0, jsRound(abs(cosLat) * lonDensity))
      var lj = 0.0
      while (lj < lonCount) {
        val lon = (lj / lonCount) * 2 * PI
        proj.project(cosLat * cos(lon) * rr, sinLat * rr, cosLat * sin(lon) * rr)
        val z = proj.pz
        val depth = (z / radius + 1) / 2
        val crest = max(0.0, w)
        dot(
          proj.px,
          proj.py,
          z,
          (rBase + rDepth * depth) * (1 + 0.4 * crest) * rs,
          0.66 - 0.56 * depth - 0.1 * crest,
        )
        lj++
      }
      ri++
    }
  }

  // --- web: a constellation wires itself — connecting -------------------

  private fun web(size: Double, t: Double, o: ModeOpts) {
    val cx = size / 2
    val cy = size / 2
    val radius = (size / 2) * 0.8 * (o["spread"] ?: 1.0)
    proj.set(t * 0.12, 0.32, cx, cy, radius)
    val rs = radiusScale(size, o["rsPow"] ?: 0.6)
    val nodeNd = o["nodeN"] ?: 30.0
    val nodeN = nodeNd.toInt()
    val thr = o["thr"] ?: 0.72
    val nodeR = o["nodeR"] ?: 1.4
    val nodeRDepth = o["nodeRDepth"] ?: 1.8
    val lineW = o["lineW"] ?: 0.8

    if (nodeX.size < nodeN) {
      nodeX = DoubleArray(nodeN)
      nodeY = DoubleArray(nodeN)
      nodeZ = DoubleArray(nodeN)
    }
    for (i in 0 until nodeN) {
      val di = i.toDouble()
      // fibDir(i, nodeN)
      val fy = 1 - (2 * (di + 0.5)) / nodeNd
      val rad = sqrt(1 - fy * fy)
      val fa = di * GOLDEN_ANGLE
      val x = rad * cos(fa) + 0.3 * (vnoise(di * 0.31 + 9, t * 0.24) - 0.5) * 2
      val y = fy + 0.3 * (vnoise(di * 0.53 + 27, t * 0.21) - 0.5) * 2
      val z = rad * sin(fa) + 0.3 * (vnoise(di * 0.77 + 55, t * 0.27) - 0.5) * 2
      val l = sqrt(x * x + y * y + z * z)
      nodeX[i] = x / l
      nodeY[i] = y / l
      nodeZ[i] = z / l
    }

    for (i in 0 until nodeN) {
      for (j in i + 1 until nodeN) {
        val dx = nodeX[i] - nodeX[j]
        val dy = nodeY[i] - nodeY[j]
        val dz = nodeZ[i] - nodeZ[j]
        val dist = sqrt(dx * dx + dy * dy + dz * dz)
        if (dist >= thr) continue
        proj.project(nodeX[i], nodeY[i], nodeZ[i])
        val x1 = proj.px
        val y1 = proj.py
        val z1 = proj.pz
        proj.project(nodeX[j], nodeY[j], nodeZ[j])
        val depth = ((z1 + proj.pz) / 2 + 1) / 2
        line(x1, y1, proj.px, proj.py, 0.42, (1 - dist / thr) * (0.3 + 0.55 * depth), max(0.6, lineW * rs))
      }
    }

    for (i in 0 until nodeN) {
      proj.project(nodeX[i], nodeY[i], nodeZ[i])
      val z = proj.pz
      val depth = (z + 1) / 2
      val pulse = 1 + 0.25 * sin(t * 1.4 + i * 2.7)
      dot(proj.px, proj.py, z, (nodeR + nodeRDepth * depth) * pulse * rs, 0.55 - 0.45 * depth)
    }

    val signals = o["signals"] ?: 5.0
    var s = 0.0
    while (s < signals) {
      val seg = floor(t * 0.55 + s * 7.31)
      val a = floor(hashD(seg, s * 3.1 + 1.7) * nodeNd).toInt()
      val b = floor(hashD(seg, s * 5.7 + 4.2) * nodeNd).toInt()
      if (a == b) {
        s++
        continue
      }
      val f = frac(t * 0.55 + s * 7.31)
      val x = lerp(nodeX[a], nodeX[b], f)
      val y = lerp(nodeY[a], nodeY[b], f)
      val z = lerp(nodeZ[a], nodeZ[b], f)
      val l = max(1e-6, sqrt(x * x + y * y + z * z))
      proj.project(x / l, y / l, z / l)
      val zr = proj.pz
      val depth = (zr + 1) / 2
      dot(proj.px, proj.py, zr, (nodeR * 1.5 + nodeRDepth * depth) * rs, 0.05, 0.5 + 0.5 * depth)
      s++
    }
  }

  /** The faint Fibonacci ghost sphere shared by braid and ribbon. */
  private fun ghostSphere(ghostN: Double, radius: Double, rs: Double) {
    var i = 0.0
    while (i < ghostN) {
      val fy = 1 - (2 * (i + 0.5)) / ghostN
      val rad = sqrt(1 - fy * fy)
      val fa = i * GOLDEN_ANGLE
      proj.project(rad * cos(fa) * radius, fy * radius, rad * sin(fa) * radius)
      val z = proj.pz
      val depth = (z / radius + 1) / 2
      dot(proj.px, proj.py, z, 0.8 * rs, 0.78, 0.1 + 0.22 * depth)
      i++
    }
  }

  // --- braid: three strands plait around the sphere — weaving -----------

  private fun braid(size: Double, t: Double, o: ModeOpts) {
    val cx = size / 2
    val cy = size / 2
    val radius = (size / 2) * 0.76
    proj.set(t * 0.4, 0.3, cx, cy, 1.0)
    val rs = radiusScale(size, o["rsPow"] ?: 0.6)
    ghostSphere(o["ghostN"] ?: 150.0, radius, rs)

    val strandN = o["strandN"] ?: 52.0
    val turns = o["turns"] ?: 3.0
    val rBase = o["rBase"] ?: 1.2
    val rDepth = o["rDepth"] ?: 1.8
    for (si in 0 until 3) {
      val phase = (si / 3.0) * 2 * PI
      var i = 0.0
      while (i < strandN) {
        val u = (frac(i / strandN + t * 0.045) * 2 - 1) * 0.96
        val surf = sqrt(max(0.0, 1 - u * u))
        val endFade = min(1.0, (1 - abs(u)) / 0.1)
        val a = u * PI * turns + phase
        val weave = 1 + 0.075 * sin(u * PI * turns * 2 + phase * 2 + t * 0.8)
        val rr = surf * radius * weave
        proj.project(cos(a) * rr, u * radius * weave, sin(a) * rr)
        val zr = proj.pz
        val depth = (zr / radius + 1) / 2
        dot(
          proj.px,
          proj.py,
          zr,
          (rBase + rDepth * depth) * rs,
          0.55 - 0.45 * depth,
          endFade * (0.45 + 0.55 * depth),
        )
        i++
      }
    }
  }

  // --- ribbon / ring: undulating sash — composing; face-on ring — breathing

  private fun ribbon(size: Double, t: Double, o: ModeOpts) {
    val cx = size / 2
    val cy = size / 2
    val radius = (size / 2) * 0.78
    val spin = o["spin"] ?: 1.0
    val camTilt = 0.3
    proj.set(t * 0.1 * spin, camTilt, cx, cy, 1.0)
    val rs = radiusScale(size, o["rsPow"] ?: 0.6)
    ghostSphere(o["ghostN"] ?: 150.0, radius, rs)

    val faceOnV = o["faceOn"]
    val faceOn = faceOnV != null && faceOnV != 0.0 && !faceOnV.isNaN()
    val ya = t * 0.24 * spin
    val ta = if (faceOn) -camTilt else 0.55 + 0.3 * sin(t * 0.18) * spin
    val ux = cos(ya)
    val uy = 0.0
    val uz = sin(ya)
    val vx = -uz * sin(ta)
    val vy = cos(ta)
    val vz = ux * sin(ta)
    val nx = uy * vz - uz * vy
    val ny = uz * vx - ux * vz
    val nz = ux * vy - uy * vx

    val wobMul = o["wobMul"] ?: 1.0
    val wobAmp = 0.23 * wobMul
    val baseR = if (faceOn) radius / (1 + 0.85 * wobAmp) else radius
    val rBase = o["rBase"] ?: 1.1
    val rDepth = o["rDepth"] ?: 1.7

    val baseLanes = o["lanes"] ?: 5.0
    val segs = o["segs"] ?: 88.0
    val lanes = max(1.0, jsRound(baseLanes * (o["bandMul"] ?: 1.0)))
    var w = 0.0
    while (w < lanes) {
      val laneOff = (w - (lanes - 1) / 2) * 0.075
      val edge = abs(w - (lanes - 1) / 2) / max(1.0, (lanes - 1) / 2)
      var k = 0.0
      while (k < segs) {
        val a = (k / segs) * 2 * PI
        val wob = (0.16 * sin(a * 3 - t * 1.7 + w * 0.22) + 0.07 * sin(a * 5 + t * 1.1)) * wobMul
        val radial = if (faceOn) 1 + wob else 1.0
        val off = if (faceOn) laneOff else laneOff + wob
        val x = ux * cos(a) + vx * sin(a) + nx * off
        val y = uy * cos(a) + vy * sin(a) + ny * off
        val z = uz * cos(a) + vz * sin(a) + nz * off
        val l = sqrt(x * x + y * y + z * z)
        val rr = baseR * radial
        proj.project((x / l) * rr, (y / l) * rr, (z / l) * rr)
        val zr = proj.pz
        val depth = (zr / radius + 1) / 2
        dot(
          proj.px,
          proj.py,
          zr,
          (rBase + rDepth * depth) * (1 - 0.25 * edge) * rs,
          0.52 - 0.44 * depth + 0.18 * edge,
          0.4 + 0.6 * depth,
        )
        k++
      }
      w++
    }
  }

  // --- morph: dotted outline circle → triangle → square — shaping -------

  private fun morph(size: Double, t: Double, o: ModeOpts) {
    val kCount = cycle.size
    val tc = t % (SEG * kCount)
    val shape = o["shape"]
    val held = if (shape != null && shape >= 0 && shape < kCount) floor(shape).toInt() else -1
    val k = if (held >= 0) held else floor(tc / SEG).toInt()
    val local = if (held >= 0) t % SEG else tc - k * SEG
    val m = if (held >= 0) 0.0 else if (local > HOLD) smoothE((local - HOLD) / MORPH) else 0.0
    val sprd = o["spread"] ?: 1.0

    val pA = cycle[k]
    val pB = if (held >= 0) pA else cycle[(k + 1) % kCount]
    for (i in 0 until MORPH_SAMPLES) {
      val f = i.toDouble() / MORPH_SAMPLES
      pA.eval(f)
      val ax0 = pA.ox
      val ay0 = pA.oy
      pB.eval(f)
      ptsX[i] = (ax0 + (pB.ox - ax0) * m) * sprd
      ptsY[i] = (ay0 + (pB.oy - ay0) * m) * sprd
    }
    var total = 0.0
    for (i in 0 until MORPH_SAMPLES) {
      val j = (i + 1) % MORPH_SAMPLES
      val l = hypot(ptsX[j] - ptsX[i], ptsY[j] - ptsY[i])
      segLen[i] = l
      total += l
    }

    val count = max(6.0, jsRound(34 * (o["iconD"] ?: 1.0)))
    val re = (o["rDot"] ?: 0.021) * 1.35 * sprd
    val pulse = 1 + 0.02 * sin(local * 3.1)
    val c2 = size / 2
    var seg = 0
    var acc = 0.0
    var k2 = 0.0
    while (k2 < count) {
      val target = (k2 / count) * total
      while (acc + segLen[seg] < target && seg < MORPH_SAMPLES - 1) {
        acc += segLen[seg]
        seg++
      }
      val j = (seg + 1) % MORPH_SAMPLES
      val f = if (segLen[seg] != 0.0) min(1.0, (target - acc) / segLen[seg]) else 0.0
      val x = (ptsX[seg] + (ptsX[j] - ptsX[seg]) * f) * pulse
      val y = (ptsY[seg] + (ptsY[j] - ptsY[seg]) * f) * pulse
      dot(c2 + x * size, c2 + y * size, 0.0, max(0.35, re * size), 0.1)
      k2++
    }
  }
}
