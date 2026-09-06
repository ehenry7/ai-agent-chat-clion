package com.aiagent.chat.model

import com.aiagent.chat.debug.DebugLog
import com.aiagent.chat.net.ApiClient

/**
 * Manages model tier configuration and provides tier-aware ApiClient instances.
 *
 * Maps the four cognitive tiers (fast, coder, architect, default) to specific
 * ApiClient instances built from the configured providers and models.
 *
 * The agent loop uses this to select the appropriate model for each task:
 * - fast: sub-agent triage, tool argument validation, slash command resolution
 * - coder: primary agent execution loop, interactive tool calling, code generation
 * - architect: whole-repository context synthesis, architectural planning, code reviews
 * - default: fallback entry point for ad-hoc prompts and dynamic model routing
 */
class ModelTierManager(
    private val providerManager: ProviderManager
) {
    /** Current tier configuration. */
    var configuration: ModelTierConfiguration = ModelTierConfiguration()
        private set

    /** Cached clients per tier. */
    private val clientCache = mutableMapOf<ModelTier, ApiClient>()

    /** Max output tokens override (from settings). */
    var maxOutputTokens: Int? = null

    /**
     * Update the tier configuration and clear the client cache.
     */
    fun updateConfiguration(config: ModelTierConfiguration) {
        configuration = config
        clientCache.clear()
        DebugLog.info("ModelTierManager", "Tier configuration updated: " +
                configuration.tiers.filter { it.modelDisplayName.isNotBlank() }
                    .joinToString { "${it.tier.name}=${it.modelDisplayName}" })
    }

    /**
     * Resolve a tier to its configured model display name.
     * If the tier is disabled or not configured, falls back to default tier,
     * then to the first available model.
     */
    fun resolveModelDisplayName(tier: ModelTier): String? {
        val tierConfig = configuration.getTier(tier)
        if (tierConfig.enabled && tierConfig.modelDisplayName.isNotBlank()) {
            return tierConfig.modelDisplayName
        }
        // Fallback to default tier
        if (tier != ModelTier.DEFAULT) {
            val defaultConfig = configuration.getTier(ModelTier.DEFAULT)
            if (defaultConfig.enabled && defaultConfig.modelDisplayName.isNotBlank()) {
                return defaultConfig.modelDisplayName
            }
        }
        // Fallback to any configured tier
        return configuration.tiers
            .filter { it.enabled && it.modelDisplayName.isNotBlank() }
            .firstOrNull()?.modelDisplayName
    }

    /**
     * Resolve a model display name to its ModelInfo and ProviderConfig.
     * Display name format: "ProviderName/ModelName"
     */
    fun resolveModel(displayName: String): Pair<ProviderConfig, ModelInfo>? {
        val parts = displayName.split("/", limit = 2)
        if (parts.size != 2) return null
        val providerName = parts[0]
        val modelName = parts[1]
        val provider = providerManager.providers.find { it.name == providerName } ?: return null
        val model = provider.models.find { it.name == modelName || it.id == modelName } ?: return null
        return provider to model
    }

    /**
     * Get or create an ApiClient for the specified tier.
     * Returns null if no model is configured for the tier and no fallback is available.
     */
    fun getClient(tier: ModelTier): ApiClient? {
        clientCache[tier]?.let { return it }

        val displayName = resolveModelDisplayName(tier) ?: run {
            DebugLog.warn("ModelTierManager", "No model configured for tier ${tier.name}, no fallback available")
            return null
        }

        val resolved = resolveModel(displayName) ?: run {
            DebugLog.warn("ModelTierManager", "Could not resolve model '$displayName' to a provider")
            return null
        }

        val (provider, model) = resolved
        val client = ApiClient(
            baseUrl = provider.baseUrl,
            apiKey = provider.apiKey,
            model = model.id,
            authHeaderType = provider.authHeaderType,
            maxOutputTokens = maxOutputTokens ?: if (model.maxOutputTokens > 0) model.maxOutputTokens else null
        )

        clientCache[tier] = client
        DebugLog.info("ModelTierManager", "Created client for tier ${tier.name}: model=${model.id}, provider=${provider.name}")
        return client
    }

    /**
     * Get the ApiClient for the default tier (main agent loop).
     * This is the primary client used when no specific tier is needed.
     */
    fun getDefaultClient(): ApiClient? = getClient(ModelTier.DEFAULT)

    /**
     * Get the ApiClient for the coder tier (primary execution loop).
     * Falls back to default if coder is not configured.
     */
    fun getCoderClient(): ApiClient? = getClient(ModelTier.CODER)

    /**
     * Get the ApiClient for the fast tier (lightweight utility tasks).
     * Falls back to default if fast is not configured.
     */
    fun getFastClient(): ApiClient? = getClient(ModelTier.FAST)

    /**
     * Get the ApiClient for the architect tier (deep reasoning tasks).
     * Falls back to default if architect is not configured.
     */
    fun getArchitectClient(): ApiClient? = getClient(ModelTier.ARCHITECT)

    /**
     * Check if tier configuration is active (at least one tier has a model configured).
     */
    fun isActive(): Boolean = configuration.hasAnyConfigured()

    /**
     * Build a system prompt section describing the tier configuration.
     * This informs the LLM about which models are available for each cognitive role.
     */
    fun toSystemPromptSection(): String {
        if (!isActive()) return ""

        val sb = StringBuilder()
        sb.append("\n<model_tier_configuration>\n")
        sb.append("The agent runtime uses a multi-tier model topology. Each cognitive tier is mapped to a specific model:\n\n")

        for (tier in ModelTier.values()) {
            val config = configuration.getTier(tier)
            val model = if (config.enabled && config.modelDisplayName.isNotBlank()) {
                config.modelDisplayName
            } else {
                "(not configured - falls back to default)"
            }
            sb.append("  ${tier.name}: ${ModelTier.shortRole(tier)}\n")
            sb.append("    Model: $model\n")
            sb.append("    Role: ${ModelTier.fullDescription(tier)}\n\n")
        }

        sb.append("Use the appropriate model for each task based on its cognitive requirements.\n")
        sb.append("</model_tier_configuration>")
        return sb.toString()
    }

    /**
     * Clear the client cache (e.g., when providers are updated).
     */
    fun clearCache() {
        clientCache.clear()
    }
}
