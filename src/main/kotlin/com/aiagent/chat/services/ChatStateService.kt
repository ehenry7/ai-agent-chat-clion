package com.aiagent.chat.services

import com.aiagent.chat.model.ApprovalMode
import com.aiagent.chat.model.AuthHeaderType
import com.aiagent.chat.model.ProviderConfig
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*

@State(
    name = "AIAgentChatSettings",
    storages = [Storage("ai-agent-chat.xml")]
)
@Service(Service.Level.APP)
class ChatStateService : PersistentStateComponent<ChatStateService.State> {

    class State {
        var baseUrl: String = "http://techdev.hicomputing.huawei.com:18000"
        var model: String = "GLM-5.2-1"
        var maxSteps: Int = 25
        var apiKey: String = ""
        var approvalMode: String = ApprovalMode.BALANCED.name

        // --- Multi-provider support ---
        /** JSON-serialized list of ProviderConfig. Stored as string for IntelliJ persistence compatibility. */
        var providersJson: String = ""
        /** Whether multi-provider mode is enabled (falls back to single-provider if false). */
        var multiProviderEnabled: Boolean = false
        /** Whether dynamic model routing is enabled. */
        var dynamicRoutingEnabled: Boolean = false
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    // --- Legacy single-provider accessors (backward compatibility) ---

    fun getApiKey(): String? = myState.apiKey.ifBlank { null }
    fun setApiKey(key: String?) { myState.apiKey = key ?: "" }
    fun isApiKeySet(): Boolean = myState.apiKey.isNotBlank()

    // --- Multi-provider accessors ---

    /**
     * Get the list of configured providers.
     * Returns empty list if providersJson is blank or parsing fails.
     */
    fun getProviders(): List<ProviderConfig> {
        if (myState.providersJson.isBlank()) return emptyList()
        return try {
            kotlinx.serialization.json.Json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(ProviderConfig.serializer()),
                myState.providersJson
            )
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Save the list of providers as JSON string.
     */
    fun setProviders(providers: List<ProviderConfig>) {
        myState.providersJson = kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(ProviderConfig.serializer()),
            providers
        )
    }

    /**
     * Add a single provider to the list.
     */
    fun addProvider(provider: ProviderConfig) {
        val current = getProviders().toMutableList()
        // Replace if ID already exists
        val idx = current.indexOfFirst { it.id == provider.id }
        if (idx >= 0) current[idx] = provider else current.add(provider)
        setProviders(current)
    }

    /**
     * Remove a provider by ID.
     */
    fun removeProvider(providerId: String) {
        val current = getProviders().toMutableList()
        current.removeAll { it.id == providerId }
        setProviders(current)
    }

    fun isMultiProviderEnabled(): Boolean = myState.multiProviderEnabled
    fun setMultiProviderEnabled(enabled: Boolean) { myState.multiProviderEnabled = enabled }

    fun isDynamicRoutingEnabled(): Boolean = myState.dynamicRoutingEnabled
    fun setDynamicRoutingEnabled(enabled: Boolean) { myState.dynamicRoutingEnabled = enabled }
}
