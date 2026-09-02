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

    // S3: Simple rate limiter — min interval between API calls
    private var lastCallTimeMs: Long = 0L
    private val minIntervalMs: Long = 100L // 100ms = max 10 req/s

    private fun enforceRateLimit() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastCallTimeMs
        if (elapsed < minIntervalMs) {
            Thread.sleep(minIntervalMs - elapsed)
        }
        lastCallTimeMs = System.currentTimeMillis()
    }

    // S1: Sanitize API key — never log or expose in error messages
    private fun sanitizeForLog(text: String): String {
        return text.replace(Regex("(Bearer\\s+)[A-Za-z0-9_\\-]+"), "$1***")
            .replace(Regex("(\"api[_-]?key\"\\s*:\\s*\")[^\"]+"), "$1***\"")
    }

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
        enforceRateLimit()
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
            throw ApiException(status, "API error $status: ${sanitizeForLog(respBody)}")
        }

        val parsed = json.decodeFromString<ChatCompletionResponse>(respBody)
        return parsed.choices.firstOrNull()?.message
            ?: throw ApiException(status, "Malformed API response: no choices returned")
    }

    /**
     * Streaming chat completion using SSE (Server-Sent Events).
     * Calls onChunk for each delta chunk received from the server.
     * Returns the fully assembled ChatMessage.
     *
     * Phase 9: SSE Streaming Support.
     */
    suspend fun chatStream(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>? = null,
        onChunk: (StreamChunk) -> Unit
    ): ChatMessage = coroutineScope {
        var lastError: Throwable? = null
        for (attempt in 1..maxAttempts) {
            try {
                return@coroutineScope chatStreamOnce(messages, tools, onChunk)
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

    private suspend fun chatStreamOnce(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>?,
        onChunk: (StreamChunk) -> Unit
    ): ChatMessage {
        enforceRateLimit()
        val endpoint = URI.create("${baseUrl.trimEnd('/')}/chat/completions")
        val reqPayload = ChatCompletionRequest(model = model, messages = messages, tools = tools, stream = true)
        val bodyStr = json.encodeToString(reqPayload)

        val request = HttpRequest.newBuilder()
            .uri(endpoint)
            .timeout(Duration.ofSeconds(120))
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .apply {
                if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
            }
            .POST(HttpRequest.BodyPublishers.ofString(bodyStr))
            .build()

        // Use BodyHandlers.ofLines for SSE streaming
        val response = client.sendAsync(request, HttpResponse.BodyHandlers.ofLines()).await()
        val status = response.statusCode()

        if (status !in 200..299) {
            val body = response.body().map { it }.toList().joinToString("")
            throw ApiException(status, "API error $status: ${sanitizeForLog(body)}")
        }

        // Accumulate the streamed response
        val contentBuilder = StringBuilder()
        val toolCallBuilders = mutableMapOf<Int, StringBuilder>() // index -> arguments
        val toolCallIds = mutableMapOf<Int, String>()
        val toolCallNames = mutableMapOf<Int, String>()
        var finishReason: String? = null

        response.body()
            .filter { it.startsWith("data: ") }
            .forEach { line ->
                val data = line.removePrefix("data: ").trim()
                if (data == "[DONE]") return@forEach

                try {
                    val chunk = json.decodeFromString<ChatCompletionChunk>(data)
                    val choice = chunk.choices.firstOrNull() ?: return@forEach
                    val delta = choice.delta

                    if (delta != null) {
                        // Content delta
                        if (!delta.content.isNullOrEmpty()) {
                            contentBuilder.append(delta.content)
                            onChunk(StreamChunk.Content(delta.content))
                        }

                        // Tool call deltas
                        if (!delta.toolCalls.isNullOrEmpty()) {
                            for (tc in delta.toolCalls) {
                                val idx = tc.index
                                tc.id?.let { toolCallIds[idx] = it }
                                tc.function?.name?.let { toolCallNames[idx] = it }
                                tc.function?.arguments?.let { args ->
                                    toolCallBuilders.getOrPut(idx) { StringBuilder() }.append(args)
                                    onChunk(StreamChunk.ToolCallDelta(toolCallNames[idx] ?: "", args))
                                }
                            }
                        }
                    }

                    if (choice.finishReason != null) {
                        finishReason = choice.finishReason
                    }
                } catch (_: Exception) {
                    // Skip malformed chunks
                }
            }

        // Build the final ChatMessage
        val toolCalls = if (toolCallBuilders.isNotEmpty()) {
            toolCallBuilders.entries.sortedBy { it.key }.map { (idx, args) ->
                ToolCall(
                    id = toolCallIds[idx] ?: "call_$idx",
                    type = "function",
                    function = FunctionCall(
                        name = toolCallNames[idx] ?: "",
                        arguments = args.toString()
                    )
                )
            }
        } else null

        return ChatMessage(
            role = MessageRole.ASSISTANT,
            content = contentBuilder.toString().ifEmpty { null },
            toolCalls = toolCalls
        )
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

/**
 * Sealed type for streaming chunks emitted during SSE streaming.
 */
sealed class StreamChunk {
    /** A content text delta from the assistant. */
    data class Content(val text: String) : StreamChunk()
    /** A tool call argument delta (partial JSON arguments). */
    data class ToolCallDelta(val toolName: String, val argumentsDelta: String) : StreamChunk()
}

class ApiException(val statusCode: Int, message: String) : RuntimeException(message)
