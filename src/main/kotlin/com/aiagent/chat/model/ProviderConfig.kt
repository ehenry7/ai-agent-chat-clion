package com.aiagent.chat.model

import kotlinx.serialization.*

/**
 * Authentication header type for API requests.
 * Different providers require different auth header formats.
 */
@Serializable
enum class AuthHeaderType {
    @SerialName("bearer") BEARER,      // Authorization: Bearer <api_key>
    @SerialName("x-api-key") X_API_KEY // x-api-key: <api_key>
}

/**
 * Model size category for routing decisions.
 */
@Serializable
enum class ModelSize {
    @SerialName("small") SMALL,
    @SerialName("medium") MEDIUM,
    @SerialName("large") LARGE,
    @SerialName("xl") XL;

    val displayName: String get() = when (this) {
        SMALL -> "small"
        MEDIUM -> "medium"
        LARGE -> "large"
        XL -> "X-Large"
    }
}

/**
 * Model cost category for routing decisions.
 */
@Serializable
enum class ModelCost {
    @SerialName("free") FREE,
    @SerialName("low_cost") LOW_COST,
    @SerialName("medium_cost") MEDIUM_COST,
    @SerialName("high_cost") HIGH_COST;

    val displayName: String get() = when (this) {
        FREE -> "free"
        LOW_COST -> "low-cost"
        MEDIUM_COST -> "medium-cost"
        HIGH_COST -> "high-cost"
    }
}

/**
 * Categorized model information.
 * Each model is tagged with size and cost for dynamic task routing.
 */
@Serializable
data class ModelInfo(
    val id: String,
    val providerId: String,
    val providerName: String = "",
    val name: String = "",
    val sizeTag: ModelSize = ModelSize.MEDIUM,
    val costTag: ModelCost = ModelCost.LOW_COST,
    val maxContextTokens: Int = 32768,
    val maxOutputTokens: Int = 4096,
    val enabled: Boolean = true,
    val measured: Boolean = false,
    val latencyMs: Long = 0
) {
    val displayName: String get() = if (providerName.isNotBlank()) "$providerName/${name.ifBlank { id }}" else name.ifBlank { id }
}

/**
 * Provider configuration for multi-provider support.
 * Each provider has its own base URL, API key, auth type, and fetched model list.
 */
@Serializable
data class ProviderConfig(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val authHeaderType: AuthHeaderType = AuthHeaderType.BEARER,
    val enabled: Boolean = true,
    val isDefault: Boolean = false,
    val models: List<ModelInfo> = emptyList()
) {
    fun toApiHeaders(): Map<String, String> {
        return when (authHeaderType) {
            AuthHeaderType.BEARER -> mapOf("Authorization" to "Bearer $apiKey")
            AuthHeaderType.X_API_KEY -> mapOf("x-api-key" to apiKey)
        }
    }
}

/**
 * Task complexity level for model routing.
 */
enum class TaskComplexity {
    SIMPLE,      // formatting, listing, simple edits
    MEDIUM,      // writing tests, explaining code, bug fixes
    COMPLEX,     // refactoring, multi-file changes, architecture
    XL_TASK      // deep reasoning, system design, complex debugging
}
