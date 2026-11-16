package com.aibuilt.claudeclone.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aibuilt.claudeclone.data.ApiProfile
import com.aibuilt.claudeclone.data.ApiRepository
import com.aibuilt.claudeclone.data.AppStorage
import com.aibuilt.claudeclone.data.ChatMessage
import com.aibuilt.claudeclone.data.PendingAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppState(
    val profiles: List<ApiProfile>,
    val activeProfileIndex: Int,
    val messages: List<ChatMessage>,
    val sending: Boolean = false,
    val testing: Boolean = false,
    val testReport: String = "",
    val attachment: PendingAttachment? = null,
    val availableModels: List<String> = emptyList(),
    val modelsLoading: Boolean = false,
    val modelsError: String = ""
) {
    val activeProfile: ApiProfile
        get() = profiles.getOrElse(activeProfileIndex) { ApiProfile() }
}

class MainViewModel(
    private val storage: AppStorage,
    private val api: ApiRepository = ApiRepository()
) : ViewModel() {
    private val initialProfiles = storage.loadProfiles()
    private val mutableState = MutableStateFlow(
        AppState(
            profiles = initialProfiles,
            activeProfileIndex = storage.loadActiveIndex().coerceIn(initialProfiles.indices),
            messages = storage.loadMessages()
        )
    )
    val state: StateFlow<AppState> = mutableState.asStateFlow()

    fun updateProfile(transform: (ApiProfile) -> ApiProfile) {
        val current = mutableState.value
        val profiles = current.profiles.toMutableList()
        profiles[current.activeProfileIndex] = transform(current.activeProfile)
        save(profiles = profiles)
    }

    fun selectProfile(index: Int) {
        val current = mutableState.value
        if (index !in current.profiles.indices) return
        storage.saveActiveIndex(index)
        mutableState.value = current.copy(
            activeProfileIndex = index,
            testReport = "",
            availableModels = emptyList(),
            modelsError = ""
        )
    }

    fun addProfile() {
        val current = mutableState.value
        val profiles = current.profiles + ApiProfile(
            name = "API ${current.profiles.size + 1}",
            endpointPath = "/v1/chat/completions"
        )
        storage.saveActiveIndex(profiles.lastIndex)
        save(profiles = profiles, index = profiles.lastIndex)
    }

    fun duplicateProfile() {
        val current = mutableState.value
        val profiles = current.profiles + current.activeProfile.copy(
            name = "${current.activeProfile.name} copy"
        )
        storage.saveActiveIndex(profiles.lastIndex)
        save(profiles = profiles, index = profiles.lastIndex)
    }

    fun deleteProfile() {
        val current = mutableState.value
        if (current.profiles.size <= 1) {
            updateProfile { ApiProfile() }
            return
        }
        val profiles = current.profiles.toMutableList().apply {
            removeAt(current.activeProfileIndex)
        }
        val index = current.activeProfileIndex.coerceAtMost(profiles.lastIndex)
        storage.saveActiveIndex(index)
        save(profiles = profiles, index = index)
    }

    private fun save(
        profiles: List<ApiProfile> = mutableState.value.profiles,
        index: Int = mutableState.value.activeProfileIndex
    ) {
        storage.saveProfiles(profiles)
        mutableState.value = mutableState.value.copy(
            profiles = profiles,
            activeProfileIndex = index.coerceIn(profiles.indices)
        )
    }

    fun setAttachment(attachment: PendingAttachment?) {
        mutableState.value = mutableState.value.copy(attachment = attachment)
    }

    fun clearChat() {
        storage.saveMessages(emptyList())
        mutableState.value = mutableState.value.copy(messages = emptyList())
    }

    fun discoverModels() {
        val current = mutableState.value
        if (current.modelsLoading || current.activeProfile.baseUrl.isBlank()) return

        val requestedProfile = current.activeProfile
        mutableState.value = current.copy(modelsLoading = true, modelsError = "")

        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    api.discoverModels(requestedProfile)
                }
            }
            val latest = mutableState.value
            if (latest.activeProfile.baseUrl.trim() != requestedProfile.baseUrl.trim() ||
                latest.activeProfile.authValue != requestedProfile.authValue
            ) {
                mutableState.value = latest.copy(modelsLoading = false)
                return@launch
            }

            result.onSuccess { models ->
                mutableState.value = mutableState.value.copy(
                    modelsLoading = false,
                    availableModels = models,
                    modelsError = ""
                )
                if (requestedProfile.model.isBlank() && models.isNotEmpty()) {
                    updateProfile { profile ->
                        if (profile.model.isBlank()) profile.copy(model = models.first()) else profile
                    }
                }
            }.onFailure { error ->
                mutableState.value = mutableState.value.copy(
                    modelsLoading = false,
                    modelsError = error.message ?: "Model discovery failed."
                )
            }
        }
    }

    fun sendMessage(text: String) {
        val current = mutableState.value
        val cleanText = text.trim()
        if (current.sending || (cleanText.isEmpty() && current.attachment == null)) return

        val userMessage = ChatMessage(
            text = cleanText,
            isUser = true,
            attachmentName = current.attachment?.name
        )
        val messages = current.messages + userMessage
        storage.saveMessages(messages)
        mutableState.value = current.copy(messages = messages, sending = true, testReport = "")

        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    api.send(
                        profile = current.activeProfile,
                        text = cleanText,
                        attachment = current.attachment,
                        history = current.messages
                    )
                }
                val updated = mutableState.value.messages +
                    ChatMessage(text = result.text, isUser = false)
                storage.saveMessages(updated)
                mutableState.value = mutableState.value.copy(
                    messages = updated,
                    sending = false,
                    attachment = null
                )
            } catch (error: Exception) {
                val updated = mutableState.value.messages + ChatMessage(
                    text = error.message ?: "Unknown connection error.",
                    isUser = false,
                    isError = true
                )
                storage.saveMessages(updated)
                mutableState.value = mutableState.value.copy(messages = updated, sending = false)
            }
        }
    }

    fun testConnection() {
        val current = mutableState.value
        if (current.testing) return
        mutableState.value = current.copy(testing = true, testReport = "Connecting…")

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val discovery = runCatching { api.discoverModels(current.activeProfile) }
                val models = discovery.getOrNull().orEmpty()
                val profileForTest = if (current.activeProfile.model.isNotBlank()) {
                    current.activeProfile
                } else if (models.isNotEmpty()) {
                    current.activeProfile.copy(model = models.first())
                } else {
                    current.activeProfile
                }
                val connection = runCatching { api.send(profileForTest, "Reply with OK.") }
                Triple(discovery, models, connection)
            }

            if (result.second.isNotEmpty()) {
                mutableState.value = mutableState.value.copy(availableModels = result.second)
                if (current.activeProfile.model.isBlank()) {
                    updateProfile { profile ->
                        if (profile.model.isBlank()) profile.copy(model = result.second.first()) else profile
                    }
                }
            }

            val report = buildString {
                if (result.first.isSuccess) {
                    append("Discovered ${result.second.size} model(s)")
                    if (result.second.isNotEmpty()) append(": ${result.second.joinToString(", ")}")
                    append(".")
                } else {
                    append("Model discovery failed: ")
                    append(result.first.exceptionOrNull()?.message ?: "Unknown discovery error.")
                    append("\nThe selected model remains available for chat.")
                }
                append("\n\n")
                append(
                    result.third.getOrNull()?.report
                        ?: result.third.exceptionOrNull()?.message
                        ?: "Connection test failed."
                )
            }
            mutableState.value = mutableState.value.copy(testing = false, testReport = report)
        }
    }

    class Factory(private val storage: AppStorage) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
                return MainViewModel(storage) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
