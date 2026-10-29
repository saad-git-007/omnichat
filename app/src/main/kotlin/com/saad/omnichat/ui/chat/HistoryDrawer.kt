package com.saad.omnichat.ui.chat

import com.saad.omnichat.ui.theme.accentText
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.saad.omnichat.history.ChatSummary
import com.saad.omnichat.ui.theme.GlassButton
import com.saad.omnichat.ui.theme.drawerSurface

/** Saved conversations: tap to reopen, the bin to delete (after a confirmation). */
@Composable
fun HistoryDrawer(
  chats: List<ChatSummary>,
  currentId: Long?,
  enabled: Boolean,
  onNewChat: () -> Unit,
  onOpen: (Long) -> Unit,
  onDelete: (Long) -> Unit,
) {
  var confirm by remember { mutableStateOf<ChatSummary?>(null) }

  ModalDrawerSheet(drawerContainerColor = drawerSurface(), modifier = Modifier.width(320.dp)) {
    Column(Modifier.fillMaxHeight().padding(horizontal = 12.dp)) {
      Text("Chats", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 12.dp, top = 20.dp, bottom = 12.dp))
      GlassButton(onClick = { if (enabled) onNewChat() }, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Outlined.AddComment, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("New chat")
      }
      Spacer(Modifier.size(12.dp))
      if (chats.isEmpty()) {
        Text(
          "Your conversations are saved here after the first reply. They stay on this phone.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(12.dp),
        )
      }
      LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        items(chats, key = { it.id }) { chat ->
          val selected = chat.id == currentId
          Row(
            Modifier.fillMaxWidth()
              .heightIn(min = 56.dp)
              .clip(RoundedCornerShape(16.dp))
              .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else Color.Transparent)
              .clickable(enabled = enabled) { onOpen(chat.id) }
              .padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(
              Icons.Outlined.ChatBubbleOutline,
              null,
              Modifier.size(18.dp),
              tint = if (selected) accentText() else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
              Text(
                chat.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) accentText() else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
              Text(
                DateUtils.getRelativeTimeSpanString(chat.updated, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            IconButton(onClick = { confirm = chat }, enabled = enabled) {
              Icon(Icons.Outlined.DeleteOutline, "Delete ${chat.title}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
          }
        }
      }
    }
  }

  confirm?.let { chat ->
    AlertDialog(
      onDismissRequest = { confirm = null },
      title = { Text("Delete this chat?") },
      text = { Text("\"${chat.title}\" will be removed from this phone. This can't be undone.") },
      confirmButton = {
        TextButton(onClick = { onDelete(chat.id); confirm = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
      },
      dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
    )
  }
}
