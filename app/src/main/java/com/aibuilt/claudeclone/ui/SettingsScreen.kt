package com.aibuilt.claudeclone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aibuilt.claudeclone.data.ApiProfile
import com.aibuilt.claudeclone.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val profile = state.activeProfile

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Custom API") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back to chat")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ProfilePicker(
                profiles = state.profiles,
                selected = state.activeProfileIndex,
                onSelect = viewModel::selectProfile,
                onAdd = viewModel::addProfile,
                onDuplicate = viewModel::duplicateProfile,
                onDelete = viewModel::deleteProfile
            )

            SectionLabel("Connection")
            SettingField("Profile name", profile.name, "Name this saved connection") { value ->
                viewModel.updateProfile { current -> current.copy(name = value) }
            }
            SettingField(
                "Base URL",
                profile.baseUrl,
                "https://api.example.com/v1",
                singleLine = true
            ) { value ->
                viewModel.updateProfile { current -> current.copy(baseUrl = value) }
            }
            Text(
                "Enter the server root or path prefix, including its scheme, host and optional port. The endpoint path is appended to this URL.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SettingField(
                "Endpoint path",
                profile.endpointPath,
                "/chat/completions, /v1/messages, or {base}/models/{model}:generateContent",
                singleLine = true
            ) { value ->
                viewModel.updateProfile { current -> current.copy(endpointPath = value) }
            }
            SettingField(
                "Model name",
                profile.model,
                "Model identifier",
                singleLine = true
            ) { value ->
                viewModel.updateProfile { current -> current.copy(model = value) }
            }

            SectionLabel("Request and response")
            DialectSelector(profile.dialect) { value ->
                viewModel.updateProfile { current ->
                    val standardPaths = setOf(
                        "choices[0].message.content",
                        "content[0].text",
                        "candidates[0].content.parts[0].text"
                    )
                    val newPath = when (value.lowercase()) {
                        "anthropic-compatible" -> "content[0].text"
                        "google-style" -> "candidates[0].content.parts[0].text"
                        else -> "choices[0].message.content"
                    }
                    current.copy(
                        dialect = value,
                        responsePath = if (current.responsePath.isBlank() ||
                            current.responsePath in standardPaths
                        ) {
                            newPath
                        } else {
                            current.responsePath
                        }
                    )
                }
            }
            Text(
                "Automatic mode builds a common request format. For other APIs, use a JSON request template. Response paths support dot names and array indexes, such as choices[0].message.content. If extraction does not match, the raw response body is displayed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SettingField(
                "Response text JSON path",
                profile.responsePath,
                "choices[0].message.content",
                singleLine = true
            ) { value ->
                viewModel.updateProfile { current -> current.copy(responsePath = value) }
            }
            SettingField(
                "Response text template (optional)",
                profile.responseTemplate,
                "Answer: {value}",
                minLines = 2
            ) { value ->
                viewModel.updateProfile { current -> current.copy(responseTemplate = value) }
            }
            Text(
                "Use {value} for the extracted response-path value or {body} for the complete raw response body. Leave empty to display the extracted value (or raw body when the path does not match).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SettingField(
                "System instruction",
                profile.systemPrompt,
                "Instructions sent with each message",
                minLines = 2
            ) { value ->
                viewModel.updateProfile { current -> current.copy(systemPrompt = value) }
            }
            SettingField(
                "Custom JSON request template (optional)",
                profile.requestTemplate,
                """{"model": {model}, "prompt": {text}}""",
                minLines = 5
            ) { value ->
                viewModel.updateProfile { current -> current.copy(requestTemplate = value) }
            }
            Text(
                "Use {model}, {text}, {system}, {messages}, and {attachment}. Put string placeholders without surrounding quotes; they are safely encoded as JSON. {messages} and {attachment} are JSON values.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Request streaming", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Server-sent events are collected and displayed as one reply.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = profile.streaming,
                    onCheckedChange = { enabled ->
                        viewModel.updateProfile { current -> current.copy(streaming = enabled) }
                    }
                )
            }

            SectionLabel("Authentication")
            SettingField(
                "Authentication header name",
                profile.authHeader,
                "Authorization / x-api-key",
                singleLine = true
            ) { value ->
                viewModel.updateProfile { current -> current.copy(authHeader = value) }
            }
            SettingField(
                "Value prefix",
                profile.authPrefix,
                "Bearer (leave empty for no prefix)",
                singleLine = true
            ) { value ->
                viewModel.updateProfile { current -> current.copy(authPrefix = value) }
            }
            SettingField(
                "Authentication value",
                profile.authValue,
                "API key or token",
                secret = true
            ) { value ->
                viewModel.updateProfile { current -> current.copy(authValue = value) }
            }
            SettingField(
                "Additional headers",
                profile.extraHeaders,
                "One per line: X-Custom-Header: value",
                minLines = 3
            ) { value ->
                viewModel.updateProfile { current -> current.copy(extraHeaders = value) }
            }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(
                    Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text("Network compatibility", fontWeight = FontWeight.SemiBold)
                    Text(
                        "HTTP connections and untrusted TLS certificates are enabled for these API calls. This skips certificate and host verification; use only servers you trust.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Button(
                onClick = viewModel::testConnection,
                enabled = !state.testing,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (state.testing) "Testing connection…" else "Test connection")
            }
            if (state.testReport.isNotBlank()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        state.testReport,
                        Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Text(
                "The test sends a short request using the current model, authentication and request settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ProfilePicker(
    profiles: List<ApiProfile>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onAdd: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Saved API profiles",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.weight(1f)
            ) {
                Text(profiles.getOrNull(selected)?.name?.ifBlank { "Unnamed profile" } ?: "Select profile")
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                profiles.forEachIndexed { index, item ->
                    DropdownMenuItem(
                        text = { Text(item.name.ifBlank { "Unnamed profile" }) },
                        onClick = {
                            onSelect(index)
                            expanded = false
                        }
                    )
                }
            }
            IconButton(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = "Add profile")
            }
            IconButton(onClick = onDuplicate) {
                Icon(Icons.Default.FileCopy, contentDescription = "Duplicate profile")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.DeleteOutline, contentDescription = "Delete profile")
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun DialectSelector(value: String, onSelected: (String) -> Unit) {
    val options = listOf("OpenAI-compatible", "Anthropic-compatible", "Google-style")
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text("Automatic request format", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(5.dp))
        OutlinedButton(onClick = { expanded = true }) {
            Text(value)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun SettingField(
    label: String,
    value: String,
    placeholder: String,
    singleLine: Boolean = false,
    minLines: Int = 1,
    secret: Boolean = false,
    onValue: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = singleLine,
        minLines = if (singleLine) 1 else minLines,
        visualTransformation = if (secret) {
            PasswordVisualTransformation()
        } else {
            androidx.compose.ui.text.input.VisualTransformation.None
        }
    )
}
