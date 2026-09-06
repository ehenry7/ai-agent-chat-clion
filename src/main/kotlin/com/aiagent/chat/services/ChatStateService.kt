package com.aiagent.chat.services

import com.aiagent.chat.model.ApprovalMode
import com.aiagent.chat.model.AuthHeaderType
import com.aiagent.chat.model.ModelTierConfiguration
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
        var model: String = ""
        var maxSteps: Int = 100
        var apiKey: String = ""
        var approvalMode: String = ApprovalMode.BALANCED.name
        var maxContextTokens: Int = 32768
        var maxOutputTokens: Int = 4096

        // --- Multi-provider support ---
        /** JSON-serialized list of ProviderConfig. Stored as string for IntelliJ persistence compatibility. */
        var providersJson: String = ""
        /** Whether multi-provider mode is enabled (falls back to single-provider if false). */
        var multiProviderEnabled: Boolean = false
        /** Whether dynamic model routing is enabled. */
        var dynamicRoutingEnabled: Boolean = false
        /** ID of the default provider (used first). */
        var defaultProviderId: String = ""
        /** Full display name of the default model (ProviderName/ModelName). */
        var defaultModelDisplayName: String = ""

        // --- Multi-tier model configuration ---
        /** JSON-serialized ModelTierConfiguration for cognitive tier model assignment. */
        var modelTierConfigJson: String = ""
        /** Whether multi-tier model configuration is enabled. */
        var modelTierEnabled: Boolean = false
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

    // --- Multi-tier model configuration accessors ---

    /**
     * Get the model tier configuration.
     * Returns a default configuration if not set or parsing fails.
     */
    fun getModelTierConfig(): ModelTierConfiguration {
        if (myState.modelTierConfigJson.isBlank()) return ModelTierConfiguration()
        return try {
            kotlinx.serialization.json.Json.decodeFromString(
                ModelTierConfiguration.serializer(),
                myState.modelTierConfigJson
            )
        } catch (e: Exception) {
            ModelTierConfiguration()
        }
    }

    /**
     * Save the model tier configuration as JSON string.
     */
    fun setModelTierConfig(config: ModelTierConfiguration) {
        myState.modelTierConfigJson = kotlinx.serialization.json.Json.encodeToString(
            ModelTierConfiguration.serializer(),
            config
        )
    }

    fun isModelTierEnabled(): Boolean = myState.modelTierEnabled
    fun setModelTierEnabled(enabled: Boolean) { myState.modelTierEnabled = enabled }
}
