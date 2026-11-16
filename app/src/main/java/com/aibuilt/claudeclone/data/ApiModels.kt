package com.aibuilt.claudeclone.data

data class ApiProfile(
    val name: String = "Custom API",
    val baseUrl: String = "",
    val endpointPath: String = "/v1/chat/completions",
    val model: String = "",
    val dialect: String = "OpenAI-compatible",
    val authHeader: String = "Authorization",
    val authPrefix: String = "Bearer",
    val authValue: String = "",
    val extraHeaders: String = "",
    val requestTemplate: String = "",
    val responsePath: String = "choices[0].message.content",
    val responseTemplate: String = "",
    val streaming: Boolean = false,
    val systemPrompt: String = "You are a helpful assistant."
)

data class ChatMessage(
    val id: Long = System.currentTimeMillis(),
    val text: String,
    val isUser: Boolean,
    val attachmentName: String? = null,
    val isError: Boolean = false
)

/**
 * Attachment contents are deliberately bounded. Files that cannot safely be inlined
 * retain their URI and metadata; this does not imply that the configured API accepts them.
 */
data class PendingAttachment(
    val name: String,
    val mimeType: String,
    val base64: String = "",
    val uri: String = "",
    val sizeBytes: Long? = null,
    val extractedText: String = "",
    val archiveManifest: String = ""
)

data class ApiResult(
    val text: String,
    val report: String
)
