package com.aiagent.chat.net

import com.aiagent.chat.debug.DebugLog
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
    val authHeaderType: AuthHeaderType = AuthHeaderType.BEARER,
    val maxOutputTokens: Int? = null,
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
            DebugLog.info("ApiClient", "Rate limiting: sleeping ${minIntervalMs - elapsed}ms")
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
        if (e is ContextLimitException) return false
        if (statusCode in listOf(408, 429, 502, 503, 504)) return true
        return e is IOException || e.message?.contains("timed out", ignoreCase = true) == true
    }

    /**
     * Check if an error response indicates a context-limit / token-limit error.
     * Returns true for HTTP 413 or error messages containing context-length indicators.
     */
    private fun isContextLimitError(statusCode: Int, body: String): Boolean {
        if (statusCode == 413) return true
        val lower = body.lowercase()
        return lower.contains("context_length_exceeded") ||
               lower.contains("maximum context length") ||
               lower.contains("token limit exceeded") ||
               lower.contains("context window")
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
                val errorDesc = e.message ?: e::class.simpleName ?: "Unknown error"
                if (!isRetriableError(e, statusCode) || attempt >= maxAttempts) {
                    DebugLog.error("ApiClient", "chat failed (non-retriable or max attempts): $errorDesc")
                    throw e
                }
                val delay = retryDelayMs * attempt
                DebugLog.warn("ApiClient", "chat failed (attempt $attempt/$maxAttempts), retrying in ${delay}ms: $errorDesc")
                onRetry?.invoke(attempt, errorDesc, delay)
                delay(delay)
            }
        }
        val finalError = lastError
        if (finalError != null) {
            throw finalError
        }
        throw RuntimeException("Network call failed")
    }

    private suspend fun chatOnce(messages: List<ChatMessage>, tools: List<ToolDefinition>?): ChatMessage {
        enforceRateLimit()
        val endpoint = URI.create("${baseUrl.trimEnd('/')}/chat/completions")
        val reqPayload = ChatCompletionRequest(
            model = model, messages = messages, tools = tools,
            maxCompletionTokens = maxOutputTokens
        )
        val bodyStr = json.encodeToString(reqPayload)
        DebugLog.info("ApiClient", "=== NON-STREAMING REQUEST ===")
        DebugLog.info("ApiClient", "URL: $endpoint")
        DebugLog.info("ApiClient", "Model: $model")
        DebugLog.info("ApiClient", "Messages (${messages.size}): ${messages.take(3).joinToString { "${it.role}: ${it.content?.take(100) ?: "[tool_calls]"}" }.take(500)}")
        DebugLog.info("ApiClient", "Request body: ${bodyStr.take(1000)}")

        val request = HttpRequest.newBuilder()
            .uri(endpoint)
            .timeout(Duration.ofSeconds(120))
            .header("Content-Type", "application/json")
            .apply {
                if (apiKey.isNotBlank()) {
                    when (authHeaderType) {
                        AuthHeaderType.BEARER -> header("Authorization", "Bearer $apiKey")
                        AuthHeaderType.X_API_KEY -> header("x-api-key", apiKey)
                    }
                }
            }
            .POST(HttpRequest.BodyPublishers.ofString(bodyStr))
            .build()

        DebugLog.info("ApiClient", "Sending HTTP request...")
        val response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        val status = response.statusCode()
        val respBody = response.body()

        DebugLog.info("ApiClient", "=== NON-STREAMING RESPONSE ===")
        DebugLog.info("ApiClient", "Status: $status")
        DebugLog.info("ApiClient", "Response body: ${respBody.take(2000)}")

        if (status !in 200..299) {
            if (isContextLimitError(status, respBody)) {
                DebugLog.warn("ApiClient", "Context limit error detected (status=$status)")
                throw ContextLimitException(status, "Context limit exceeded. Response: ${respBody.take(500)}")
            }
            val errorMsg = "API error $status: ${sanitizeForLog(respBody)}"
            DebugLog.error("ApiClient", errorMsg)
            throw ApiException(status, errorMsg)
        }

        val parsed = json.decodeFromString<ChatCompletionResponse>(respBody)
        DebugLog.info("ApiClient", "Non-streaming response received, ${parsed.choices.size} choices")
        val message = parsed.choices.firstOrNull()?.message
            ?: throw ApiException(status, "Malformed API response: no choices returned")
        // Attach usage data if present
        if (parsed.usage != null) {
            DebugLog.info("ApiClient", "Usage: prompt=${parsed.usage.promptTokens}, completion=${parsed.usage.completionTokens}, total=${parsed.usage.totalTokens}")
            return message.copy(usage = parsed.usage)
        }
        return message
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
        onChunk: (StreamChunk) -> Unit,
        isAborted: (() -> Boolean)? = null
    ): ChatMessage = coroutineScope {
        var lastError: Throwable? = null
        for (attempt in 1..maxAttempts) {
            try {
                return@coroutineScope chatStreamOnce(messages, tools, onChunk, isAborted)
            } catch (e: Throwable) {
                lastError = e
                val statusCode = (e as? ApiException)?.statusCode ?: (e as? ContextLimitException)?.statusCode
                val errorDesc = e.message ?: e::class.simpleName ?: "Unknown error"
                if (!isRetriableError(e, statusCode) || attempt >= maxAttempts) {
                    DebugLog.error("ApiClient", "chatStream failed (non-retriable or max attempts): $errorDesc")
                    throw e
                }
                val delay = retryDelayMs * attempt
                DebugLog.warn("ApiClient", "chatStream failed (attempt $attempt/$maxAttempts), retrying in ${delay}ms: $errorDesc")
                onRetry?.invoke(attempt, errorDesc, delay)
                delay(delay)
            }
        }
        val finalError = lastError
        if (finalError != null) {
            throw finalError
        }
        throw RuntimeException("Network call failed")
    }

    private suspend fun chatStreamOnce(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>?,
        onChunk: (StreamChunk) -> Unit,
        isAborted: (() -> Boolean)? = null
    ): ChatMessage {
        enforceRateLimit()
        val endpoint = URI.create("${baseUrl.trimEnd('/')}/chat/completions")
        val reqPayload = ChatCompletionRequest(
            model = model, messages = messages, tools = tools, stream = true,
            maxCompletionTokens = maxOutputTokens
        )
        val bodyStr = json.encodeToString(reqPayload)
        DebugLog.info("ApiClient", "=== STREAMING REQUEST ===")
        DebugLog.info("ApiClient", "URL: $endpoint")
        DebugLog.info("ApiClient", "Model: $model")
        DebugLog.info("ApiClient", "Messages (${messages.size}): ${messages.take(3).joinToString { "${it.role}: ${it.content?.take(100) ?: "[tool_calls]"}" }.take(500)}")
        DebugLog.info("ApiClient", "Tools: ${tools?.size ?: 0}")
        DebugLog.info("ApiClient", "Request body: ${bodyStr.take(1000)}")

        val request = HttpRequest.newBuilder()
            .uri(endpoint)
            .timeout(Duration.ofSeconds(120))
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .apply {
                if (apiKey.isNotBlank()) {
                    when (authHeaderType) {
                        AuthHeaderType.BEARER -> header("Authorization", "Bearer $apiKey")
                        AuthHeaderType.X_API_KEY -> header("x-api-key", apiKey)
                    }
                }
            }
            .POST(HttpRequest.BodyPublishers.ofString(bodyStr))
            .build()

        DebugLog.info("ApiClient", "Sending HTTP request, awaiting SSE stream...")
        val response = client.sendAsync(request, HttpResponse.BodyHandlers.ofLines()).await()
        val status = response.statusCode()
        DebugLog.info("ApiClient", "=== STREAMING RESPONSE ===")
        DebugLog.info("ApiClient", "Status: $status")

        if (status !in 200..299) {
            val body = response.body().map { it }.toList().joinToString("")
            if (isContextLimitError(status, body)) {
                DebugLog.warn("ApiClient", "Context limit error in stream (status=$status)")
                throw ContextLimitException(status, "Context limit exceeded. Response: ${body.take(500)}")
            }
            DebugLog.error("ApiClient", "Non-2xx response: $status, body: ${body.take(1000)}")
            val errorMsg = "API error $status: ${sanitizeForLog(body)}"
            DebugLog.error("ApiClient", errorMsg)
            throw ApiException(status, errorMsg)
        }

        DebugLog.info("ApiClient", "SSE stream opened, processing chunks...")
        val contentBuilder = StringBuilder()
        val reasoningBuilder = StringBuilder()
        val toolCallBuilders = mutableMapOf<Int, StringBuilder>()
        val toolCallIds = mutableMapOf<Int, String>()
        val toolCallNames = mutableMapOf<Int, String>()
        var finishReason: String? = null
        var chunkCount = 0
        val allLines = mutableListOf<String>()

        // --- Thinking tag parser state ---
        // Tracks whether we're currently inside a </think>...</think> tag so we can route
        // content to StreamChunk.Reasoning instead of StreamChunk.Content.
        var insideThinking = false
        val thinkingOpenTag = "<thinking>"
        val thinkingCloseTag = "</thinking>"
        val pendingBuffer = StringBuilder() // buffer for partial tag detection

        response.body()
            .filter { it.startsWith("data: ") }
            .forEach { line ->
                // --- Abort check between SSE lines ---
                if (isAborted != null && isAborted()) {
                    DebugLog.info("ApiClient", "Abort detected during SSE streaming, stopping")
                    throw CancellationException("Aborted during streaming")
                }

                allLines.add(line)
                val data = line.removePrefix("data: ").trim()
                if (data == "[DONE]") {
                    DebugLog.info("ApiClient", "SSE stream received [DONE]")
                    return@forEach
                }

                try {
                    val chunk = json.decodeFromString<ChatCompletionChunk>(data)
                    val choice = chunk.choices.firstOrNull() ?: return@forEach
                    val delta = choice.delta
                    chunkCount++

                    if (delta != null) {
                        if (!delta.content.isNullOrEmpty()) {
                            val rawContent = delta.content
                            // --- Thinking tag parsing ---
                            // We need to detect </think>...</think> tags that may span multiple chunks.
                            // Strategy: buffer content, scan for tags, emit Content or Reasoning accordingly.
                            pendingBuffer.append(rawContent)
                            val processed = processThinkingTags(pendingBuffer, insideThinking, onChunk)
                            insideThinking = processed.stillInsideThinking
                        }

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
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DebugLog.warn("ApiClient", "Failed to parse SSE chunk: ${e.message}, raw: ${data.take(100)}")
                }
            }

        // --- Flush any remaining buffered content ---
        if (pendingBuffer.isNotEmpty()) {
            val remaining = pendingBuffer.toString()
            if (insideThinking) {
                reasoningBuilder.append(remaining)
                onChunk(StreamChunk.Reasoning(remaining))
            } else {
                contentBuilder.append(remaining)
                onChunk(StreamChunk.Content(remaining))
            }
            pendingBuffer.clear()
        }

        DebugLog.info("ApiClient", "=== STREAM COMPLETE ===")
        DebugLog.info("ApiClient", "Total SSE lines received: ${allLines.size}")
        DebugLog.info("ApiClient", "Total chunks parsed: $chunkCount")
        DebugLog.info("ApiClient", "Content length: ${contentBuilder.length}")
        DebugLog.info("ApiClient", "Reasoning length: ${reasoningBuilder.length}")
        DebugLog.info("ApiClient", "Content preview: ${contentBuilder.toString().take(500)}")
        DebugLog.info("ApiClient", "Finish reason: $finishReason")
        val assembledToolCalls = if (toolCallBuilders.isNotEmpty()) {
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
        DebugLog.info("ApiClient", "Tool calls: ${assembledToolCalls?.size ?: 0}")
        assembledToolCalls?.forEachIndexed { idx, tc ->
            DebugLog.info("ApiClient", "  ToolCall[$idx]: ${tc.function.name}(${tc.function.arguments.take(100)})")
        }

        // Strip thinking tags from the final content (they've been routed to reasoning separately)
        val finalContent = contentBuilder.toString()
            .replace(Regex("<thinking>[\\s\\S]*?</thinking>", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifEmpty { null }

        return ChatMessage(
            role = MessageRole.ASSISTANT,
            content = finalContent,
            toolCalls = assembledToolCalls
        )
    }

    /**
     * Process thinking tags in the pending buffer.
     * Extracts content between </think>...</think> tags and emits Reasoning chunks.
     * Emits Content chunks for text outside thinking tags.
     * Returns whether we're still inside a thinking tag after processing.
     */
    private fun processThinkingTags(
        buffer: StringBuilder,
        currentlyInside: Boolean,
        onChunk: (StreamChunk) -> Unit
    ): TagProcessResult {
        var inside = currentlyInside
        var text = buffer.toString()
        var consumed = 0

        while (text.isNotEmpty()) {
            if (inside) {
                // Look for closing tag
                val closeIdx = text.indexOf("</thinking>", ignoreCase = true)
                if (closeIdx == -1) {
                    // No closing tag found yet — but we might have a partial tag at the end
                    // Check if the end of the buffer could be the start of </thinking>
                    val partialCloseLen = findPartialTagMatch(text, "</thinking>")
                    if (partialCloseLen > 0) {
                        // Emit everything except the partial tag as reasoning
                        val safeEnd = text.length - partialCloseLen
                        if (safeEnd > 0) {
                            onChunk(StreamChunk.Reasoning(text.substring(0, safeEnd)))
                        }
                        // Keep the partial tag in the buffer
                        buffer.setLength(0)
                        buffer.append(text.substring(safeEnd))
                        consumed += safeEnd
                        return TagProcessResult(true)
                    } else {
                        // No partial tag, emit all as reasoning
                        onChunk(StreamChunk.Reasoning(text))
                        buffer.setLength(0)
                        return TagProcessResult(true)
                    }
                } else {
                    // Found closing tag — emit reasoning content before it
                    val reasoningContent = text.substring(0, closeIdx)
                    if (reasoningContent.isNotEmpty()) {
                        onChunk(StreamChunk.Reasoning(reasoningContent))
                    }
                    // Skip past the closing tag
                    val afterClose = closeIdx + "</thinking>".length
                    text = text.substring(afterClose)
                    consumed += afterClose
                    inside = false
                }
            } else {
                // Look for opening tag
                val openIdx = text.indexOf("<thinking>", ignoreCase = true)
                if (openIdx == -1) {
                    // No opening tag — check for partial tag at end
                    val partialOpenLen = findPartialTagMatch(text, "<thinking>")
                    if (partialOpenLen > 0) {
                        val safeEnd = text.length - partialOpenLen
                        if (safeEnd > 0) {
                            onChunk(StreamChunk.Content(text.substring(0, safeEnd)))
                        }
                        buffer.setLength(0)
                        buffer.append(text.substring(safeEnd))
                        consumed += safeEnd
                        return TagProcessResult(false)
                    } else {
                        onChunk(StreamChunk.Content(text))
                        buffer.setLength(0)
                        return TagProcessResult(false)
                    }
                } else {
                    // Found opening tag — emit content before it
                    val beforeTag = text.substring(0, openIdx)
                    if (beforeTag.isNotEmpty()) {
                        onChunk(StreamChunk.Content(beforeTag))
                    }
                    // Skip past the opening tag
                    val afterOpen = openIdx + "<thinking>".length
                    text = text.substring(afterOpen)
                    consumed += afterOpen
                    inside = true
                }
            }
        }

        buffer.setLength(0)
        return TagProcessResult(inside)
    }

    /**
     * Check if the end of the text could be the beginning of a tag.
     * Returns the length of the partial match, or 0 if no partial match.
     */
    private fun findPartialTagMatch(text: String, tag: String): Int {
        val maxPartial = minOf(tag.length - 1, text.length)
        for (len in maxPartial downTo 1) {
            if (text.endsWith(tag.substring(0, len), ignoreCase = true)) {
                return len
            }
        }
        return 0
    }

    private data class TagProcessResult(val stillInsideThinking: Boolean)

    suspend fun listModels(): List<String> {
        val endpoint = URI.create("${baseUrl.trimEnd('/')}/models")
        val request = HttpRequest.newBuilder()
            .uri(endpoint)
            .timeout(Duration.ofSeconds(15))
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
    /** A reasoning/thinking text delta (from <think> tags). */
    data class Reasoning(val text: String) : StreamChunk()
    /** A tool call argument delta (partial JSON arguments). */
    data class ToolCallDelta(val toolName: String, val argumentsDelta: String) : StreamChunk()
}

class ApiException(val statusCode: Int, message: String) : RuntimeException(message)

/**
 * Thrown when the API returns a context-limit error (HTTP 413 or error message indicating context length exceeded).
 * The agent engine should catch this and trigger context compaction before retrying.
 */
class ContextLimitException(val statusCode: Int, message: String) : RuntimeException(message)
