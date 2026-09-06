package com.aiagent.chat.model

import kotlinx.serialization.*

/**
 * Cognitive tier for model assignment in the agent runtime.
 *
 * Instead of hardcoding vendor names, the runtime maps specialized agentic
 * responsibilities to cognitive tiers alongside an orchestration default.
 *
 * @see ModelTierConfiguration
 */
@Serializable
enum class ModelTier {
    @SerialName("fast") FAST,
    @SerialName("coder") CODER,
    @SerialName("architect") ARCHITECT,
    @SerialName("default") DEFAULT;

    companion object {
        /** Human-readable short role label for UI display. */
        fun shortRole(tier: ModelTier): String = when (tier) {
            FAST -> "Lightweight / Utility"
            CODER -> "Core Workhorse"
            ARCHITECT -> "Frontier / Reasoning"
            DEFAULT -> "Orchestration Target"
        }

        /** Full description of what the tier is for. */
        fun fullDescription(tier: ModelTier): String = when (tier) {
            FAST -> "Lightweight / Utility Tier - Sub-agent triage, tool argument validation, " +
                    "slash command resolution, and live steering classification. " +
                    "Sub-second latency, minimal token cost, high throughput, " +
                    "and shallow deterministic reasoning."
            CODER -> "Core Workhorse Tier - Primary agent execution loop, interactive tool calling, " +
                    "code generation, refactoring, and single-file modifications. " +
                    "Balanced multi-step analytical ability, strong instruction-following, " +
                    "and efficient execution speed."
            ARCHITECT -> "Frontier / Reasoning Tier - Whole-repository context synthesis, " +
                    "multi-step architectural planning, root-cause diagnosis for subtle bugs, " +
                    "and code reviews. Deep cognitive capacity, extensive context comprehension, " +
                    "and extended deliberative reasoning."
            DEFAULT -> "Orchestration Target - The fallback entry point for ad-hoc prompts " +
                    "and dynamic model routing. Aliased to the standard coder profile " +
                    "unless explicit heuristics or user flags trigger escalation to architect " +
                    "or delegation to fast."
        }
    }
}

/**
 * Configuration for a single model tier.
 * Maps a cognitive tier to a specific model from a provider.
 *
 * @param tier The cognitive tier this config applies to.
 * @param modelDisplayName Display name in "ProviderName/ModelName" format, or empty if not configured.
 * @param enabled Whether this tier is enabled.
 */
@Serializable
data class TierModelConfig(
    val tier: ModelTier,
    val modelDisplayName: String = "",
    val enabled: Boolean = true
)

/**
 * Complete model tier configuration for the agent runtime.
 *
 * Defines the model topology: four cognitive tiers (fast, coder, architect, default)
 * that map specialized agentic responsibilities to specific models.
 */
@Serializable
data class ModelTierConfiguration(
    val tiers: List<TierModelConfig> = ModelTier.values().map { tier ->
        TierModelConfig(tier = tier)
    }
) {
    /** Get the config for a specific tier. */
    fun getTier(tier: ModelTier): TierModelConfig =
        tiers.find { it.tier == tier } ?: TierModelConfig(tier)

    /** Get the model display name for a tier, or empty string if not configured. */
    fun getModelForTier(tier: ModelTier): String = getTier(tier).modelDisplayName

    /** Check if any tier has a model configured. */
    fun hasAnyConfigured(): Boolean = tiers.any { it.modelDisplayName.isNotBlank() }
}
