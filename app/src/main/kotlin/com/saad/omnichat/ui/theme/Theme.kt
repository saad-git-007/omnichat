package com.saad.omnichat.ui.theme

import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

private val DarkScheme =
  with(Dark) {
    darkColorScheme(
      primary = primary, onPrimary = onPrimary, primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
      secondary = secondary, onSecondary = onSecondary, secondaryContainer = secondaryContainer, onSecondaryContainer = onSecondaryContainer,
      tertiaryContainer = tertiaryContainer, onTertiaryContainer = onTertiaryContainer,
      background = background, onBackground = onSurface, surface = surface, onSurface = onSurface,
      surfaceVariant = surfaceContainerHigh, onSurfaceVariant = onSurfaceVariant,
      surfaceContainerLowest = surfaceContainerLowest, surfaceContainerLow = surfaceContainerLow, surfaceContainer = surfaceContainer,
      surfaceContainerHigh = surfaceContainerHigh, surfaceContainerHighest = surfaceContainerHighest,
      surfaceBright = surfaceContainerHighest, surfaceDim = background,
      outline = outline, outlineVariant = outlineVariant,
      error = error, onError = onError, errorContainer = errorContainer, onErrorContainer = onErrorContainer,
    )
  }

private val LightScheme =
  with(Light) {
    lightColorScheme(
      primary = primary, onPrimary = onPrimary, primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
      secondary = secondary, onSecondary = onSecondary, secondaryContainer = secondaryContainer, onSecondaryContainer = onSecondaryContainer,
      tertiaryContainer = tertiaryContainer, onTertiaryContainer = onTertiaryContainer,
      background = background, onBackground = onSurface, surface = surface, onSurface = onSurface,
      surfaceVariant = surfaceContainerHigh, onSurfaceVariant = onSurfaceVariant,
      surfaceContainerLowest = surfaceContainerLowest, surfaceContainerLow = surfaceContainerLow, surfaceContainer = surfaceContainer,
      surfaceContainerHigh = surfaceContainerHigh, surfaceContainerHighest = surfaceContainerHighest,
      surfaceBright = surfaceContainerLowest, surfaceDim = surfaceContainerHighest,
      outline = outline, outlineVariant = outlineVariant,
      error = error, onError = onError, errorContainer = errorContainer, onErrorContainer = onErrorContainer,
    )
  }

/** Extra tokens Material has no role for. */
data class OmniTokens(val orb: OrbColors, val series: SeriesColors, val reduceMotion: Boolean, val backdrop: BackdropColors, val dark: Boolean)

val LocalOmni = staticCompositionLocalOf { OmniTokens(OrbDark, SeriesDark, reduceMotion = false, BackdropDark, dark = true) }

@Composable
fun OmniChatTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
  val context = LocalContext.current
  // "Remove animations" sets the animator scale to 0: swap motion for instant cuts / static states
  val reduceMotion = remember {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
  }
  val tokens =
    if (darkTheme) OmniTokens(OrbDark, SeriesDark, reduceMotion, BackdropDark, dark = true)
    else OmniTokens(OrbLight, SeriesLight, reduceMotion, BackdropLight, dark = false)
  CompositionLocalProvider(LocalOmni provides tokens) {
    MaterialTheme(colorScheme = if (darkTheme) DarkScheme else LightScheme, typography = Typography, content = content)
  }
}

/** Rose for accent text and small marks (tok/s, selected chat): maroon would be too dim as text on navy. */
@androidx.compose.runtime.Composable
fun accentText(): androidx.compose.ui.graphics.Color =
  if (LocalOmni.current.dark) androidx.compose.ui.graphics.Color(0xFFFF86B4) else androidx.compose.ui.graphics.Color(0xFFB0245E)
