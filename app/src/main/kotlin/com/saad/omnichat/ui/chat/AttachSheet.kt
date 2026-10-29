package com.saad.omnichat.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.saad.omnichat.ui.theme.BubbleGradient
import com.saad.omnichat.ui.theme.glass
import com.saad.omnichat.ui.theme.sheetGlass

/** What the + button offers: each choice opens the system app that fits it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachSheet(onDismiss: () -> Unit, onPhotos: () -> Unit, onAudio: () -> Unit, onFiles: () -> Unit) {
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor = sheetGlass(),
  ) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 12.dp)) {
      Text("Add to your message", style = MaterialTheme.typography.titleMedium)
      Spacer(Modifier.height(16.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AttachTile(Icons.Outlined.PhotoLibrary, "Photos", "From your gallery", onPhotos, Modifier.weight(1f))
        AttachTile(Icons.Outlined.GraphicEq, "Audio", "Recordings and music", onAudio, Modifier.weight(1f))
        AttachTile(Icons.Outlined.Description, "Files", "PDF, Excel, CSV, text", onFiles, Modifier.weight(1f))
      }
    }
  }
}

@Composable
private fun AttachTile(icon: ImageVector, title: String, caption: String, onClick: () -> Unit, modifier: Modifier) {
  Column(
    modifier.glass(RoundedCornerShape(22.dp), lift = 6.dp).clickable(onClick = onClick).padding(vertical = 18.dp, horizontal = 10.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Box(Modifier.size(48.dp).clip(CircleShape).background(Brush.linearGradient(BubbleGradient)), contentAlignment = Alignment.Center) {
      Icon(icon, null, Modifier.size(24.dp), tint = androidx.compose.ui.graphics.Color.White)
    }
    Spacer(Modifier.height(10.dp))
    Text(title, style = MaterialTheme.typography.titleSmall)
    Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, minLines = 2)
  }
}
