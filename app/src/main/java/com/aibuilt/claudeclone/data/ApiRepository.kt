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
import java.net.UnknownHostException
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
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
        require(profile.model.isNotBlank()) {
            "Enter a model ID or select one returned by the server."
        }
        val dialect = detectDialect(profile)
        val url = chatUrl(profile, dialect)
        val body = makeBody(profile, dialect, text, attachment, history)
        val request = buildRequest(profile, dialect, url)
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Accept", if (profile.streaming) "text/event-stream, application/json" else "application/json")
            .build()

        return execute(request, profile) { responseBody ->
            val reply = parseReply(profile, dialect, responseBody)
            ApiResult(
                text = reply.ifBlank { "The API returned an empty response." },
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
                    "The model-list endpoint returned non-JSON data. This server may not support model discovery."
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
            base.contains("generativelanguage.googleapis.com") ||
                base.contains("googleapis.com") ||
                endpoint.contains("generatecontent") ||
                profile.dialect.equals("Google-style", ignoreCase = true) -> "Google-style"

            base.contains("api.anthropic.com") ||
                endpoint.endsWith("/messages") ||
                profile.dialect.equals("Anthropic-compatible", ignoreCase = true) -> "Anthropic-compatible"

            else -> "OpenAI-compatible"
        }
    }

    private fun chatUrl(profile: ApiProfile, dialect: String): HttpUrl {
        val base = normalizedBase(profile.baseUrl)
        val defaultEndpoint = when (dialect) {
            "Anthropic-compatible" -> "v1/messages"
            "Google-style" -> "v1beta/models/${profile.model}:generateContent"
            else -> "v1/chat/completions"
        }
        val configured = profile.endpointPath.trim()
        val endpoint = when {
            dialect == "Google-style" && configured.contains("generateContent", ignoreCase = true) ->
                configured.replace("{model}", profile.model)
            dialect == "Anthropic-compatible" && configured.endsWith("/messages", ignoreCase = true) ->
                configured
            dialect == "OpenAI-compatible" &&
                configured.endsWith("/chat/completions", ignoreCase = true) -> configured
            else -> defaultEndpoint
        }
        return appendPath(base, endpoint)
    }

    private fun modelsUrl(profile: ApiProfile, dialect: String): HttpUrl {
        val base = normalizedBase(profile.baseUrl)
        val modelPath = if (dialect == "Google-style") "v1beta/models" else "v1/models"
        return appendPath(base, modelPath)
    }

    private fun normalizedBase(input: String): HttpUrl {
        val trimmed = input.trim()
        require(trimmed.isNotBlank()) { "Enter a base URL first." }
        require(
            trimmed.startsWith("https://", ignoreCase = true) ||
                trimmed.startsWith("http://", ignoreCase = true)
        ) { "Base URL must start with http:// or https://." }

        var base = trimmed.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("The base URL is invalid. Check its scheme, host, and port.")
        require(base.host.isNotBlank()) { "Base URL must include a server host." }

        val path = base.encodedPath.trimEnd('/')
        val generationEndpoint = Regex(
            """/(?:v1beta/)?models/[^/]+:generatecontent$""",
            RegexOption.IGNORE_CASE
        )
        val removable = listOf(
            "/v1/chat/completions",
            "/chat/completions",
            "/v1/messages",
            "/messages",
            "/v1beta/models",
            "/v1/models",
            "/models"
        ).firstOrNull { path.endsWith(it, ignoreCase = true) }
            ?: generationEndpoint.find(path)?.value

        if (removable != null) {
            base = base.newBuilder()
                .encodedPath(path.dropLast(removable.length).ifBlank { "/" })
                .build()
        }
        return base
    }

    private fun appendPath(base: HttpUrl, requestedPath: String): HttpUrl {
        val absolute = requestedPath.trim()
        if (absolute.startsWith("https://", true) || absolute.startsWith("http://", true)) {
            return absolute.toHttpUrlOrNull()
                ?: throw IllegalArgumentException("The configured endpoint URL is invalid.")
        }

        val path = absolute.substringBefore('?').trim('/')
        val pathParts = path.split('/').filter(String::isNotBlank)
        val baseParts = base.encodedPath.split('/').filter(String::isNotBlank)
        val overlap = (minOf(baseParts.size, pathParts.size) downTo 1)
            .firstOrNull { count ->
                baseParts.takeLast(count).map(::decodePath) ==
                    pathParts.take(count).map(::decodePath)
            } ?: 0
        val remaining = pathParts.drop(overlap)

        val builder = base.newBuilder()
        remaining.forEach { part -> builder.addPathSegment(decodePath(part)) }

        val pathQuery = absolute.substringAfter('?', "").takeIf(String::isNotBlank)
        if (pathQuery != null) {
            pathQuery.split('&').forEach { pair ->
                val key = pair.substringBefore('=')
                val value = pair.substringAfter('=', "")
                builder.addQueryParameter(key, value)
            }
        }
        return builder.build()
    }

    private fun buildRequest(profile: ApiProfile, dialect: String, url: HttpUrl): Request.Builder {
        val builder = Request.Builder().url(url)
        val key = profile.authValue.trim()
        if (key.isNotBlank()) {
            when (dialect) {
                "Google-style" -> builder.header("x-goog-api-key", key)
                "Anthropic-compatible" -> {
                    builder.header("x-api-key", key)
                    builder.header("anthropic-version", "2023-06-01")
                }
                else -> {
                    val prefix = profile.authPrefix.trim()
                    val value = if (prefix.isBlank() || prefix.equals("none", true)) {
                        key
                    } else {
                        "$prefix $key"
                    }
                    builder.header(profile.authHeader.ifBlank { "Authorization" }, value)
                }
            }
        } else if (dialect == "Anthropic-compatible") {
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
                builder.header(line.substring(0, colon).trim(), line.substring(colon + 1).trim())
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
                        401, 403 -> "Authentication was rejected. Check the API key and authentication format."
                        402 -> "Payment is required by the API provider. Check the provider's billing page for a required top-up, credits, or an active payment method."
                        404 -> "The endpoint was not found. Check the base URL and API format."
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
                    "TLS connection failed. Check that the server supports HTTPS and can complete a TLS handshake."
                is java.net.ConnectException ->
                    "Could not connect to the server. Check the host, port, and network access."
                is SocketTimeoutException ->
                    "The server timed out. Check that it is reachable and try again."
                else ->
                    "Request failed. Check the URL, network, model ID, and API format."
            }
            val detail = redact(e.message ?: e.javaClass.simpleName, profile.authValue).take(500)
            throw IllegalStateException("$reason\n${e.javaClass.simpleName}: $detail", e)
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
                "This API format supports image or PDF attachments only."
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
                "This OpenAI-compatible chat endpoint cannot reliably accept PDF attachments. Use an image or a server that supports PDF input."
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
            )
        }
        return extractReply(profile, dialect, body)
    }

    private fun extractReply(profile: ApiProfile, dialect: String, body: String): String {
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

        val root = runCatching { JSONObject(body) }.getOrNull() ?: return body
        val configuredPath = profile.responsePath.trim().ifBlank {
            when (dialect) {
                "Anthropic-compatible" -> "content[0].text"
                "Google-style" -> "candidates[0].content.parts[0].text"
                else -> "choices[0].message.content"
            }
        }
        val paths = listOf(
            configuredPath,
            "choices[0].message.content",
            "choices[0].text",
            "content[0].text",
            "candidates[0].content.parts[0].text",
            "output_text",
            "response"
        ).distinct()

        return paths.firstNotNullOfOrNull { readPath(root, it).asText() } ?: body
    }

    private fun readPath(root: Any, path: String): Any? {
        val normalizedPath = path.trim().removePrefix("$.")
        if (normalizedPath.isEmpty()) return root

        var current: Any? = root
        var cursor = 0

        while (cursor < normalizedPath.length) {
            if (normalizedPath[cursor] == '.') {
                cursor++
                continue
            }

            val token: String
            if (normalizedPath[cursor] == '[') {
                val closingBracket = normalizedPath.indexOf(']', startIndex = cursor + 1)
                if (closingBracket < 0) return null
                token = normalizedPath.substring(cursor + 1, closingBracket)
                    .trim()
                    .removeSurrounding("\"")
                    .removeSurrounding("'")
                cursor = closingBracket + 1
            } else {
                val start = cursor
                while (
                    cursor < normalizedPath.length &&
                    normalizedPath[cursor] != '.' &&
                    normalizedPath[cursor] != '['
                ) {
                    cursor++
                }
                token = normalizedPath.substring(start, cursor)
            }

            if (token.isEmpty()) return null
            current = when (val value = current) {
                is JSONObject -> value.opt(token).takeUnless { it == JSONObject.NULL }
                is JSONArray -> value.opt(token.toIntOrNull() ?: return null)
                    .takeUnless { it == JSONObject.NULL }
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
