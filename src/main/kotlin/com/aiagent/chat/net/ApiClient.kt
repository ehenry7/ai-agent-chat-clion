package com.aiagent.chat.net

import com.aiagent.chat.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.io.IOException

class ApiClient(
    val baseUrl: String = "http://techdev.hicomputing.huawei.com:18000",
    val apiKey: String = "",
    val model: String = "GLM-5.2-1",
    val maxAttempts: Int = 3,
    val retryDelayMs: Long = 1500,
    val onRetry: ((attempt: Int, error: String, delay: Long) -> Unit)? = null
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    private fun isRetriableError(e: Throwable, statusCode: Int?): Boolean {
        if (e is CancellationException) return false
        if (statusCode in listOf(408, 429, 502, 503, 504)) return true
        return e is IOException || e.message?.contains("timed out", ignoreCase = true) == true
    }

    suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>? = null
    ): ChatMessage = coroutineScope {
        var lastError: Throwable? = null
        for (attempt in 1..maxAttempts) {
            try {
                return@coroutineScope chatOnce(messages, tools)
            } catch (e: Throwable) {
                lastError = e
                val statusCode = (e as? ApiException)?.statusCode
                if (!isRetriableError(e, statusCode) || attempt >= maxAttempts) {
                    throw e
                }
                val delay = retryDelayMs * attempt
                onRetry?.invoke(attempt, e.message ?: "Unknown error", delay)
                delay(delay)
            }
        }
        throw lastError ?: RuntimeException("Network call failed")
    }

    private suspend fun chatOnce(messages: List<ChatMessage>, tools: List<ToolDefinition>?): ChatMessage {
        val endpoint = URI.create("${baseUrl.trimEnd('/')}/chat/completions")
        val reqPayload = ChatCompletionRequest(model = model, messages = messages, tools = tools)
        val bodyStr = json.encodeToString(reqPayload)

        val request = HttpRequest.newBuilder()
            .uri(endpoint)
            .timeout(Duration.ofSeconds(120))
            .header("Content-Type", "application/json")
            .apply {
                if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
            }
            .POST(HttpRequest.BodyPublishers.ofString(bodyStr))
            .build()

        val response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        val status = response.statusCode()
        val respBody = response.body()

        if (status !in 200..299) {
            throw ApiException(status, "API error $status: $respBody")
        }

        val parsed = json.decodeFromString<ChatCompletionResponse>(respBody)
        return parsed.choices.firstOrNull()?.message
            ?: throw ApiException(status, "Malformed API response: no choices returned")
    }

    suspend fun listModels(): List<String> {
        val endpoint = URI.create("${baseUrl.trimEnd('/')}/models")
        val request = HttpRequest.newBuilder()
            .uri(endpoint)
            .timeout(Duration.ofSeconds(15))
            .apply {
                if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
            }
            .GET()
            .build()

        val response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        if (response.statusCode() !in 200..299) {
            throw ApiException(response.statusCode(), "Failed to fetch models: ${response.body()}")
        }
        val parsed = json.decodeFromString<ModelsListResponse>(response.body())
        return parsed.data.map { it.id }
    }
}

class ApiException(val statusCode: Int, message: String) : RuntimeException(message)
