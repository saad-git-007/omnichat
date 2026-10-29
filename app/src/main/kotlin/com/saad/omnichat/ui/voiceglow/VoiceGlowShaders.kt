/*
 * Ported from voice-glow (github.com/Jakubantalik/Libraries.dev, packages/voice-glow).
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

package com.saad.omnichat.ui.voiceglow

/**
 * AGSL port of `VoiceGlowShaders.metal` (itself a port of the CSS layers in `styles.ts`).
 *
 * [GLOW] paints the inner light, the edge stroke (with the hot core) and the bloom in one pass,
 * bottom to top. Each layer is a stack of explicit-size radial gradients (`color 0%, transparent F%`,
 * first lobe on top) masked by the ellipse the glow rises in. The CSS bloom's 10px blur is
 * approximated analytically: each bloom cone is widened by the blur's variance and its peak scaled
 * down to keep its mass (see [VoiceGlowRenderer]).
 */
internal object VoiceGlowShaders {
  const val GLOW = """
uniform float2 size;
uniform float radius;
uniform float border;
uniform float2 fades;
uniform float2 innerEdge;
uniform float4 shadow;
uniform float3 opacity;
uniform float3 lobeAlpha;
uniform float4 maskA;
uniform float4 maskB;
uniform float4 lobeI[7];
uniform float4 lobeS[7];
uniform float4 lobeB[7];
uniform float4 lobeC[7];
uniform float4 hiGeom;
uniform float3 hiColor;
uniform float4 hiStops;

float rrSdf(float2 p, float2 hs, float r) {
  float2 q = abs(p) - hs + r;
  return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

float ellD(float2 p, float4 e) {
  if (e.z <= 0.0 || e.w <= 0.0) { return 1000000.0; }
  return length((p - e.xy) / e.zw);
}

float maskAlpha(float d, float mid, float tail) {
  if (d >= 1.0) { return 0.0; }
  if (d <= mid) { return mix(1.0, 0.5, d / mid); }
  if (tail < 0.0) { return mix(0.5, 0.0, (d - mid) / (1.0 - mid)); }
  if (d <= 0.85) { return mix(0.5, tail, (d - mid) / (0.85 - mid)); }
  return mix(tail, 0.0, (d - 0.85) / 0.15);
}

float4 srcOver(float4 src, float4 dst) {
  return src + dst * (1.0 - src.a);
}

float cone(float2 p, float4 e, float stop) {
  return max(0.0, 1.0 - ellD(p, e) / stop);
}

half4 main(float2 p) {
  float dA = ellD(p, maskA);
  float mInner = maskAlpha(dA, 0.45, 0.3);
  float mStroke = maskAlpha(dA, 0.45, -1.0);
  float mBloom = maskAlpha(ellD(p, maskB), 0.35, -1.0);
  if (mInner + mStroke + mBloom <= 0.0) { return half4(0.0); }

  float2 c = size * 0.5;
  float2 rel = p - c;
  float outerSdf = rrSdf(rel, c, radius);
  float outerCov = 1.0 - smoothstep(-0.5, 0.5, outerSdf);
  float ringIn = 1.0 - smoothstep(-0.5, 0.5, rrSdf(rel, c - border, max(radius - border, 0.0)));
  float ring = max(outerCov - ringIn, 0.0);
  float cf = innerEdge.x;
  float v = max(max(0.0, 1.0 - p.y / cf), max(0.0, 1.0 - (size.y - p.y) / cf));
  float h = max(max(0.0, 1.0 - p.x / cf), max(0.0, 1.0 - (size.x - p.x) / cf));
  float innerGeom = outerCov * (v + h * (1.0 - v));

  float4 ci = float4(0.0);
  float4 cs = float4(0.0);
  float4 cb = float4(0.0);
  for (int i = 0; i < 7; i++) {
    float4 rgb = float4(lobeC[6 - i].rgb, 1.0);
    ci = srcOver(rgb * (lobeAlpha.x * cone(p, lobeI[6 - i], fades.x)), ci);
    cs = srcOver(rgb * (lobeAlpha.y * cone(p, lobeS[6 - i], fades.x)), cs);
    cb = srcOver(rgb * (lobeAlpha.z * lobeC[6 - i].a * cone(p, lobeB[6 - i], fades.y)), cb);
  }

  float hd = ellD(p, hiGeom);
  float ha = 0.0;
  if (hd <= hiStops.y) {
    ha = mix(hiStops.x, hiStops.z, hd / hiStops.y);
  } else if (hd < hiStops.w) {
    ha = mix(hiStops.z, 0.0, (hd - hiStops.y) / (hiStops.w - hiStops.y));
  }
  cs = srcOver(float4(hiColor, 1.0) * ha, cs);

  // box-shadow: inset 0 0 <blur> 1px
  float sh = shadow.a * (1.0 - smoothstep(border - innerEdge.y, border + innerEdge.y, -outerSdf));
  ci = srcOver(float4(shadow.rgb, 1.0) * sh, ci);

  float4 col = ci * (innerGeom * mInner * opacity.x);
  col = srcOver(cs * (ring * mStroke * opacity.y), col);
  col = srcOver(cb * (mBloom * opacity.z), col);
  return half4(col);
}
"""

  /**
   * The light theme's epicentre: a white radial wash at the source, only under the band line
   * (the same bell the band is stroked along), its clip edge softened in place of the blur.
   */
  const val WASH = """
uniform float4 lineA;
uniform float4 lineB;
uniform float4 lineC;
uniform float4 lineD;
uniform float4 wash;
uniform float4 stops;
uniform float2 washK;

float bell(float t) {
  float side = t < 0.0 ? 1.0 - lineB.z : 1.0 + lineB.z;
  float s = max(0.05, lineB.y * side);
  float v = exp(-pow(max(abs(t) / s, 0.00001), lineB.x));
  float tl = exp(-pow(1.0 / s, lineB.x));
  return max(0.0, (v - tl) / (1.0 - tl));
}

float tailLift(float dist, float edge) {
  if (lineB.w <= 0.0 || edge <= 0.0) { return 0.0; }
  float start = edge * clamp(lineC.x, 0.0, 0.98);
  if (dist <= start) { return 0.0; }
  float u = min(1.0, (dist - start) / max(1.0, edge - start));
  return lineB.w * pow(max(u, 0.00001), max(0.5, lineC.y));
}

float cornerLift(float x) {
  float r = lineD.z;
  if (r <= 0.0) { return 0.0; }
  float d = min(x, lineD.w - x);
  if (d >= r) { return 0.0; }
  if (d <= 0.0) { return r; }
  float dx = r - d;
  return r - sqrt(max(0.0, r * r - dx * dx));
}

half4 main(float2 p) {
  if (p.x < lineD.x || p.x > lineD.y || wash.z <= 0.0 || wash.w <= 0.0) { return half4(0.0); }
  float centre = lineA.x;
  float t = clamp((p.x - centre) / max(1.0, lineA.y), -1.0, 1.0);
  float edge = (p.x < centre ? centre : lineD.w - centre) + lineC.z;
  float y = bell(t) + tailLift(abs(p.x - centre), edge);
  float ly = lineA.w - lineA.z * y - cornerLift(p.x) * lineC.w;
  float below = smoothstep(-washK.y, washK.y, p.y - ly);

  float d = length((p - wash.xy) / wash.zw);
  float a = 0.0;
  if (d <= stops.x) {
    a = 1.0;
  } else if (d <= stops.y) {
    a = mix(1.0, stops.z, (d - stops.x) / max(stops.y - stops.x, 0.0001));
  } else if (d < stops.w) {
    a = mix(stops.z, 0.0, (d - stops.y) / max(stops.w - stops.y, 0.0001));
  }
  a = a * below * washK.x;
  return half4(a, a, a, a);
}
"""
}
