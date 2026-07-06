package com.charles.meshtalk.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.charles.meshtalk.app.data.MessageEntity
import com.charles.meshtalk.app.data.ReactionEntity
import com.charles.meshtalk.app.repository.MeshRepository
import com.charles.meshtalk.app.ui.theme.SignalGreen

private val QUICK_REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

/**
 * Wraps a message's content with the shared action set: long-press to copy/react/edit/delete,
 * an "(edited)" tag, a "message deleted" placeholder, and a row of emoji-reaction chips. Used by
 * both the public feed and DM threads so the two stay in sync.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageContentWithActions(repository: MeshRepository, message: MessageEntity) {
    var showSheet by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    val reactionsByMessage by repository.reactionsByMessage.collectAsState()
    val reactions = reactionsByMessage[message.id].orEmpty()
    val myKey by repository.myPublicKeyHex.collectAsState()
    val clipboard = LocalClipboardManager.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = { if (!message.deleted) showSheet = true }
            )
    ) {
        if (message.deleted) {
            Text(
                "This message was deleted",
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        } else {
            MessageContentBody(message, onLongPress = { showSheet = true })
            if (message.edited) {
                Text(
                    "(edited)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (reactions.isNotEmpty()) {
            ReactionsRow(reactions, myKey) { emoji -> repository.reactToMessage(message, emoji) }
        }
    }

    if (showSheet) {
        MessageActionSheet(
            message = message,
            onDismiss = { showSheet = false },
            onCopy = {
                clipboard.setText(AnnotatedString(message.body))
                showSheet = false
            },
            onReact = { emoji ->
                repository.reactToMessage(message, emoji)
                showSheet = false
            },
            onEdit = {
                showSheet = false
                showEditDialog = true
            },
            onDelete = {
                repository.deleteMessage(message)
                showSheet = false
            }
        )
    }

    if (showEditDialog) {
        EditMessageDialog(
            initialText = message.body,
            onDismiss = { showEditDialog = false },
            onConfirm = { newText ->
                repository.editMessage(message, newText)
                showEditDialog = false
            }
        )
    }
}

@Composable
private fun ReactionsRow(reactions: List<ReactionEntity>, myKey: String?, onToggle: (String) -> Unit) {
    val grouped = reactions.groupBy { it.emoji }
    Row(modifier = Modifier.padding(top = 4.dp)) {
        grouped.forEach { (emoji, reactors) ->
            val reactedByMe = reactors.any { it.reactorPubKeyHex == myKey }
            Row(
                modifier = Modifier
                    .padding(end = 6.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (reactedByMe) SignalGreen.copy(alpha = 0.25f)
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .clickable { onToggle(emoji) }
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(emoji, fontSize = 14.sp)
                if (reactors.size > 1) {
                    Text(
                        " ${reactors.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageActionSheet(
    message: MessageEntity,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onReact: (String) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            Text(
                "React",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                items(QUICK_REACTIONS) { emoji ->
                    Text(
                        emoji,
                        fontSize = 26.sp,
                        modifier = Modifier.clickable { onReact(emoji) }.padding(6.dp)
                    )
                }
            }
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Copy") },
                leadingContent = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onCopy)
            )
            if (message.isMine && !message.deleted && message.contentType == "TEXT") {
                ListItem(
                    headlineContent = { Text("Edit") },
                    leadingContent = { Icon(Icons.Filled.Edit, contentDescription = null) },
                    modifier = Modifier.clickable(onClick = onEdit)
                )
            }
            if (message.isMine && !message.deleted) {
                ListItem(
                    headlineContent = { Text("Delete") },
                    leadingContent = { Icon(Icons.Filled.Delete, contentDescription = null) },
                    modifier = Modifier.clickable(onClick = onDelete)
                )
            }
        }
    }
}

@Composable
private fun EditMessageDialog(initialText: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initialText) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit message") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onConfirm(text.trim()) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
