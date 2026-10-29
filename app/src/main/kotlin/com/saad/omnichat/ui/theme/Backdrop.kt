package com.saad.omnichat.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Full-window gradient: crimson glow top-left, ember bottom-right, indigo pooling mid-left. */
@Composable
fun Backdrop(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
  val c = LocalOmni.current.backdrop
  Box(
    modifier.fillMaxSize().drawBehind {
      val w = size.width
      val h = size.height
      drawRect(Brush.verticalGradient(listOf(c.top, c.bottom)))
      fun glow(color: Color, at: Offset, radius: Float, alpha: Float) =
        drawRect(Brush.radialGradient(0f to color.copy(alpha = alpha), 1f to Color.Transparent, center = at, radius = radius))
      glow(c.glowC, Offset(w * 0.05f, h * 0.62f), w * 1.1f, 0.22f)
      glow(c.glowA, Offset(-w * 0.12f, -h * 0.02f), w * 0.95f, 0.30f)
      glow(c.glowB, Offset(w * 1.08f, h * 1.02f), w * 1.0f, 0.28f)
    },
  ) {
    // replaces the root Surface, so it must also provide the default content colour
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) { content() }
  }
}

/**
 * Translucent tonal fill for surfaces on the backdrop (no blur: the gradient is the material).
 * Dark mode uses deep navy, not white, so text on it keeps >= 4.5:1 even over the brightest glow.
 */
@Composable
fun veil(strength: Float = 1f): Color {
  val dark = LocalOmni.current.dark
  return if (dark) Color(0xFF1C1645).copy(alpha = (0.58f * strength).coerceAtMost(0.92f))
  else Color.White.copy(alpha = (0.62f * strength).coerceAtMost(0.95f))
}
