package com.aiagent.chat.model

/**
 * Heuristic-based model categorization.
 *
 * Assigns a ModelSize (small/medium/large/XL) and ModelCost (free/low/medium/high)
 * to each fetched model ID using a predefined mapping and pattern-based heuristics.
 *
 * Inspired by refact-main's model metadata system.
 */
object ModelCategorizer {

    /**
     * Predefined model mappings for known model families.
     * Keyed by lowercase substring match against the model ID.
     */
    private val KNOWN_MODELS: List<KnownModelEntry> = listOf(
        // --- OpenAI family ---
        KnownModelEntry("gpt-4o-mini", ModelSize.SMALL, ModelCost.LOW_COST),
        KnownModelEntry("gpt-4o", ModelSize.LARGE, ModelCost.HIGH_COST),
        KnownModelEntry("gpt-4-turbo", ModelSize.LARGE, ModelCost.HIGH_COST),
        KnownModelEntry("gpt-4", ModelSize.LARGE, ModelCost.HIGH_COST),
        KnownModelEntry("gpt-3.5-turbo", ModelSize.SMALL, ModelCost.LOW_COST),
        KnownModelEntry("o1-mini", ModelSize.MEDIUM, ModelCost.MEDIUM_COST),
        KnownModelEntry("o1-preview", ModelSize.XL, ModelCost.HIGH_COST),
        KnownModelEntry("o3-mini", ModelSize.MEDIUM, ModelCost.MEDIUM_COST),
        KnownModelEntry("o3", ModelSize.XL, ModelCost.HIGH_COST),

        // --- Claude family ---
        KnownModelEntry("claude-3-haiku", ModelSize.SMALL, ModelCost.LOW_COST),
        KnownModelEntry("claude-3-sonnet", ModelSize.MEDIUM, ModelCost.MEDIUM_COST),
        KnownModelEntry("claude-3-opus", ModelSize.XL, ModelCost.HIGH_COST),
        KnownModelEntry("claude-3.5-sonnet", ModelSize.LARGE, ModelCost.MEDIUM_COST),
        KnownModelEntry("claude-3-5-sonnet", ModelSize.LARGE, ModelCost.MEDIUM_COST),
        KnownModelEntry("claude-3.5-haiku", ModelSize.SMALL, ModelCost.LOW_COST),
        KnownModelEntry("claude-3-5-haiku", ModelSize.SMALL, ModelCost.LOW_COST),

        // --- DeepSeek family ---
        KnownModelEntry("deepseek-v3", ModelSize.LARGE, ModelCost.LOW_COST),
        KnownModelEntry("deepseek-r1", ModelSize.XL, ModelCost.MEDIUM_COST),
        KnownModelEntry("deepseek-coder", ModelSize.MEDIUM, ModelCost.LOW_COST),
        KnownModelEntry("deepseek-chat", ModelSize.MEDIUM, ModelCost.LOW_COST),
        KnownModelEntry("deepseek-v4-flash", ModelSize.SMALL, ModelCost.LOW_COST),
        KnownModelEntry("deepseek-v4", ModelSize.LARGE, ModelCost.MEDIUM_COST),

        // --- GLM family ---
        KnownModelEntry("glm-4-flash", ModelSize.SMALL, ModelCost.FREE),
        KnownModelEntry("glm-4-air", ModelSize.SMALL, ModelCost.LOW_COST),
        KnownModelEntry("glm-4-plus", ModelSize.LARGE, ModelCost.MEDIUM_COST),
        KnownModelEntry("glm-4-long", ModelSize.LARGE, ModelCost.MEDIUM_COST),
        KnownModelEntry("glm-5", ModelSize.LARGE, ModelCost.MEDIUM_COST),
        KnownModelEntry("glm-5.2", ModelSize.LARGE, ModelCost.MEDIUM_COST),

        // --- Llama family ---
        KnownModelEntry("llama-3.1-8b", ModelSize.SMALL, ModelCost.FREE),
        KnownModelEntry("llama-3.1-70b", ModelSize.MEDIUM, ModelCost.LOW_COST),
        KnownModelEntry("llama-3.1-405b", ModelSize.XL, ModelCost.HIGH_COST),
        KnownModelEntry("llama-3.3-70b", ModelSize.MEDIUM, ModelCost.LOW_COST),

        // --- Qwen family ---
        KnownModelEntry("qwen-2.5-7b", ModelSize.SMALL, ModelCost.FREE),
        KnownModelEntry("qwen-2.5-72b", ModelSize.LARGE, ModelCost.LOW_COST),
        KnownModelEntry("qwen-2.5-coder", ModelSize.MEDIUM, ModelCost.LOW_COST),

        // --- Mistral family ---
        KnownModelEntry("mistral-small", ModelSize.SMALL, ModelCost.LOW_COST),
        KnownModelEntry("mistral-large", ModelSize.LARGE, ModelCost.HIGH_COST),
        KnownModelEntry("mixtral-8x7b", ModelSize.MEDIUM, ModelCost.LOW_COST),
        KnownModelEntry("mixtral-8x22b", ModelSize.LARGE, ModelCost.MEDIUM_COST),

        // --- Gemini family ---
        KnownModelEntry("gemini-flash", ModelSize.SMALL, ModelCost.LOW_COST),
        KnownModelEntry("gemini-pro", ModelSize.MEDIUM, ModelCost.MEDIUM_COST),
        KnownModelEntry("gemini-1.5-pro", ModelSize.LARGE, ModelCost.MEDIUM_COST),
        KnownModelEntry("gemini-1.5-flash", ModelSize.SMALL, ModelCost.LOW_COST),
        KnownModelEntry("gemini-2.0-flash", ModelSize.SMALL, ModelCost.LOW_COST),
        KnownModelEntry("gemini-2.5-pro", ModelSize.XL, ModelCost.HIGH_COST)
    )

    private data class KnownModelEntry(
        val pattern: String,
        val size: ModelSize,
        val cost: ModelCost
    )

    /**
     * Categorize a single model ID into size and cost tags.
     * First checks the known model mapping, then falls back to heuristic patterns.
     */
    fun categorize(modelId: String, providerId: String = "", providerName: String = ""): ModelInfo {
        val lowerId = modelId.lowercase()

        // 1. Check known model mappings
        for (entry in KNOWN_MODELS) {
            if (lowerId.contains(entry.pattern)) {
                return ModelInfo(
                    id = modelId,
                    providerId = providerId,
                    providerName = providerName,
                    sizeTag = entry.size,
                    costTag = entry.cost
                )
            }
        }

        // 2. Heuristic patterns based on name keywords
        val size = guessSize(lowerId)
        val cost = guessCost(lowerId, size)

        return ModelInfo(
            id = modelId,
            providerId = providerId,
            providerName = providerName,
            sizeTag = size,
            costTag = cost
        )
    }

    /**
     * Categorize a list of model IDs from a provider.
     */
    fun categorizeAll(modelIds: List<String>, providerId: String, providerName: String): List<ModelInfo> {
        return modelIds.map { categorize(it, providerId, providerName) }
    }

    /**
     * Heuristic size estimation based on model name keywords.
     */
    private fun guessSize(lowerId: String): ModelSize {
        // Size keywords
        if (lowerId.contains("mini") || lowerId.contains("flash") ||
            lowerId.contains("haiku") || lowerId.contains("air") ||
            lowerId.contains("tiny") || lowerId.contains("nano") ||
            lowerId.contains("small") || lowerId.contains("lite")) {
            return ModelSize.SMALL
        }
        if (lowerId.contains("xl") || lowerId.contains("opus") ||
            lowerId.contains("405b") || lowerId.contains("ultra") ||
            lowerId.contains("max") || lowerId.contains("pro-max")) {
            return ModelSize.XL
        }
        if (lowerId.contains("large") || lowerId.contains("plus") ||
            lowerId.contains("pro") || lowerId.contains("70b") ||
            lowerId.contains("72b") || lowerId.contains("turbo")) {
            return ModelSize.LARGE
        }
        // Parameter count hints
        if (lowerId.contains("-8b") || lowerId.contains("-7b") ||
            lowerId.contains("-13b") || lowerId.contains("-14b")) {
            return ModelSize.SMALL
        }
        if (lowerId.contains("-34b") || lowerId.contains("-32b") ||
            lowerId.contains("-35b")) {
            return ModelSize.MEDIUM
        }

        return ModelSize.MEDIUM // safe default
    }

    /**
     * Heuristic cost estimation based on model name and size.
     */
    private fun guessCost(lowerId: String, size: ModelSize): ModelCost {
        // Free models
        if (lowerId.contains("free") || lowerId.contains("flash") && size == ModelSize.SMALL) {
            return ModelCost.FREE
        }
        // Cost correlates with size
        return when (size) {
            ModelSize.SMALL -> ModelCost.LOW_COST
            ModelSize.MEDIUM -> ModelCost.LOW_COST
            ModelSize.LARGE -> ModelCost.MEDIUM_COST
            ModelSize.XL -> ModelCost.HIGH_COST
        }
    }
}
