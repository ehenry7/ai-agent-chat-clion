package com.aiagent.chat.model

/**
 * ========================================================================
 *  PRESET MODEL SPECIFICATIONS TABLE
 * ========================================================================
 *
 *  This is the single place to modify preset type/size/cost/token values
 *  for known models. When models are fetched from a provider API, each
 *  model ID is matched against the [SPECS] list below (case-insensitive
 *  substring match, first match wins).
 *
 *  To add or modify a model's preset values:
 *    1. Find the model's entry in the [SPECS] list below.
 *    2. Adjust size, cost, contextTokens, or outputTokens as needed.
 *    3. To add a new model, insert a new [ModelSpec] entry at the top of
 *       the appropriate family section.
 *
 *  Fields:
 *    - pattern      : Substring to match against the model ID (lowercase)
 *    - size         : SMALL / MEDIUM / LARGE / XL
 *    - cost         : FREE / LOW_COST / MEDIUM_COST / HIGH_COST
 *    - contextTokens: Maximum context window in tokens
 *    - outputTokens : Maximum output tokens
 * ========================================================================
 */

data class ModelSpec(
    val pattern: String,
    val size: ModelSize,
    val cost: ModelCost,
    val contextTokens: Int,
    val outputTokens: Int
)

object ModelSpecs {

    /**
     * Master table of preset model specifications.
     * Ordered by family. First match wins, so more specific patterns
     * should come before less specific ones within the same family.
     *
     * *** EDIT THIS TABLE TO MODIFY PRESET MODEL VALUES ***
     */
    val SPECS: List<ModelSpec> = listOf(
        // ── OpenAI family ──────────────────────────────────────────────
        ModelSpec("gpt-4o-mini",      ModelSize.SMALL,  ModelCost.LOW_COST,     128000, 16384),
        ModelSpec("gpt-4o",           ModelSize.LARGE,  ModelCost.HIGH_COST,    128000, 16384),
        ModelSpec("gpt-4-turbo",      ModelSize.LARGE,  ModelCost.HIGH_COST,    128000, 4096),
        ModelSpec("gpt-4",            ModelSize.LARGE,  ModelCost.HIGH_COST,     8192,  4096),
        ModelSpec("gpt-3.5-turbo",    ModelSize.SMALL,  ModelCost.LOW_COST,     16385,  4096),
        ModelSpec("o1-mini",          ModelSize.MEDIUM, ModelCost.MEDIUM_COST,  128000, 65536),
        ModelSpec("o1-preview",       ModelSize.XL,     ModelCost.HIGH_COST,    128000, 65536),
        ModelSpec("o3-mini",          ModelSize.MEDIUM, ModelCost.MEDIUM_COST,  200000, 100000),
        ModelSpec("o3",               ModelSize.XL,     ModelCost.HIGH_COST,    200000, 100000),
        ModelSpec("o4-mini",          ModelSize.MEDIUM, ModelCost.MEDIUM_COST,  200000, 100000),

        // ── Claude family ──────────────────────────────────────────────
        ModelSpec("claude-3-haiku",   ModelSize.SMALL,  ModelCost.LOW_COST,     200000, 4096),
        ModelSpec("claude-3-sonnet",  ModelSize.MEDIUM, ModelCost.MEDIUM_COST,  200000, 4096),
        ModelSpec("claude-3-opus",    ModelSize.XL,     ModelCost.HIGH_COST,    200000, 4096),
        ModelSpec("claude-3.5-sonnet",ModelSize.LARGE,  ModelCost.MEDIUM_COST,  200000, 8192),
        ModelSpec("claude-3-5-sonnet",ModelSize.LARGE,  ModelCost.MEDIUM_COST,  200000, 8192),
        ModelSpec("claude-3.5-haiku", ModelSize.SMALL,  ModelCost.LOW_COST,     200000, 8192),
        ModelSpec("claude-3-5-haiku", ModelSize.SMALL,  ModelCost.LOW_COST,     200000, 8192),
        ModelSpec("claude-sonnet-4",  ModelSize.LARGE,  ModelCost.MEDIUM_COST,  200000, 16384),
        ModelSpec("claude-opus-4",    ModelSize.XL,     ModelCost.HIGH_COST,    200000, 16384),

        // ── DeepSeek family ────────────────────────────────────────────
        ModelSpec("deepseek-v3",      ModelSize.LARGE,  ModelCost.LOW_COST,      64000, 8192),
        ModelSpec("deepseek-r1",      ModelSize.XL,     ModelCost.MEDIUM_COST,   64000, 8192),
        ModelSpec("deepseek-coder",   ModelSize.MEDIUM, ModelCost.LOW_COST,      64000, 8192),
        ModelSpec("deepseek-chat",    ModelSize.MEDIUM, ModelCost.LOW_COST,      64000, 8192),
        ModelSpec("deepseek-v4-flash",ModelSize.SMALL,  ModelCost.LOW_COST,      64000, 8192),
        ModelSpec("deepseek-v4",      ModelSize.LARGE,  ModelCost.MEDIUM_COST,   64000, 8192),

        // ── GLM family (ZhipuAI) ───────────────────────────────────────
        ModelSpec("glm-4-flash",      ModelSize.SMALL,  ModelCost.FREE,         128000, 4096),
        ModelSpec("glm-4-air",        ModelSize.SMALL,  ModelCost.LOW_COST,     128000, 4096),
        ModelSpec("glm-4-plus",       ModelSize.LARGE,  ModelCost.MEDIUM_COST,  128000, 4096),
        ModelSpec("glm-4-long",       ModelSize.LARGE,  ModelCost.MEDIUM_COST,  1000000, 4096),
        ModelSpec("glm-5",            ModelSize.LARGE,  ModelCost.MEDIUM_COST,  128000, 8192),
        ModelSpec("glm-5.2",          ModelSize.LARGE,  ModelCost.MEDIUM_COST,  128000, 8192),

        // ── Llama family ───────────────────────────────────────────────
        ModelSpec("llama-3.1-8b",     ModelSize.SMALL,  ModelCost.FREE,         128000, 4096),
        ModelSpec("llama-3.1-70b",    ModelSize.MEDIUM, ModelCost.LOW_COST,     128000, 4096),
        ModelSpec("llama-3.1-405b",   ModelSize.XL,     ModelCost.HIGH_COST,    128000, 4096),
        ModelSpec("llama-3.3-70b",    ModelSize.MEDIUM, ModelCost.LOW_COST,     128000, 4096),

        // ── Qwen family ────────────────────────────────────────────────
        ModelSpec("qwen-2.5-7b",      ModelSize.SMALL,  ModelCost.FREE,         128000, 8192),
        ModelSpec("qwen-2.5-72b",     ModelSize.LARGE,  ModelCost.LOW_COST,     128000, 8192),
        ModelSpec("qwen-2.5-coder",   ModelSize.MEDIUM, ModelCost.LOW_COST,     128000, 8192),
        ModelSpec("qwen-3",           ModelSize.LARGE,  ModelCost.MEDIUM_COST,  128000, 8192),

        // ── Mistral family ─────────────────────────────────────────────
        ModelSpec("mistral-small",    ModelSize.SMALL,  ModelCost.LOW_COST,      32000, 8192),
        ModelSpec("mistral-large",    ModelSize.LARGE,  ModelCost.HIGH_COST,    128000, 8192),
        ModelSpec("mixtral-8x7b",     ModelSize.MEDIUM, ModelCost.LOW_COST,      32000, 8192),
        ModelSpec("mixtral-8x22b",    ModelSize.LARGE,  ModelCost.MEDIUM_COST,   64000, 8192),

        // ── Gemini family ──────────────────────────────────────────────
        ModelSpec("gemini-flash",     ModelSize.SMALL,  ModelCost.LOW_COST,    1000000, 8192),
        ModelSpec("gemini-pro",       ModelSize.MEDIUM, ModelCost.MEDIUM_COST, 1000000, 8192),
        ModelSpec("gemini-1.5-pro",   ModelSize.LARGE,  ModelCost.MEDIUM_COST, 2000000, 8192),
        ModelSpec("gemini-1.5-flash", ModelSize.SMALL,  ModelCost.LOW_COST,    1000000, 8192),
        ModelSpec("gemini-2.0-flash", ModelSize.SMALL,  ModelCost.LOW_COST,    1000000, 8192),
        ModelSpec("gemini-2.5-pro",   ModelSize.XL,     ModelCost.HIGH_COST,   2000000, 8192),

        // ── Huawei Pangu / other ───────────────────────────────────────
        ModelSpec("pangu",            ModelSize.LARGE,  ModelCost.MEDIUM_COST,   32000, 4096)
    )

    /**
     * Look up a model spec by ID (case-insensitive substring match).
     * Returns null if no match found.
     */
    fun findSpec(modelId: String): ModelSpec? {
        val lower = modelId.lowercase()
        return SPECS.firstOrNull { lower.contains(it.pattern) }
    }

    /**
     * Get the context tokens for a model, falling back to a default.
     */
    fun contextTokens(modelId: String): Int =
        findSpec(modelId)?.contextTokens ?: 32768

    /**
     * Get the output tokens for a model, falling back to a default.
     */
    fun outputTokens(modelId: String): Int =
        findSpec(modelId)?.outputTokens ?: 4096
}
