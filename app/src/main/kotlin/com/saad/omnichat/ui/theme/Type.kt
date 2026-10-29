package com.saad.omnichat.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val base = Typography()

// Roboto throughout; tighter display/title tracking, relaxed reading body.
val Typography =
  Typography(
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 25.sp, letterSpacing = 0.15.sp),
    labelSmall = base.labelSmall.copy(letterSpacing = 0.3.sp),
  )

/** Measurements: tabular, lining numerals so columns of figures do not jitter while they update. */
val Numeric = TextStyle(fontFeatureSettings = "tnum, lnum")
