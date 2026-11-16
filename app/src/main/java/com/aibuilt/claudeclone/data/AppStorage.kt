package com.aibuilt.claudeclone.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class AppStorage(context: Context) {
    private val prefs = context.getSharedPreferences("claude_clone_data", Context.MODE_PRIVATE)

    fun loadProfiles(): List<ApiProfile> {
        val raw = prefs.getString("profiles", null) ?: return listOf(ApiProfile())
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length())
                .map { index -> array.getJSONObject(index).toProfile() }
                .ifEmpty { listOf(ApiProfile()) }
        }.getOrDefault(listOf(ApiProfile()))
    }

    fun saveProfiles(profiles: List<ApiProfile>) {
        val array = JSONArray()
        profiles.forEach { profile ->
            array.put(
                JSONObject()
                    .put("name", profile.name)
                    .put("baseUrl", profile.baseUrl)
                    .put("endpointPath", profile.endpointPath)
                    .put("model", profile.model)
                    .put("dialect", profile.dialect)
                    .put("authHeader", profile.authHeader)
                    .put("authPrefix", profile.authPrefix)
                    .put("authValue", profile.authValue)
                    .put("extraHeaders", profile.extraHeaders)
                    .put("requestTemplate", profile.requestTemplate)
                    .put("responsePath", profile.responsePath)
                    .put("responseTemplate", profile.responseTemplate)
                    .put("streaming", profile.streaming)
                    .put("systemPrompt", profile.systemPrompt)
            )
        }
        prefs.edit().putString("profiles", array.toString()).apply()
    }

    fun loadActiveIndex(): Int = prefs.getInt("active_profile", 0)

    fun saveActiveIndex(index: Int) {
        prefs.edit().putInt("active_profile", index).apply()
    }

    fun loadMessages(): List<ChatMessage> {
        val raw = prefs.getString("messages", null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                ChatMessage(
                    id = item.optLong("id", System.currentTimeMillis() + index),
                    text = item.optString("text"),
                    isUser = item.optBoolean("user"),
                    attachmentName = item.optString("attachment").takeIf { it.isNotBlank() },
                    isError = item.optBoolean("error")
                )
            }
        }.getOrDefault(emptyList())
    }

    fun saveMessages(messages: List<ChatMessage>) {
        val array = JSONArray()
        messages.takeLast(150).forEach { message ->
            array.put(
                JSONObject()
                    .put("id", message.id)
                    .put("text", message.text)
                    .put("user", message.isUser)
                    .put("attachment", message.attachmentName ?: "")
                    .put("error", message.isError)
            )
        }
        prefs.edit().putString("messages", array.toString()).apply()
    }

    private fun JSONObject.toProfile() = ApiProfile(
        name = optString("name", "Custom API"),
        baseUrl = optString("baseUrl"),
        endpointPath = optString("endpointPath", "/v1/chat/completions"),
        model = optString("model"),
        dialect = optString("dialect", "OpenAI-compatible"),
        authHeader = optString("authHeader", "Authorization"),
        authPrefix = optString("authPrefix", "Bearer"),
        authValue = optString("authValue"),
        extraHeaders = optString("extraHeaders"),
        requestTemplate = optString("requestTemplate"),
        responsePath = optString("responsePath", "choices[0].message.content"),
        responseTemplate = optString("responseTemplate"),
        streaming = optBoolean("streaming"),
        systemPrompt = optString("systemPrompt", "You are a helpful assistant.")
    )
}
