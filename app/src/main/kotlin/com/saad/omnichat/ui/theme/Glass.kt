package com.saad.omnichat.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Glass panel: translucent tint over the backdrop, a sheen from the top, a specular rim brighter on the
 * upper edge, and an offset shadow for lift. [tint] defaults to the deep-navy veil so text keeps contrast.
 */
@Composable
fun Modifier.glass(shape: Shape, lift: Dp = 8.dp, tint: Color = veil(1.3f)): Modifier = glass(shape, lift, SolidColor(tint))

/** Glass over a gradient fill (e.g. the user's message bubble); same sheen, rim and lift. */
@Composable
fun Modifier.glass(shape: Shape, lift: Dp, fill: Brush): Modifier {
  val dark = LocalOmni.current.dark
  val rimTop = if (dark) Color.White.copy(alpha = 0.30f) else Color.White.copy(alpha = 0.95f)
  val rimBottom = if (dark) Color.White.copy(alpha = 0.05f) else Color(0x14000000)
  val sheen = if (dark) Color.White.copy(alpha = 0.09f) else Color.White.copy(alpha = 0.55f)
  return this.shadow(lift, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.55f), spotColor = Color.Black.copy(alpha = 0.7f))
    .clip(shape)
    .background(fill)
    .background(Brush.verticalGradient(0f to sheen, 0.55f to Color.Transparent))
    .border(1.dp, Brush.verticalGradient(listOf(rimTop, rimBottom)), shape)
}

/** Secondary action as a lifted glass capsule (replaces the tonal button on the backdrop). */
@Composable
fun GlassButton(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
  val interaction = remember { MutableInteractionSource() }
  Row(
    modifier
      .glass(CircleShape, lift = 6.dp)
      .clickable(interactionSource = interaction, indication = ripple(), onClick = onClick)
      .heightIn(min = 48.dp)
      .padding(horizontal = 20.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.Center,
  ) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) { content() }
  }
}

/** Bottom sheets float over the backdrop: near-opaque navy so long settings text stays crisp. */
@Composable
fun sheetGlass(): Color = if (LocalOmni.current.dark) Color(0xF2140F36) else Color(0xF5FFFFFF)

/** The history drawer sits over a chat full of text, so it is fully opaque (glass let the chat bleed through). */
@Composable
fun drawerSurface(): Color = if (LocalOmni.current.dark) Color(0xFF140F36) else Color(0xFFFFFFFF)
