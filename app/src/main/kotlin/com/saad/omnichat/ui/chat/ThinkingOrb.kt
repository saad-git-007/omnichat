package com.saad.omnichat.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.saad.omnichat.ui.orbs.OrbSize
import com.saad.omnichat.ui.orbs.OrbState
import com.saad.omnichat.ui.orbs.ThinkingOrbs
import com.saad.omnichat.ui.theme.LocalOmni

enum class OrbMode { IDLE, LOADING, READING_IMAGE, LISTENING, THINKING, WRITING }

/**
 * The model's status orb: the Thinking Orbs "solving" state, drawn exactly as the library draws it
 * (grayscale ink, its own speed and frame rate). [frozen] shows the library's static frame.
 */
@Composable
fun ThinkingOrb(mode: OrbMode, modifier: Modifier = Modifier, frozen: Boolean = false, small: Boolean = false) {
  ThinkingOrbs(
    state = OrbState.SOLVING,
    modifier = modifier,
    size = if (small) OrbSize.Small else OrbSize.Large,
    dark = LocalOmni.current.dark,
    paused = frozen,
  )
}
