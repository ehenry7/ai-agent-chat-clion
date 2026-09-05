package com.aiagent.chat.net

import com.aiagent.chat.debug.DebugLog
import com.aiagent.chat.model.AuthHeaderType
import kotlinx.coroutines.future.await
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

/**
 * API pre-flight check: verifies endpoint reachability and model availability
 * before the agent loop starts.
 *
 * Inspired by refact-main's daemon health gate pattern.
 * Prevents wasted agent cycles when the API endpoint is down or the model
 * name is incorrect.
 *
 * Usage: Call [check] before starting the agent loop. If the check fails,
 * display the error to the user instead of starting a doomed agent run.
 */
class ApiPreFlightCheck(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val authHeaderType: AuthHeaderType = AuthHeaderType.BEARER
) {
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    data class PreFlightResult(
        val success: Boolean,
        val endpointReachable: Boolean,
        val modelAvailable: Boolean,
        val modelName: String = "",
        val endpointUrl: String = "",
        val availableModels: List<String> = emptyList(),
        val latencyMs: Long = 0,
        val error: String? = null
    ) {
        fun toDisplayString(): String = if (success) {
            "API pre-flight check passed. Endpoint reachable (${latencyMs}ms), model '$modelName' available."
        } else {
            buildString {
                appendLine("API pre-flight check FAILED:")
                if (!endpointReachable) {
                    appendLine("  - Endpoint $endpointUrl is not reachable")
                }
                if (endpointReachable && !modelAvailable) {
                    appendLine("  - Model '$modelName' not found in available models")
                    if (availableModels.isNotEmpty()) {
                        appendLine("  - Available models: ${availableModels.take(10).joinToString(", ")}")
                        if (availableModels.size > 10) appendLine("    ... and ${availableModels.size - 10} more")
                    }
                }
                error?.let { appendLine("  - Error: $it") }
            }.trimEnd()
        }
    }

    /**
     * Run the pre-flight check.
     * 1. Try to reach the /models endpoint
     * 2. Verify the configured model is in the list
     * 3. Measure latency
     */
    suspend fun check(): PreFlightResult {
        val startTime = System.currentTimeMillis()
        DebugLog.info("ApiPreFlightCheck", "Running pre-flight check: baseUrl=$baseUrl, model=$model")

        // Step 1: Check endpoint reachability
        val modelsResponse = try {
            val endpoint = "${baseUrl.trimEnd('/')}/models"
            val request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(10))
                .apply {
                    if (apiKey.isNotBlank()) {
                        when (authHeaderType) {
                            AuthHeaderType.BEARER -> header("Authorization", "Bearer $apiKey")
                            AuthHeaderType.X_API_KEY -> header("x-api-key", apiKey)
                        }
                    }
                }
                .GET()
                .build()

            val response = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
            val latency = System.currentTimeMillis() - startTime

            if (response.statusCode() !in 200..299) {
                DebugLog.warn("ApiPreFlightCheck", "Endpoint returned ${response.statusCode()}")
                return PreFlightResult(
                    success = false,
                    endpointReachable = false,
                    modelAvailable = false,
                    modelName = model,
                    endpointUrl = baseUrl,
                    latencyMs = latency,
                    error = "HTTP ${response.statusCode()}: ${response.body().take(200)}"
                )
            }

            Pair(response.body(), latency)
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - startTime
            DebugLog.warn("ApiPreFlightCheck", "Endpoint unreachable: ${e.message}")
            return PreFlightResult(
                success = false,
                endpointReachable = false,
                modelAvailable = false,
                modelName = model,
                endpointUrl = baseUrl,
                latencyMs = latency,
                error = e.message ?: e::class.simpleName
            )
        }

        val (body, latency) = modelsResponse

        // Step 2: Parse available models
        val availableModels = try {
            val jsonParser = Json { ignoreUnknownKeys = true }
            val parsed = jsonParser.parseToJsonElement(body) as JsonObject
            val dataArray = parsed["data"]?.jsonArray ?: return PreFlightResult(
                success = false,
                endpointReachable = true,
                modelAvailable = false,
                modelName = model,
                endpointUrl = baseUrl,
                latencyMs = latency,
                error = "No 'data' field in models response"
            )
            dataArray.mapNotNull { item ->
                val obj = item.jsonObject
                obj["id"]?.jsonPrimitive?.content
            }
        } catch (e: Exception) {
            DebugLog.warn("ApiPreFlightCheck", "Failed to parse models response: ${e.message}")
            // Endpoint is reachable but response format is unexpected
            // Don't fail hard - some providers don't have /models endpoint
            return PreFlightResult(
                success = true,
                endpointReachable = true,
                modelAvailable = true, // Assume available since we can't verify
                modelName = model,
                endpointUrl = baseUrl,
                latencyMs = latency,
                error = "Could not verify model list (parsing failed), proceeding anyway"
            )
        }

        DebugLog.info("ApiPreFlightCheck", "Found ${availableModels.size} models, checking for '$model'")

        // Step 3: Verify model is available
        val modelFound = availableModels.any { it.equals(model, ignoreCase = true) }

        return if (modelFound) {
            DebugLog.info("ApiPreFlightCheck", "Pre-flight check passed (${latency}ms)")
            PreFlightResult(
                success = true,
                endpointReachable = true,
                modelAvailable = true,
                modelName = model,
                endpointUrl = baseUrl,
                availableModels = availableModels,
                latencyMs = latency
            )
        } else {
            DebugLog.warn("ApiPreFlightCheck", "Model '$model' not found in ${availableModels.size} available models")
            PreFlightResult(
                success = false,
                endpointReachable = true,
                modelAvailable = false,
                modelName = model,
                endpointUrl = baseUrl,
                availableModels = availableModels,
                latencyMs = latency,
                error = "Model '$model' not found in available models"
            )
        }
    }
}
