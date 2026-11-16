package com.aibuilt.claudeclone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aibuilt.claudeclone.viewmodel.MainViewModel
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val profile = state.activeProfile

    LaunchedEffect(profile.baseUrl, profile.authValue) {
        if (profile.baseUrl.isNotBlank()) {
            delay(700)
            viewModel.discoverModels()
        }
    }

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
            OutlinedTextField(
                value = profile.baseUrl,
                onValueChange = { value ->
                    viewModel.updateProfile { it.copy(baseUrl = value) }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Base URL") },
                placeholder = { Text("https://api.example.com/v1") },
                singleLine = true
            )

            OutlinedTextField(
                value = profile.endpointPath,
                onValueChange = { value ->
                    viewModel.updateProfile { it.copy(endpointPath = value) }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Chat endpoint path") },
                placeholder = { Text("/v1/chat/completions") },
                singleLine = true,
                supportingText = { Text("Can include {model}; the base URL path is preserved.") }
            )

            OutlinedTextField(
                value = profile.authValue,
                onValueChange = { value ->
                    viewModel.updateProfile { it.copy(authValue = value) }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("API key / authentication value") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )

            OutlinedTextField(
                value = profile.authHeader,
                onValueChange = { value ->
                    viewModel.updateProfile { it.copy(authHeader = value) }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Authentication header name") },
                placeholder = { Text("Authorization") },
                singleLine = true
            )

            OutlinedTextField(
                value = profile.authPrefix,
                onValueChange = { value ->
                    viewModel.updateProfile { it.copy(authPrefix = value) }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Authentication value prefix") },
                placeholder = { Text("Bearer, none, or custom") },
                singleLine = true
            )

            ModelPicker(
                model = profile.model,
                models = state.availableModels,
                loading = state.modelsLoading,
                onModelChange = { value ->
                    viewModel.updateProfile { it.copy(model = value) }
                }
            )

            OutlinedTextField(
                value = profile.dialect,
                onValueChange = { value ->
                    viewModel.updateProfile { it.copy(dialect = value) }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Request format") },
                placeholder = { Text("OpenAI-compatible, Anthropic-compatible, Google-style") },
                singleLine = true,
                supportingText = { Text("Use one of the listed formats, or provide a request template below.") }
            )

            OutlinedTextField(
                value = profile.extraHeaders,
                onValueChange = { value ->
                    viewModel.updateProfile { it.copy(extraHeaders = value) }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Additional headers") },
                placeholder = { Text("Header-Name: value (one per line)") },
                minLines = 2,
                maxLines = 5
            )

            OutlinedTextField(
                value = profile.requestTemplate,
                onValueChange = { value ->
                    viewModel.updateProfile { it.copy(requestTemplate = value) }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Request JSON template (optional)") },
                placeholder = { Text("""{"model":{model},"prompt":{text}}""") },
                minLines = 3,
                maxLines = 8,
                supportingText = { Text("Placeholders: {model}, {text}, {system}, {messages}, {attachment}.") }
            )

            OutlinedTextField(
                value = profile.responsePath,
                onValueChange = { value ->
                    viewModel.updateProfile { it.copy(responsePath = value) }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Response text path") },
                placeholder = { Text("choices[0].message.content") },
                singleLine = true,
                supportingText = { Text("Supports dotted keys and array indexes, for example data[0].reply.text.") }
            )

            OutlinedTextField(
                value = profile.responseTemplate,
                onValueChange = { value ->
                    viewModel.updateProfile { it.copy(responseTemplate = value) }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Response template (optional)") },
                placeholder = { Text("{value}") },
                singleLine = true,
                supportingText = { Text("Use {value} for extracted text or {body} for the raw response.") }
            )

            if (state.modelsLoading) {
                Text(
                    "Loading models from the configured server…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (state.modelsError.isNotBlank()) {
                Text(
                    "Model discovery failed: ${state.modelsError}\nYou can still enter a model ID above.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            } else if (state.availableModels.isNotEmpty()) {
                Text(
                    "${state.availableModels.size} model(s) available from this server.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (profile.baseUrl.isNotBlank()) {
                Text(
                    "Model discovery may not be supported by this server. Enter a model ID manually if needed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Button(
                onClick = viewModel::testConnection,
                enabled = !state.testing,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (state.testing) "Testing connection…" else "Test connection")
            }

            if (state.testReport.isNotBlank()) {
                Text(
                    state.testReport,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.testReport.contains("successfully", true)) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ModelPicker(
    model: String,
    models: List<String>,
    loading: Boolean,
    onModelChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Column {
        OutlinedTextField(
            value = model,
            onValueChange = onModelChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Model") },
            placeholder = { Text("Select or enter a model ID") },
            singleLine = true,
            trailingIcon = {
                IconButton(
                    onClick = { expanded = true },
                    enabled = models.isNotEmpty() && !loading
                ) {
                    Icon(
                        Icons.Default.ArrowDropDown,
                        contentDescription = "Show available models"
                    )
                }
            }
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            models.forEach { id ->
                DropdownMenuItem(
                    text = { Text(id) },
                    onClick = {
                        onModelChange(id)
                        expanded = false
                    },
                    trailingIcon = {
                        if (id == model) {
                            Icon(Icons.Default.Check, contentDescription = "Selected model")
                        }
                    }
                )
            }
        }
    }
}
