package com.aiagent.chat.model

/**
 * Dynamic task router that analyzes incoming task complexity
 * and selects the optimal model based on size and cost tags.
 *
 * Routing strategy:
 * - SIMPLE  -> small/free or small/low_cost models
 * - MEDIUM  -> medium/low_cost or medium/medium_cost models
 * - COMPLEX -> large/medium_cost or large/high_cost models
 * - XL_TASK -> xl/high_cost models (deep reasoning)
 *
 * Falls back to the largest available model if no exact match is found.
 */
object ModelRouter {

    /**
     * Analyze task complexity from the user's prompt text.
     * Uses keyword heuristics to classify the task.
     */
    fun analyzeComplexity(prompt: String): TaskComplexity {
        val lower = prompt.lowercase()

        // XL_TASK indicators: deep reasoning, system design, complex debugging
        val xlKeywords = listOf(
            "architect", "system design", "redesign", "refactor entire",
            "complex debug", "deep analysis", "comprehensive review",
            "optimize architecture", "migrate", "full rewrite",
            "security audit", "performance audit", "end-to-end"
        )
        if (xlKeywords.any { lower.contains(it) }) {
            return TaskComplexity.XL_TASK
        }

        // COMPLEX indicators: multi-file, refactoring, new feature
        val complexKeywords = listOf(
            "refactor", "implement feature", "add feature", "new module",
            "multi-file", "multiple files", "integration", "test suite",
            "api design", "database schema", "concurrency", "thread safety",
            "algorithm design", "data structure design", "write a parser", "write a compiler"
        )
        if (complexKeywords.any { lower.contains(it) }) {
            return TaskComplexity.COMPLEX
        }

        // MEDIUM indicators: tests, bug fix, explain
        val mediumKeywords = listOf(
            "write test", "unit test", "bug fix", "fix bug", "explain",
            "document", "add comment", "type hint", "rename", "extract method",
            "simplify", "clean up", "review", "summarize"
        )
        if (mediumKeywords.any { lower.contains(it) }) {
            return TaskComplexity.MEDIUM
        }

        // SIMPLE indicators: formatting, listing, simple edits
        val simpleKeywords = listOf(
            "format", "indent", "list", "show", "print", "replace text",
            "rename variable", "add import", "fix typo", "sort", "count",
            "find all", "grep", "search for"
        )
        if (simpleKeywords.any { lower.contains(it) }) {
            return TaskComplexity.SIMPLE
        }

        // Default: MEDIUM for unknown tasks (safe middle ground)
        return TaskComplexity.MEDIUM
    }

    /**
     * Select the optimal model for a given task complexity from the available model pool.
     *
     * @param complexity The analyzed task complexity
     * @param availableModels All categorized models across all providers
     * @return The best matching ModelInfo, or null if no models available
     */
    fun selectModel(complexity: TaskComplexity, availableModels: List<ModelInfo>): ModelInfo? {
        if (availableModels.isEmpty()) return null

        // Define preferred (size, cost) pairs for each complexity level, in priority order
        val preferenceChain: List<Pair<ModelSize, ModelCost?>> = when (complexity) {
            TaskComplexity.SIMPLE -> listOf(
                ModelSize.SMALL to ModelCost.FREE,
                ModelSize.SMALL to ModelCost.LOW_COST,
                ModelSize.SMALL to null,
                ModelSize.MEDIUM to ModelCost.LOW_COST,
                ModelSize.MEDIUM to null
            )
            TaskComplexity.MEDIUM -> listOf(
                ModelSize.MEDIUM to ModelCost.LOW_COST,
                ModelSize.MEDIUM to ModelCost.MEDIUM_COST,
                ModelSize.MEDIUM to null,
                ModelSize.SMALL to ModelCost.LOW_COST,
                ModelSize.LARGE to ModelCost.LOW_COST
            )
            TaskComplexity.COMPLEX -> listOf(
                ModelSize.LARGE to ModelCost.MEDIUM_COST,
                ModelSize.LARGE to ModelCost.HIGH_COST,
                ModelSize.LARGE to null,
                ModelSize.XL to ModelCost.MEDIUM_COST,
                ModelSize.MEDIUM to ModelCost.MEDIUM_COST
            )
            TaskComplexity.XL_TASK -> listOf(
                ModelSize.XL to ModelCost.HIGH_COST,
                ModelSize.XL to ModelCost.MEDIUM_COST,
                ModelSize.XL to null,
                ModelSize.LARGE to ModelCost.HIGH_COST,
                ModelSize.LARGE to ModelCost.MEDIUM_COST
            )
        }

        // Try each preference in order
        for ((preferredSize, preferredCost) in preferenceChain) {
            val match = availableModels.firstOrNull { model ->
                model.sizeTag == preferredSize &&
                (preferredCost == null || model.costTag == preferredCost)
            }
            if (match != null) return match
        }

        // Fallback: return the largest model available
        return availableModels.maxByOrNull { it.sizeTag.ordinal }
    }

    /**
     * Convenience method: analyze prompt and select model in one call.
     */
    fun routeTask(prompt: String, availableModels: List<ModelInfo>): ModelInfo? {
        val complexity = analyzeComplexity(prompt)
        return selectModel(complexity, availableModels)
    }

    /**
     * Generate a human-readable routing explanation for logging/debugging.
     */
    fun explainRouting(complexity: TaskComplexity, selectedModel: ModelInfo?): String {
        if (selectedModel == null) return "No model available for complexity=$complexity"
        return "Task complexity=$complexity -> selected model=${selectedModel.id} " +
               "(size=${selectedModel.sizeTag.displayName}, cost=${selectedModel.costTag.displayName}, " +
               "provider=${selectedModel.providerName})"
    }
}
