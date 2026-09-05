package com.aiagent.chat.model

import com.aiagent.chat.debug.DebugLog
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.future.await

/**
 * Manages the list of AI providers and their models.
 *
 * Responsibilities:
 * - Add/remove providers
 * - Sync models from a provider's API (GET /models or /v1/models)
 * - Categorize fetched models using ModelCategorizer
 * - Provide a flat list of all available models across all providers
 * - Build system prompt section listing available models
 */
class ProviderManager {

    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    private val _providers = mutableListOf<ProviderConfig>()
    private val _allModels = mutableListOf<ModelInfo>()

    /** Thread-safe read access to providers */
    val providers: List<ProviderConfig> get() = _providers.toList()

    /** Thread-safe read access to all categorized models across all providers */
    val allModels: List<ModelInfo> get() = _allModels.toList()

    /**
     * Add a provider and immediately sync its models.
     * Returns the updated ProviderConfig with fetched models.
     */
    suspend fun addProvider(provider: ProviderConfig): ProviderConfig {
        DebugLog.info("ProviderManager", "Adding provider: ${provider.name} (${provider.baseUrl})")
        _providers.add(provider)
        val synced = syncModels(provider)
        val idx = _providers.indexOfFirst { it.id == provider.id }
        if (idx >= 0) _providers[idx] = synced
        rebuildAllModels()
        return synced
    }

    /**
     * Add a provider without syncing (for testing or offline setup).
     */
    fun addProviderOffline(provider: ProviderConfig) {
        _providers.add(provider)
        rebuildAllModels()
    }

    /**
     * Remove a provider by ID.
     */
    fun removeProvider(providerId: String) {
        _providers.removeAll { it.id == providerId }
        rebuildAllModels()
    }

    /**
     * Update an existing provider's config (e.g., changed API key).
     */
    fun updateProvider(updated: ProviderConfig) {
        val idx = _providers.indexOfFirst { it.id == updated.id }
        if (idx >= 0) {
            _providers[idx] = updated
            rebuildAllModels()
        }
    }

    /**
     * Get a provider by ID.
     */
    fun getProvider(providerId: String): ProviderConfig? = _providers.find { it.id == providerId }

    /**
     * Find the provider that owns a given model ID.
     */
    fun findProviderForModel(modelId: String): ProviderConfig? {
        return _providers.find { p -> p.models.any { it.id == modelId } }
    }

    /**
     * Find a ModelInfo by model ID across all providers.
     */
    fun findModel(modelId: String): ModelInfo? = _allModels.find { it.id == modelId }

    /**
     * Sync models from a provider's API.
     * Tries GET /models first, then GET /v1/models as fallback.
     * Uses the provider's auth header type (Bearer or x-api-key).
     */
    suspend fun syncModels(provider: ProviderConfig): ProviderConfig {
        DebugLog.info("ProviderManager", "Syncing models for provider '${provider.name}' at ${provider.baseUrl}")

        val modelIds = try {
            fetchModelIds(provider)
        } catch (e: Exception) {
            DebugLog.error("ProviderManager", "Failed to sync models for '${provider.name}': ${e.message}")
            return provider // return unchanged on failure
        }

        DebugLog.info("ProviderManager", "Fetched ${modelIds.size} models from '${provider.name}': $modelIds")

        val categorized = ModelCategorizer.categorizeAll(modelIds, provider.id, provider.name)
        categorized.forEach { m ->
            DebugLog.info("ProviderManager", "  Model: ${m.id} -> size=${m.sizeTag.displayName}, cost=${m.costTag.displayName}")
        }

        return provider.copy(models = categorized)
    }

    /**
     * HTTP fetch of model IDs from a provider.
     * Tries /models endpoint first, falls back to /v1/models.
     */
    private suspend fun fetchModelIds(provider: ProviderConfig): List<String> {
        val authHeaders = provider.toApiHeaders()

        // Try /models first
        val endpoints = listOf(
            "${provider.baseUrl.trimEnd('/')}/models",
            "${provider.baseUrl.trimEnd('/')}/v1/models"
        )

        var lastError: Exception? = null
        for (endpoint in endpoints) {
            try {
                DebugLog.info("ProviderManager", "Trying endpoint: $endpoint")
                val request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(15))
                    .apply {
                        authHeaders.forEach { (k, v) -> header(k, v) }
                    }
                    .GET()
                    .build()

                val response = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
                if (response.statusCode() in 200..299) {
                    return parseModelIds(response.body())
                }
                DebugLog.warn("ProviderManager", "Endpoint $endpoint returned ${response.statusCode()}")
                lastError = Exception("HTTP ${response.statusCode()}: ${response.body().take(200)}")
            } catch (e: Exception) {
                DebugLog.warn("ProviderManager", "Endpoint $endpoint failed: ${e.message}")
                lastError = e
            }
        }

        throw lastError ?: Exception("Failed to fetch models from all endpoints")
    }

    /**
     * Parse model IDs from API response body.
     * Handles OpenAI-compatible format: { "data": [ { "id": "model-name" }, ... ] }
     */
    private fun parseModelIds(body: String): List<String> {
        val parsed = json.parseToJsonElement(body) as JsonObject
        val dataArray = parsed["data"]?.jsonArray
            ?: throw Exception("No 'data' field in models response")
        return dataArray.mapNotNull { item ->
            val obj = item.jsonObject
            obj["id"]?.jsonPrimitive?.content
        }
    }

    /**
     * Rebuild the flat list of all models from all providers.
     */
    private fun rebuildAllModels() {
        _allModels.clear()
        _providers.forEach { p ->
            _allModels.addAll(p.models)
        }
        DebugLog.info("ProviderManager", "Total models across all providers: ${_allModels.size}")
    }

    /**
     * Build a system prompt section listing all available models with their metadata.
     * This is injected into the agent's system prompt so the LLM knows what models are available
     * and can make routing decisions.
     */
    fun toSystemPromptSection(): String {
        // Only include enabled providers and their models
        val enabledProvs = _providers.filter { it.enabled }
        val enabledMdls = enabledProvs.flatMap { it.models }
        if (enabledMdls.isEmpty()) return ""

        val sb = StringBuilder()
        sb.append("\n<available_models>\n")
        sb.append("The following AI models are available for task routing. ")
        sb.append("Each model has a size tag (small, medium, large, XL) and a cost tag (free, low-cost, medium-cost, high-cost).\n")
        sb.append("When selecting a model for a task, weigh the task complexity against these tags:\n")
        sb.append("- SIMPLE tasks (formatting, listing, simple edits): prefer small/free or small/low-cost models\n")
        sb.append("- MEDIUM tasks (writing tests, explaining code, bug fixes): prefer medium/low-cost or medium/medium-cost models\n")
        sb.append("- COMPLEX tasks (refactoring, multi-file changes, architecture): prefer large/medium-cost or large/high-cost models\n")
        sb.append("- XL tasks (deep reasoning, system design, complex debugging): prefer XL/high-cost models\n\n")
        sb.append("Models:\n")

        // Group by provider for readability (only enabled providers)
        enabledProvs.forEach { provider ->
            if (provider.models.isNotEmpty()) {
                sb.append("  Provider: ${provider.name} (${provider.baseUrl})\n")
                provider.models.forEach { m ->
                    sb.append("    - ${m.id}: size=${m.sizeTag.displayName}, cost=${m.costTag.displayName}\n")
                }
            }
        }

        sb.append("</available_models>")
        return sb.toString()
    }

    /**
     * Clear all providers and models (for testing).
     */
    fun clear() {
        _providers.clear()
        _allModels.clear()
    }
}
