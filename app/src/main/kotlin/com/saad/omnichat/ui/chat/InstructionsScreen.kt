package com.saad.omnichat.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import com.saad.omnichat.ui.theme.sheetGlass

private const val NEW_ID = -1L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstructionsScreen(
  instructions: List<Instruction>,
  onBack: () -> Unit,
  onAdd: (String) -> Unit,
  onUpdate: (Instruction) -> Unit,
  onDelete: (Long) -> Unit,
) {
  var editing by remember { mutableStateOf<Instruction?>(null) }
  val suggestions = SuggestedInstructions.filter { s -> instructions.none { it.text.equals(s, ignoreCase = true) } }

  Scaffold(
    containerColor = Color.Transparent,
    topBar = {
      TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent),
        title = { Text("Instructions") },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
      )
    },
    floatingActionButton = {
      ExtendedFloatingActionButton(
        onClick = { editing = Instruction(NEW_ID, "") },
        icon = { Icon(Icons.Default.Add, null) },
        text = { Text("Add instruction") },
      )
    },
  ) { pad ->
    LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
      item {
        Text(
          "Tell the model how to respond, for example how long or in what format. Instructions that are on are added to every new chat and stay on this phone.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
        )
      }

      if (instructions.isEmpty()) {
        item {
          Text(
            "No instructions yet. Add your own, or start from a suggestion below.",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
          )
        }
      }

      items(instructions, key = { it.id }) { ins ->
        Row(
          Modifier.fillMaxWidth().clickable { editing = ins }.heightIn(min = 64.dp).padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp).animateItem(),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            ins.text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (ins.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
          )
          Spacer(Modifier.width(12.dp))
          Switch(checked = ins.enabled, onCheckedChange = { onUpdate(ins.copy(enabled = it)) })
        }
        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
      }

      if (suggestions.isNotEmpty()) {
        item {
          Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 28.dp)) {
            Text("Suggestions", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
              suggestions.forEach { s ->
                SuggestionChip(onClick = { onAdd(s) }, label = { Text(s) }, icon = { Icon(Icons.Default.Add, null, Modifier.size(16.dp)) })
              }
            }
          }
        }
      }
    }
  }

  editing?.let { current ->
    EditInstructionSheet(
      initial = current,
      onDismiss = { editing = null },
      onSave = { text ->
        if (current.id == NEW_ID) onAdd(text) else onUpdate(current.copy(text = text))
        editing = null
      },
      onDelete = if (current.id == NEW_ID) null else ({ onDelete(current.id); editing = null }),
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditInstructionSheet(initial: Instruction, onDismiss: () -> Unit, onSave: (String) -> Unit, onDelete: (() -> Unit)?) {
  var text by remember { mutableStateOf(initial.text) }
  val focus = remember { FocusRequester() }
  LaunchedEffect(Unit) { focus.requestFocus() }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor = sheetGlass(),
  ) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().imePadding()) {
      Text(if (onDelete == null) "New instruction" else "Edit instruction", style = MaterialTheme.typography.titleLarge)
      Spacer(Modifier.height(12.dp))
      OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        placeholder = { Text("For example: Keep responses under 100 words.") },
        minLines = 3,
        maxLines = 8,
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
      )
      Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onDismiss) { Text("Cancel") }
        Spacer(Modifier.width(8.dp))
        Button(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text("Save") }
      }
    }
  }
}
