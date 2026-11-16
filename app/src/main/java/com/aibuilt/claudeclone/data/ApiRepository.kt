package com.aibuilt.claudeclone.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class ApiRepository {
    private val client: OkHttpClient by lazy {
        val trustManager = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        }
        val ssl = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        }
        OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(150, TimeUnit.SECONDS)
            .build()
    }

    fun send(
        profile: ApiProfile,
        text: String,
        attachment: PendingAttachment? = null,
        history: List<ChatMessage> = emptyList()
    ): ApiResult {
        require(profile.baseUrl.isNotBlank()) { "Enter a base URL first." }
        require(profile.model.isNotBlank()) { "Enter a model name first." }

        val url = resolveUrl(profile)
        val jsonBody = makeBody(profile, text, attachment, history)
        val requestBuilder = Request.Builder()
            .url(url)
            .post(jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header(
                "Accept",
                if (profile.streaming) "text/event-stream, application/json" else "application/json"
            )

        val authHeader = profile.authHeader.trim()
        val authValue = profile.authValue.trim()
        if (authHeader.isNotEmpty() && authValue.isNotEmpty()) {
            val prefix = profile.authPrefix.trim()
            val completeValue = if (prefix.isBlank() || prefix.equals("none", ignoreCase = true)) {
                authValue
            } else {
                "$prefix $authValue"
            }

            if (profile.dialect.equals("Google-style", ignoreCase = true) &&
                authHeader.equals("x-goog-api-key", ignoreCase = true)
            ) {
                val separator = if (url.contains("?")) "&" else "?"
                requestBuilder.url("$url${separator}key=${encode(authValue)}")
            } else {
                requestBuilder.header(authHeader, completeValue)
            }
        }

        profile.extraHeaders.lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .forEach { line ->
                val colon = line.indexOf(':')
                require(colon > 0) {
                    "Invalid additional header. Use one header per line in the form Name: value."
                }
                val name = line.substring(0, colon).trim()
                val value = line.substring(colon + 1).trim()
                require(name.isNotEmpty()) { "Additional header name cannot be blank." }
                requestBuilder.header(name, value)
            }

        val request = try {
            requestBuilder.build()
        } catch (e: Exception) {
            throw IllegalArgumentException(
                "Could not build request for $url: ${e.message ?: e.javaClass.simpleName}",
                e
            )
        }

        try {
            client.newCall(request).execute().use { response ->
                val responseText = response.body?.string().orEmpty()
                val report = buildString {
                    append("URL: ")
                    append(request.url)
                    append('\n')
                    append("HTTP ")
                    append(response.code)
                    if (response.message.isNotBlank()) {
                        append(' ')
                        append(response.message)
                    }
                    append("\nBody: ")
                    append(responseText.take(200).ifBlank { "(empty response body)" })
                }

                if (!response.isSuccessful) {
                    throw ApiHttpException(report)
                }

                val reply = parseReply(profile, responseText)
                return ApiResult(
                    text = reply.ifBlank { responseText.ifBlank { "The API returned an empty response." } },
                    report = report
                )
            }
        } catch (e: ApiHttpException) {
            throw IllegalStateException(e.message ?: "The API returned an HTTP error.", e)
        } catch (e: Exception) {
            val detail = e.message?.take(700).orEmpty()
            throw IllegalStateException(
                buildString {
                    append("Request failed: ")
                    append(e.javaClass.simpleName)
                    if (detail.isNotBlank()) {
                        append(": ")
                        append(detail)
                    }
                    append("\nURL: ")
                    append(url)
                },
                e
            )
        }
    }

    fun resolveUrl(profile: ApiProfile): String {
        val base = profile.baseUrl.trim().trimEnd('/')
        require(base.startsWith("http://", ignoreCase = true) ||
            base.startsWith("https://", ignoreCase = true)
        ) {
            "Base URL must start with http:// or https://."
        }

        val configuredPath = profile.endpointPath.trim()
            .replace("{base}", base)
            .replace("{model}", encodePath(profile.model))

        return when {
            configuredPath.startsWith("http://", ignoreCase = true) ||
                configuredPath.startsWith("https://", ignoreCase = true) -> configuredPath
            configuredPath.isBlank() -> base
            else -> "$base/${configuredPath.trimStart('/')}"
        }
    }

    private fun makeBody(
        profile: ApiProfile,
        text: String,
        attachment: PendingAttachment?,
        history: List<ChatMessage>
    ): JSONObject {
        if (profile.requestTemplate.isNotBlank()) {
            val values = mapOf(
                "model" to JSONObject.quote(profile.model),
                "text" to JSONObject.quote(text),
                "system" to JSONObject.quote(profile.systemPrompt),
                "messages" to makeOpenAiMessages(
                    profile.systemPrompt,
                    text,
                    attachment,
                    history
                ).toString(),
                "attachment" to attachmentJson(attachment).toString()
            )
            val raw = replacePlaceholders(profile.requestTemplate, values)
            return try {
                JSONObject(raw)
            } catch (e: Exception) {
                throw IllegalArgumentException(
                    "Request template must be a valid JSON object: ${e.message ?: "invalid JSON"}",
                    e
                )
            }
        }

        return when (profile.dialect.lowercase()) {
            "anthropic-compatible" -> makeAnthropicBody(profile, text, attachment, history)
            "google-style" -> makeGoogleBody(profile, text, attachment, history)
            else -> JSONObject()
                .put("model", profile.model)
                .put("messages", makeOpenAiMessages(profile.systemPrompt, text, attachment, history))
                .put("max_tokens", 2048)
                .apply { if (profile.streaming) put("stream", true) }
        }
    }

    private fun makeAnthropicBody(
        profile: ApiProfile,
        text: String,
        attachment: PendingAttachment?,
        history: List<ChatMessage>
    ): JSONObject {
        val messages = JSONArray()
        history
            .filterNot { it.isError }
            .filter { it.text.isNotBlank() }
            .forEach { message ->
                messages.put(
                    JSONObject()
                        .put("role", if (message.isUser) "user" else "assistant")
                        .put("content", message.text)
                )
            }

        val content = JSONArray()
        if (attachment != null) {
            if (attachment.mimeType == "application/pdf") {
                content.put(
                    JSONObject()
                        .put("type", "document")
                        .put(
                            "source",
                            JSONObject()
                                .put("type", "base64")
                                .put("media_type", "application/pdf")
                                .put("data", attachment.base64)
                        )
                )
            } else {
                content.put(
                    JSONObject()
                        .put("type", "image")
                        .put(
                            "source",
                            JSONObject()
                                .put("type", "base64")
                                .put("media_type", attachment.mimeType)
                                .put("data", attachment.base64)
                        )
                )
            }
        }
        content.put(JSONObject().put("type", "text").put("text", text))
        messages.put(JSONObject().put("role", "user").put("content", content))

        return JSONObject()
            .put("model", profile.model)
            .put("max_tokens", 2048)
            .put("system", profile.systemPrompt)
            .put("messages", messages)
            .apply { if (profile.streaming) put("stream", true) }
    }

    private fun makeGoogleBody(
        profile: ApiProfile,
        text: String,
        attachment: PendingAttachment?,
        history: List<ChatMessage>
    ): JSONObject {
        val contents = JSONArray()
        history
            .filterNot { it.isError }
            .filter { it.text.isNotBlank() }
            .forEach { message ->
                contents.put(
                    JSONObject()
                        .put("role", if (message.isUser) "user" else "model")
                        .put(
                            "parts",
                            JSONArray().put(JSONObject().put("text", message.text))
                        )
                )
            }

        val parts = JSONArray().put(JSONObject().put("text", text))
        if (attachment != null) {
            parts.put(
                JSONObject().put(
                    "inlineData",
                    JSONObject()
                        .put("mimeType", attachment.mimeType)
                        .put("data", attachment.base64)
                )
            )
        }
        contents.put(JSONObject().put("role", "user").put("parts", parts))

        return JSONObject()
            .put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", profile.systemPrompt))
                )
            )
            .put("contents", contents)
            .put("generationConfig", JSONObject().put("maxOutputTokens", 2048))
            .apply { if (profile.streaming) put("stream", true) }
    }

    private fun makeOpenAiMessages(
        system: String,
        text: String,
        attachment: PendingAttachment?,
        history: List<ChatMessage>
    ): JSONArray {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        history
            .filterNot { it.isError }
            .filter { it.text.isNotBlank() }
            .forEach { message ->
                messages.put(
                    JSONObject()
                        .put("role", if (message.isUser) "user" else "assistant")
                        .put("content", message.text)
                )
            }

        val userContent: Any = if (attachment == null) {
            text
        } else {
            val parts = JSONArray()
                .put(JSONObject().put("type", "text").put("text", text))
            if (attachment.mimeType.startsWith("image/")) {
                parts.put(
                    JSONObject()
                        .put("type", "image_url")
                        .put(
                            "image_url",
                            JSONObject().put(
                                "url",
                                "data:${attachment.mimeType};base64,${attachment.base64}"
                            )
                        )
                )
            } else {
                parts.put(
                    JSONObject()
                        .put(
                            "text",
                            "Attached file: ${attachment.name} (${attachment.mimeType})."
                        )
                )
            }
            parts
        }
        messages.put(JSONObject().put("role", "user").put("content", userContent))
        return messages
    }

    private fun attachmentJson(attachment: PendingAttachment?): JSONObject {
        return if (attachment == null) {
            JSONObject().put("value", JSONObject.NULL)
        } else {
            JSONObject()
                .put("name", attachment.name)
                .put("mime_type", attachment.mimeType)
                .put("base64", attachment.base64)
        }
    }

    private fun parseReply(profile: ApiProfile, body: String): String {
        val configuredPath = profile.responsePath.trim().ifBlank {
            when (profile.dialect.lowercase()) {
                "anthropic-compatible" -> "content[0].text"
                "google-style" -> "candidates[0].content.parts[0].text"
                else -> "choices[0].message.content"
            }
        }

        val streamText = if (profile.streaming) parseEventStream(profile, body, configuredPath) else null
        val root = runCatching { JSONObject(body) }.getOrNull()
        val extracted = root?.let { readPath(it, configuredPath) }
        val extractedText = extracted.asReplyText()

        if (profile.responseTemplate.isNotBlank()) {
            val values = mapOf(
                "body" to body,
                "value" to (extractedText ?: body)
            )
            return replacePlaceholders(profile.responseTemplate, values)
        }

        if (!streamText.isNullOrEmpty()) return streamText
        return extractedText ?: body
    }

    private fun parseEventStream(
        profile: ApiProfile,
        body: String,
        configuredPath: String
    ): String? {
        if (body.lineSequence().none { it.trimStart().startsWith("data:") }) return null

        val collected = StringBuilder()
        body.lineSequence()
            .map(String::trim)
            .filter { it.startsWith("data:") }
            .map { it.removePrefix("data:").trim() }
            .filter { it.isNotEmpty() && it != "[DONE]" }
            .forEach { chunk ->
                val json = runCatching { JSONObject(chunk) }.getOrNull() ?: return@forEach
                val paths = listOf(
                    configuredPath,
                    "choices[0].delta.content",
                    "choices[0].message.content",
                    "content_block_delta.delta.text",
                    "delta.text",
                    "candidates[0].content.parts[0].text"
                ).distinct()
                val value = paths.firstNotNullOfOrNull { path ->
                    readPath(json, path).asReplyText()
                }
                if (value != null) collected.append(value)
            }
        return collected.toString().takeIf { it.isNotEmpty() }
    }

    private fun Any?.asReplyText(): String? = when (this) {
        null, JSONObject.NULL -> null
        is String -> this
        is JSONArray, is JSONObject -> toString()
        else -> toString()
    }

    private fun readPath(root: Any, path: String): Any? {
        var current: Any? = root
        val tokens = Regex("""([^.[\]]+)|\[(\d+)]""").findAll(path.removePrefix("$."))
        for (token in tokens) {
            current = when {
                token.groupValues[2].isNotEmpty() -> {
                    val index = token.groupValues[2].toIntOrNull() ?: return null
                    (current as? JSONArray)?.opt(index)
                }
                current is JSONObject -> current.opt(token.groupValues[1])
                    .takeUnless { it == JSONObject.NULL }
                else -> null
            }
            if (current == null) return null
        }
        return current
    }

    private fun replacePlaceholders(template: String, values: Map<String, String>): String {
        val placeholderPattern = Regex("""\{([A-Za-z][A-Za-z0-9_]*)}""")
        return placeholderPattern.replace(template) { match ->
            values[match.groupValues[1]] ?: match.value
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun encodePath(value: String): String = encode(value).replace("+", "%20")

    private class ApiHttpException(message: String) : Exception(message)
}
