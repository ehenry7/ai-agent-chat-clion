package com.aiagent.chat.model

/**
 * Heuristic-based model categorization.
 *
 * Assigns a ModelSize (small/medium/large/XL) and ModelCost (free/low/medium/high)
 * to each fetched model ID using the [ModelSpecs] preset table first, then
 * pattern-based heuristics as fallback.
 *
 * Also generates a human-readable name from the model ID.
 */
object ModelCategorizer {

    /**
     * Categorize a single model ID into size, cost, and token settings.
     * First checks the ModelSpecs preset table, then falls back to heuristic patterns.
     * Also generates a simplified name from the model ID.
     */
    fun categorize(modelId: String, providerId: String = "", providerName: String = ""): ModelInfo {
        val lowerId = modelId.lowercase()

        // 1. Check preset specs table
        val spec = ModelSpecs.findSpec(modelId)
        if (spec != null) {
            return ModelInfo(
                id = modelId,
                providerId = providerId,
                providerName = providerName,
                name = generateName(modelId),
                sizeTag = spec.size,
                costTag = spec.cost,
                maxContextTokens = spec.contextTokens,
                maxOutputTokens = spec.outputTokens
            )
        }

        // 2. Heuristic patterns based on name keywords
        val size = guessSize(lowerId)
        val cost = guessCost(lowerId, size)

        return ModelInfo(
            id = modelId,
            providerId = providerId,
            providerName = providerName,
            name = generateName(modelId),
            sizeTag = size,
            costTag = cost
        )
    }

    /**
     * Categorize a list of model IDs from a provider.
     * Ensures generated names are unique within the batch by appending an index
     * if a name collision occurs.
     */
    fun categorizeAll(modelIds: List<String>, providerId: String, providerName: String): List<ModelInfo> {
        val models = modelIds.map { categorize(it, providerId, providerName) }
        // Deduplicate names by appending index
        val nameCounts = mutableMapOf<String, Int>()
        return models.map { m ->
            val baseName = m.name
            val count = nameCounts.getOrPut(baseName) { 0 }
            nameCounts[baseName] = count + 1
            if (count == 0) m else m.copy(name = "$baseName-$count")
        }
    }

    /**
     * Generate a simplified human-readable name from a model ID.
     * Examples:
     *   "gpt-4o-mini" -> "Gpt-4o-Mini"
     *   "deepseek-v3" -> "Deepseek-V3"
     *   "glm-5.2-1" -> "Glm-5.2-1"
     */
    fun generateName(modelId: String): String {
        // Split on hyphens and capitalize each segment
        return modelId.split("-")
            .filter { it.isNotBlank() }
            .joinToString("-") { segment ->
                segment.replaceFirstChar { it.uppercase() }
            }
    }

    /**
     * Heuristic size estimation based on model name keywords.
     */
    private fun guessSize(lowerId: String): ModelSize {
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
        if (lowerId.contains("-8b") || lowerId.contains("-7b") ||
            lowerId.contains("-13b") || lowerId.contains("-14b")) {
            return ModelSize.SMALL
        }
        if (lowerId.contains("-34b") || lowerId.contains("-32b") ||
            lowerId.contains("-35b")) {
            return ModelSize.MEDIUM
        }
        return ModelSize.MEDIUM
    }

    /**
     * Heuristic cost estimation based on model name and size.
     */
    private fun guessCost(lowerId: String, size: ModelSize): ModelCost {
        if (lowerId.contains("free") || (lowerId.contains("flash") && size == ModelSize.SMALL)) {
            return ModelCost.FREE
        }
        return when (size) {
            ModelSize.SMALL -> ModelCost.LOW_COST
            ModelSize.MEDIUM -> ModelCost.LOW_COST
            ModelSize.LARGE -> ModelCost.MEDIUM_COST
            ModelSize.XL -> ModelCost.HIGH_COST
        }
    }
}
