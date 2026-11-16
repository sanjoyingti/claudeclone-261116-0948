package com.aibuilt.claudeclone.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

class ApiRepository {
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
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
        require(profile.model.isNotBlank()) {
            "Enter a model ID or select one returned by the server."
        }
        val dialect = detectDialect(profile)
        val url = chatUrl(profile, dialect)
        val body = makeBody(profile, dialect, text, attachment, history)
        val request = buildRequest(profile, dialect, url)
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header(
                "Accept",
                if (profile.streaming) "text/event-stream, application/json" else "application/json"
            )
            .build()

        return execute(request, profile) { responseBody ->
            val reply = parseReply(profile, dialect, responseBody)
            ApiResult(
                text = reply,
                report = "Connected successfully. HTTP response received from ${safeUrl(request.url)}."
            )
        }
    }

    fun discoverModels(profile: ApiProfile): List<String> {
        require(profile.baseUrl.isNotBlank()) { "Enter a base URL before discovering models." }
        val dialect = detectDialect(profile)
        val url = modelsUrl(profile, dialect)
        val request = buildRequest(profile, dialect, url)
            .get()
            .header("Accept", "application/json")
            .build()

        return execute(request, profile) { body ->
            val root = runCatching { JSONObject(body) }.getOrNull()
                ?: throw IllegalStateException(
                    "The model-list endpoint returned non-JSON data. Model discovery may not be supported by this server."
                )
            val models = extractModels(root)
            if (models.isEmpty()) {
                throw IllegalStateException(
                    "The server returned no model IDs. Model discovery may be unsupported; enter a model ID manually."
                )
            }
            models
        }
    }

    private fun detectDialect(profile: ApiProfile): String {
        val base = profile.baseUrl.trim().lowercase()
        val endpoint = profile.endpointPath.trim().lowercase()
        return when {
            profile.dialect.equals("Google-style", ignoreCase = true) ||
                base.contains("generativelanguage.googleapis.com") ||
                endpoint.contains("generatecontent") -> "Google-style"

            profile.dialect.equals("Anthropic-compatible", ignoreCase = true) ||
                base.contains("api.anthropic.com") ||
                endpoint.endsWith("/messages") -> "Anthropic-compatible"

            else -> "OpenAI-compatible"
        }
    }

    private fun chatUrl(profile: ApiProfile, dialect: String): HttpUrl {
        val endpoint = profile.endpointPath.trim().ifBlank {
            when (dialect) {
                "Anthropic-compatible" -> "/v1/messages"
                "Google-style" -> "/v1beta/models/{model}:generateContent"
                else -> "/v1/chat/completions"
            }
        }.replace("{model}", profile.model)

        return appendPath(normalizedBase(profile.baseUrl), endpoint)
    }

    private fun modelsUrl(profile: ApiProfile, dialect: String): HttpUrl {
        val base = normalizedBase(profile.baseUrl)
        val modelPath = if (dialect == "Google-style") "v1beta/models" else "v1/models"
        return appendPath(base, modelPath)
    }

    private fun normalizedBase(input: String): HttpUrl {
        val trimmed = input.trim().trimEnd('/')
        require(trimmed.isNotBlank()) { "Enter a base URL first." }
        require(
            trimmed.startsWith("https://", ignoreCase = true) ||
                trimmed.startsWith("http://", ignoreCase = true)
        ) { "Base URL must start with http:// or https://." }

        val url = trimmed.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("The base URL is invalid. Check its scheme, host, and port.")
        require(url.host.isNotBlank()) { "Base URL must include a server host." }
        return url
    }

    private fun appendPath(base: HttpUrl, requestedPath: String): HttpUrl {
        val path = requestedPath.trim()
        if (path.startsWith("https://", true) || path.startsWith("http://", true)) {
            return path.toHttpUrlOrNull()
                ?: throw IllegalArgumentException("The configured endpoint URL is invalid.")
        }

        val endpointPath = path.substringBefore('?').trim('/')
        val endpointParts = endpointPath.split('/').filter(String::isNotBlank)
        val baseParts = base.encodedPath.split('/').filter(String::isNotBlank)

        // Avoid duplicating a shared prefix, while retaining the complete user-provided base path.
        val overlap = (minOf(baseParts.size, endpointParts.size) downTo 1)
            .firstOrNull { count ->
                baseParts.takeLast(count).map(::decodePath) ==
                    endpointParts.take(count).map(::decodePath)
            } ?: 0

        val builder = base.newBuilder()
        endpointParts.drop(overlap).forEach { builder.addPathSegment(decodePath(it)) }

        val query = path.substringAfter('?', "").takeIf(String::isNotBlank)
        if (query != null) {
            query.split('&').filter(String::isNotBlank).forEach { pair ->
                builder.addQueryParameter(pair.substringBefore('='), pair.substringAfter('=', ""))
            }
        }
        return builder.build()
    }

    private fun buildRequest(profile: ApiProfile, dialect: String, url: HttpUrl): Request.Builder {
        val builder = Request.Builder().url(url)
        val key = profile.authValue.trim()
        if (key.isNotBlank()) {
            val configuredHeader = profile.authHeader.trim()
            val header = when {
                configuredHeader.isNotBlank() &&
                    !configuredHeader.equals("Authorization", ignoreCase = true) -> configuredHeader
                dialect == "Google-style" -> "x-goog-api-key"
                dialect == "Anthropic-compatible" -> "x-api-key"
                else -> configuredHeader.ifBlank { "Authorization" }
            }
            val prefix = when {
                configuredHeader.isNotBlank() &&
                    !configuredHeader.equals("Authorization", ignoreCase = true) -> profile.authPrefix.trim()
                dialect == "Google-style" || dialect == "Anthropic-compatible" -> ""
                else -> profile.authPrefix.trim()
            }
            val value = if (prefix.isBlank() || prefix.equals("none", true)) key else "$prefix $key"
            builder.header(header, value)
        }

        if (dialect == "Anthropic-compatible") {
            builder.header("anthropic-version", "2023-06-01")
        }

        profile.extraHeaders.lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .forEach { line ->
                val colon = line.indexOf(':')
                require(colon > 0) {
                    "An additional header is invalid. Use one 'Name: value' header per line."
                }
                val name = line.substring(0, colon).trim()
                val value = line.substring(colon + 1).trim()
                require(name.isNotBlank()) { "An additional header must have a name." }
                builder.header(name, value)
            }
        return builder
    }

    private inline fun <T> execute(
        request: Request,
        profile: ApiProfile,
        parse: (String) -> T
    ): T {
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val explanation = when (response.code) {
                        401, 403 -> "Authentication was rejected. Check the API key, header name, and authentication prefix."
                        402 -> "Payment is required by the API provider. Check billing, credits, and payment method."
                        404 -> "The endpoint was not found. Check the base URL and endpoint path."
                        400, 422 -> "The server rejected the request. Check the model ID and request format."
                        429 -> "The server is rate limiting requests. Wait and try again."
                        in 500..599 -> "The API server returned an error."
                        else -> "The API returned an HTTP error."
                    }
                    val detail = errorDetail(body)
                    throw ApiHttpException(
                        "$explanation HTTP ${response.code}" +
                            (detail?.let { ". Server message: ${redact(it, profile.authValue).take(400)}" } ?: "") +
                            "\nURL: ${safeUrl(request.url)}"
                    )
                }
                return parse(body)
            }
        } catch (e: ApiHttpException) {
            throw IllegalStateException(e.message, e)
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            val reason = when (e) {
                is UnknownHostException ->
                    "Could not resolve the server host. Check the base URL and network connection."
                is SSLException ->
                    "TLS connection failed. Check the server certificate and HTTPS configuration."
                is java.net.ConnectException ->
                    "Could not connect to the server. Check the host, port, and network access."
                is SocketTimeoutException ->
                    "The server timed out. Check that it is reachable and try again."
                else ->
                    "Request failed. Check the URL, network, model ID, headers, and API format."
            }
            val detail = redact(e.message ?: e.javaClass.simpleName, profile.authValue).take(500)
            throw IllegalStateException(
                "$reason\n${e.javaClass.simpleName}: $detail\nURL: ${safeUrl(request.url)}",
                e
            )
        }
    }

    private class ApiHttpException(message: String) : Exception(message)

    private fun makeBody(
        profile: ApiProfile,
        dialect: String,
        text: String,
        attachment: PendingAttachment?,
        history: List<ChatMessage>
    ): JSONObject {
        if (profile.requestTemplate.isNotBlank()) {
            val values = mapOf(
                "model" to JSONObject.quote(profile.model),
                "text" to JSONObject.quote(text),
                "system" to JSONObject.quote(profile.systemPrompt),
                "messages" to openAiMessages(profile.systemPrompt, text, attachment, history).toString(),
                "attachment" to attachmentJson(attachment).toString()
            )
            val template = replacePlaceholders(profile.requestTemplate, values)
            return runCatching { JSONObject(template) }.getOrElse {
                throw IllegalArgumentException("The saved request template is not a valid JSON object.")
            }
        }

        return when (dialect) {
            "Anthropic-compatible" -> anthropicBody(profile, text, attachment, history)
            "Google-style" -> googleBody(profile, text, attachment, history)
            else -> JSONObject()
                .put("model", profile.model)
                .put("messages", openAiMessages(profile.systemPrompt, text, attachment, history))
                .put("max_tokens", 2048)
                .apply { if (profile.streaming) put("stream", true) }
        }
    }

    private fun anthropicBody(
        profile: ApiProfile,
        text: String,
        attachment: PendingAttachment?,
        history: List<ChatMessage>
    ): JSONObject {
        val messages = JSONArray()
        history.filterNot { it.isError }.filter { it.text.isNotBlank() }.forEach {
            messages.put(
                JSONObject()
                    .put("role", if (it.isUser) "user" else "assistant")
                    .put("content", it.text)
            )
        }

        val parts = JSONArray()
        if (attachment != null) {
            require(attachment.mimeType.startsWith("image/") || attachment.mimeType == "application/pdf") {
                "This Anthropic-compatible request supports image or PDF attachments only. The selected server may have different upload capabilities."
            }
            val type = if (attachment.mimeType == "application/pdf") "document" else "image"
            parts.put(
                JSONObject()
                    .put("type", type)
                    .put(
                        "source",
                        JSONObject()
                            .put("type", "base64")
                            .put("media_type", attachment.mimeType)
                            .put("data", attachment.base64)
                    )
            )
        }
        parts.put(JSONObject().put("type", "text").put("text", text))
        messages.put(JSONObject().put("role", "user").put("content", parts))

        return JSONObject()
            .put("model", profile.model)
            .put("max_tokens", 2048)
            .put("system", profile.systemPrompt)
            .put("messages", messages)
            .apply { if (profile.streaming) put("stream", true) }
    }

    private fun googleBody(
        profile: ApiProfile,
        text: String,
        attachment: PendingAttachment?,
        history: List<ChatMessage>
    ): JSONObject {
        val contents = JSONArray()
        history.filterNot { it.isError }.filter { it.text.isNotBlank() }.forEach {
            contents.put(
                JSONObject()
                    .put("role", if (it.isUser) "user" else "model")
                    .put("parts", JSONArray().put(JSONObject().put("text", it.text)))
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

    private fun openAiMessages(
        system: String,
        text: String,
        attachment: PendingAttachment?,
        history: List<ChatMessage>
    ): JSONArray {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        history.filterNot { it.isError }.filter { it.text.isNotBlank() }.forEach {
            messages.put(
                JSONObject()
                    .put("role", if (it.isUser) "user" else "assistant")
                    .put("content", it.text)
            )
        }

        val content: Any = if (attachment == null) {
            text
        } else if (attachment.mimeType.startsWith("image/")) {
            JSONArray()
                .put(JSONObject().put("type", "text").put("text", text))
                .put(
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
            throw IllegalArgumentException(
                "This OpenAI-compatible request implementation can send images only. The configured server may support other file types through a different upload protocol."
            )
        }
        messages.put(JSONObject().put("role", "user").put("content", content))
        return messages
    }

    private fun attachmentJson(attachment: PendingAttachment?): JSONObject =
        if (attachment == null) {
            JSONObject().put("value", JSONObject.NULL)
        } else {
            JSONObject()
                .put("name", attachment.name)
                .put("mime_type", attachment.mimeType)
                .put("base64", attachment.base64)
        }

    private fun extractModels(root: JSONObject): List<String> {
        val values = root.optJSONArray("data")
            ?: root.optJSONArray("models")
            ?: root.optJSONObject("result")?.optJSONArray("models")
            ?: return emptyList()

        return (0 until values.length())
            .mapNotNull { index ->
                when (val item = values.opt(index)) {
                    is JSONObject -> item.optString("id").takeIf(String::isNotBlank)
                        ?: item.optString("name").takeIf(String::isNotBlank)
                    is String -> item.takeIf(String::isNotBlank)
                    else -> null
                }
            }
            .map { it.removePrefix("models/") }
            .distinct()
            .sorted()
    }

    private fun parseReply(profile: ApiProfile, dialect: String, body: String): String {
        if (profile.responseTemplate.isNotBlank()) {
            val value = extractReply(profile, dialect, body)
            return replacePlaceholders(
                profile.responseTemplate,
                mapOf("body" to body, "value" to value)
            ).ifBlank {
                throw IllegalStateException("The configured response template produced an empty reply.")
            }
        }
        return extractReply(profile, dialect, body)
    }

    private fun extractReply(profile: ApiProfile, dialect: String, body: String): String {
        if (body.isBlank()) {
            throw IllegalStateException("The API returned an empty response body.")
        }

        if (profile.streaming && body.lineSequence().any { it.trimStart().startsWith("data:") }) {
            val stream = StringBuilder()
            body.lineSequence()
                .map(String::trim)
                .filter { it.startsWith("data:") }
                .map { it.removePrefix("data:").trim() }
                .filter { it.isNotEmpty() && it != "[DONE]" }
                .forEach { chunk ->
                    val json = runCatching { JSONObject(chunk) }.getOrNull() ?: return@forEach
                    val paths = listOf(
                        "choices[0].delta.content",
                        "choices[0].message.content",
                        "content_block_delta.delta.text",
                        "delta.text",
                        "candidates[0].content.parts[0].text"
                    )
                    paths.firstNotNullOfOrNull { readPath(json, it).asText() }?.let(stream::append)
                }
            if (stream.isNotEmpty()) return stream.toString()
        }

        val root = runCatching { JSONObject(body) }.getOrNull()
        if (root == null) {
            val arrayRoot = runCatching { JSONArray(body) }.getOrNull()
            if (arrayRoot != null) {
                val custom = profile.responsePath.trim()
                val value = if (custom.isNotBlank()) readPath(arrayRoot, custom).asText() else null
                return value ?: arrayRoot.toString()
            }
            return body
        }

        val defaultPath = when (dialect) {
            "Anthropic-compatible" -> "content[0].text"
            "Google-style" -> "candidates[0].content.parts[0].text"
            else -> "choices[0].message.content"
        }
        val configuredPath = profile.responsePath.trim().ifBlank { defaultPath }
        val candidatePaths = listOf(
            configuredPath,
            "choices[0].message.content",
            "choices[0].text",
            "content[0].text",
            "candidates[0].content.parts[0].text",
            "output_text",
            "response"
        ).distinct()

        candidatePaths.firstNotNullOfOrNull { path ->
            runCatching { readPath(root, path).asText() }.getOrNull()?.takeIf(String::isNotBlank)
        }?.let { return it }

        val apiError = errorDetail(body)
        if (apiError != null) {
            throw IllegalStateException(
                "The server returned JSON but no assistant text could be found. Server message: ${apiError.take(400)}. Check the response path setting."
            )
        }
        throw IllegalStateException(
            "The API response was valid JSON, but no reply text was found. Check the response path setting. Response preview: ${body.take(400)}"
        )
    }

    /**
     * Reads paths such as choices[0].message.content without regex parsing.
     * Supports dotted object keys, numeric array indexes, and quoted bracket keys.
     */
    private fun readPath(root: Any, path: String): Any? {
        val normalized = path.trim().removePrefix("$.")
        if (normalized.isEmpty()) return root

        val tokens = mutableListOf<String>()
        val token = StringBuilder()
        var cursor = 0

        while (cursor < normalized.length) {
            when (val char = normalized[cursor]) {
                '.' -> {
                    if (token.isNotEmpty()) {
                        tokens += token.toString()
                        token.setLength(0)
                    }
                    cursor++
                }
                '[' -> {
                    if (token.isNotEmpty()) {
                        tokens += token.toString()
                        token.setLength(0)
                    }
                    val close = normalized.indexOf(']', cursor + 1)
                    if (close < 0) return null
                    val bracketValue = normalized.substring(cursor + 1, close).trim()
                    val unquoted = bracketValue.removeSurrounding("\"").removeSurrounding("'")
                    if (unquoted.isEmpty()) return null
                    tokens += unquoted
                    cursor = close + 1
                }
                else -> {
                    token.append(char)
                    cursor++
                }
            }
        }
        if (token.isNotEmpty()) tokens += token.toString()

        var current: Any? = root
        for (part in tokens) {
            current = when (val value = current) {
                is JSONObject -> value.opt(part).takeUnless { it == JSONObject.NULL }
                is JSONArray -> {
                    val index = part.toIntOrNull() ?: return null
                    if (index !in 0 until value.length()) return null
                    value.opt(index).takeUnless { it == JSONObject.NULL }
                }
                else -> return null
            }
            if (current == null) return null
        }
        return current
    }

    private fun Any?.asText(): String? = when (this) {
        null, JSONObject.NULL -> null
        is String -> this
        is JSONArray, is JSONObject -> toString()
        else -> toString()
    }

    private fun errorDetail(body: String): String? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        return when (val error = json.opt("error")) {
            is JSONObject -> error.optString("message").takeIf(String::isNotBlank)
                ?: error.optString("detail").takeIf(String::isNotBlank)
            is String -> error.takeIf(String::isNotBlank)
            else -> json.optString("message").takeIf(String::isNotBlank)
                ?: json.optString("detail").takeIf(String::isNotBlank)
        }
    }

    private fun safeUrl(url: HttpUrl): String =
        url.newBuilder().query(null).build().toString().trimEnd('/')

    private fun redact(value: String, secret: String): String {
        if (secret.isBlank()) return value
        val encoded = URLEncoder.encode(secret, "UTF-8")
        return value.replace(secret, "[redacted]").replace(encoded, "[redacted]")
    }

    private fun replacePlaceholders(template: String, values: Map<String, String>): String =
        Regex("""\{([A-Za-z][A-Za-z0-9_]*)}""").replace(template) { match ->
            values[match.groupValues[1]] ?: match.value
        }

    private fun decodePath(value: String): String =
        runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
}
