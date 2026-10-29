package com.saad.omnichat.ui.theme

import androidx.compose.ui.graphics.Color

// Night gradient world: navy-indigo ground lit by crimson and ember glows (after Codioful's gradient), rose accent.
internal object Dark {
  val background = Color(0xFF0A0724)
  val surface = Color(0xFF0A0724)
  val surfaceContainerLowest = Color(0xFF07051A)
  val surfaceContainerLow = Color(0xFF120E30)
  val surfaceContainer = Color(0xFF17123A)
  val surfaceContainerHigh = Color(0xFF1E1846)
  val surfaceContainerHighest = Color(0xFF282153)
  val onSurface = Color(0xFFF3F0FA)
  val onSurfaceVariant = Color(0xFFCAC3DE)
  val outline = Color(0xFF746C96)
  val outlineVariant = Color(0xFF332C5A)
  // maroon fills (send/stop, buttons, switches) with white content: 7.3:1. Text accents use accentText() instead.
  val primary = Color(0xFFA3214A)
  val onPrimary = Color(0xFFFFFFFF)
  val primaryContainer = Color(0xFF5A1B4E)
  val onPrimaryContainer = Color(0xFFFFDDEA)
  val secondary = Color(0xFFB9A8FF)
  val onSecondary = Color(0xFF241066)
  val secondaryContainer = Color(0xFF2E2266)
  val onSecondaryContainer = Color(0xFFE8E0FF)
  val tertiaryContainer = Color(0xFF4A2433)
  val onTertiaryContainer = Color(0xFFFFD9E2)
  val error = Color(0xFFFF8A80)
  val onError = Color(0xFF3B0907)
  val errorContainer = Color(0xFF5A1A1A)
  val onErrorContainer = Color(0xFFFFDAD6)
}

internal object Light {
  val background = Color(0xFFFBF7FA)
  val surface = Color(0xFFFBF7FA)
  val surfaceContainerLowest = Color(0xFFFFFFFF)
  val surfaceContainerLow = Color(0xFFF6F0F5)
  val surfaceContainer = Color(0xFFF0E9F0)
  val surfaceContainerHigh = Color(0xFFEAE2EA)
  val surfaceContainerHighest = Color(0xFFE3DAE4)
  val onSurface = Color(0xFF1A1424)
  val onSurfaceVariant = Color(0xFF574E66)
  val outline = Color(0xFF857C93)
  val outlineVariant = Color(0xFFD9D0DD)
  val primary = Color(0xFF8E1B3A)
  val onPrimary = Color(0xFFFFFFFF)
  val primaryContainer = Color(0xFFFFD9E6)
  val onPrimaryContainer = Color(0xFF3E0420)
  val secondary = Color(0xFF5B47C8)
  val onSecondary = Color(0xFFFFFFFF)
  val secondaryContainer = Color(0xFFE5DEFF)
  val onSecondaryContainer = Color(0xFF1B0B5C)
  val tertiaryContainer = Color(0xFFFFE0D3)
  val onTertiaryContainer = Color(0xFF3A1206)
  val error = Color(0xFFBA1A1A)
  val onError = Color(0xFFFFFFFF)
  val errorContainer = Color(0xFFFFDAD6)
  val onErrorContainer = Color(0xFF410002)
}

/** Backdrop glows. Static: drawn once, no animation cost during inference. */
data class BackdropColors(val top: Color, val bottom: Color, val glowA: Color, val glowB: Color, val glowC: Color)

internal val BackdropDark =
  BackdropColors(top = Color(0xFF040312), bottom = Color(0xFF070420), glowA = Color(0xFFE0103F), glowB = Color(0xFFFF4A1C), glowC = Color(0xFF3A16A0))
internal val BackdropLight =
  BackdropColors(top = Color(0xFFFCF8FB), bottom = Color(0xFFF7F1FA), glowA = Color(0xFFFFC2D3), glowB = Color(0xFFFFD2BC), glowC = Color(0xFFD8D0FF))

/** Orb light: the accent plus two neighbours, so it reads as one luminous material, not a rainbow. */
data class OrbColors(val core: Color, val a: Color, val b: Color, val glow: Color)

internal val OrbDark = OrbColors(core = Color(0xFFEDE9FF), a = Color(0xFF8C7BFF), b = Color(0xFF4FD8C8), glow = Color(0xFF6F5CFF))
internal val OrbLight = OrbColors(core = Color(0xFFFFFFFF), a = Color(0xFF6A56F0), b = Color(0xFF14B3A3), glow = Color(0xFF8D7DFF))

/** Backend series colours (categorical slots 1-3, validated all-pairs for both modes). Fixed per backend, never by rank. */
// Categorical slots 1-4 of the dataviz palette in fixed order. Slot 4 (NPU, yellow) only falls below the
// separation floor against slot 2 (Vulkan, orange), which can't run on the Adreno 740; charts label backends by name too.
data class SeriesColors(val gpuOpenCl: Color, val gpuVulkan: Color, val cpu: Color, val npu: Color)

/** User message bubble: rose to violet. White text is 6.0:1 on the rose end and 7.1:1 on the violet end. */
val BubbleGradient = listOf(Color(0xFFB0306A), Color(0xFF6A35C8))

internal val SeriesDark = SeriesColors(gpuOpenCl = Color(0xFF3987E5), gpuVulkan = Color(0xFFD95926), cpu = Color(0xFF199E70), npu = Color(0xFFC98500))
internal val SeriesLight = SeriesColors(gpuOpenCl = Color(0xFF2A78D6), gpuVulkan = Color(0xFFEB6834), cpu = Color(0xFF1BAF7A), npu = Color(0xFFEDA100))
