package com.aibuilt.claudeclone.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aibuilt.claudeclone.data.AttachmentProcessor
import com.aibuilt.claudeclone.data.ChatMessage
import com.aibuilt.claudeclone.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: MainViewModel, onSettings: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var input by rememberSaveable { mutableStateOf("") }
    var archiveManifest by rememberSaveable { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                AttachmentProcessor(context).read(uri)
            }.onSuccess { attachment ->
                viewModel.setAttachment(attachment)
                archiveManifest = attachment.archiveManifest
                if (attachment.archiveManifest.isNotBlank()) {
                    Toast.makeText(
                        context,
                        "Archive inspected. Tap its attachment chip to view the manifest.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }.onFailure { error ->
                Toast.makeText(
                    context,
                    error.message ?: "Unable to access the selected file.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    if (archiveManifest.isNotBlank()) {
        AlertDialog(
            onDismissRequest = { archiveManifest = "" },
            title = { Text("Archive contents") },
            text = {
                Column {
                    Text(
                        archiveManifest,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { archiveManifest = "" }) {
                    Text("Close")
                }
            }
        )
    }

    val listState = rememberLazyListState()
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.navigationBars,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Claude", fontWeight = FontWeight.SemiBold)
                        Text(
                            state.activeProfile.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::clearChat) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = "Clear conversation"
                        )
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "API settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            Surface(
                modifier = Modifier
                    .navigationBarsPadding()
                    .imePadding(),
                color = MaterialTheme.colorScheme.background,
                shadowElevation = 6.dp
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    state.attachment?.let { attachment ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.AttachFile,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp)
                            )
                            Text(
                                text = attachment.name,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (attachment.archiveManifest.isNotBlank()) {
                                TextButton(
                                    onClick = {
                                        archiveManifest = attachment.archiveManifest
                                    }
                                ) {
                                    Text("Inspect")
                                }
                            }
                            IconButton(
                                onClick = { viewModel.setAttachment(null) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Text("×", style = MaterialTheme.typography.titleLarge)
                            }
                        }
                        val detail = buildString {
                            attachment.sizeBytes?.let { append("${it / 1024} KB · ") }
                            append(attachment.mimeType)
                            if (
                                attachment.base64.isBlank() &&
                                attachment.extractedText.isBlank()
                            ) {
                                append(" · metadata only; this API may not accept this file")
                            }
                        }
                        Text(
                            detail,
                            modifier = Modifier.padding(start = 24.dp, bottom = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(verticalAlignment = Alignment.Bottom) {
                        IconButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                            Icon(
                                Icons.Default.AttachFile,
                                contentDescription = "Attach any file"
                            )
                        }
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Message…") },
                            shape = RoundedCornerShape(24.dp),
                            maxLines = 6
                        )
                        Spacer(Modifier.width(4.dp))
                        IconButton(
                            onClick = {
                                viewModel.sendMessage(input)
                                input = ""
                            },
                            enabled = !state.sending &&
                                (input.isNotBlank() || state.attachment != null),
                            modifier = Modifier
                                .padding(bottom = 4.dp)
                                .background(
                                    if (
                                        !state.sending &&
                                        (input.isNotBlank() || state.attachment != null)
                                    ) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                    CircleShape
                                )
                        ) {
                            if (state.sending) {
                                CircularProgressIndicator(
                                    Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    Icons.Default.Send,
                                    contentDescription = "Send message",
                                    tint = if (
                                        input.isNotBlank() || state.attachment != null
                                    ) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.sending) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (state.messages.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(28.dp)
                    ) {
                        Text(
                            "Claude",
                            style = MaterialTheme.typography.headlineLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "What can I help you with today?",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                        Text(
                            "Connect your own API in settings to start a conversation.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 16.dp,
                        vertical = 18.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    items(state.messages, key = { it.id }) { message ->
                        MessageBubble(message)
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(if (message.isUser) 0.88f else 1f)
                .background(
                    when {
                        message.isError -> MaterialTheme.colorScheme.errorContainer
                        message.isUser -> MaterialTheme.colorScheme.secondaryContainer
                        else -> MaterialTheme.colorScheme.surface
                    },
                    RoundedCornerShape(18.dp)
                )
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                if (message.isUser) "You" else if (message.isError) {
                    "Connection error"
                } else {
                    "Claude"
                },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (message.isError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                }
            )
            message.attachmentName?.let {
                Text(
                    "📎 $it",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 5.dp, bottom = 4.dp)
                )
            }
            Text(
                message.text.ifBlank { " " },
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 5.dp)
            )
        }
    }
}
