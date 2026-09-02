package com.aiagent.chat.agent

import com.aiagent.chat.model.*
import com.aiagent.chat.net.ApiClient
import com.aiagent.chat.net.StreamChunk
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

sealed interface AgentDelta {
    data class Status(val text: String) : AgentDelta
    data class Assistant(val text: String) : AgentDelta
    data class ToolOutput(val name: String, val text: String) : AgentDelta
    /** Incremental streaming content — emitted token-by-token during SSE streaming. */
    data class StreamingContent(val text: String) : AgentDelta
    /** Signals the start of a streaming response. */
    data class StreamingStart(val text: String = "") : AgentDelta
    /** Signals the end of a streaming response, with the full accumulated text. */
    data class StreamingEnd(val fullText: String) : AgentDelta
}

class AgentEngine(
    private val client: ApiClient,
    private val toolExecutor: suspend (name: String, args: JsonObject) -> String,
    private val onDelta: (AgentDelta) -> Unit
) {
    companion object {
        const val RECENT_WINDOW_MESSAGES = 8
        const val TOOL_COMPRESS_THRESHOLD = 2000
        const val COMPRESSED_TOOL_NOTICE = "[Tool executed successfully. Output compressed for memory preservation.]"
    }

    private val mutatingTools = setOf(
        "write_file", "edit_file", "apply_patch", "apply_diff",
        "search_replace", "delete_file", "delete_directory",
        "rename_file", "create_directory", "run_command", "run_python", "git_commit"
    )

    fun applySemanticSlidingWindow(messages: List<ChatMessage>): List<ChatMessage> {
        val n = messages.size
        val protectedStart = (n - RECENT_WINDOW_MESSAGES).coerceAtLeast(1)

        return messages.mapIndexed { i, m ->
            if (i == 0 || i >= protectedStart) {
                m
            } else if (m.role == MessageRole.TOOL && (m.content?.length ?: 0) > TOOL_COMPRESS_THRESHOLD) {
                m.copy(content = COMPRESSED_TOOL_NOTICE)
            } else {
                m
            }
        }
    }

    /**
     * Streaming variant of runAgentLoop.
     * Uses SSE streaming to receive tokens incrementally, emitting AgentDelta.StreamingContent
     * for each chunk so the UI can render text as it arrives.
     *
     * Phase 9: SSE Streaming Support.
     */
    suspend fun runAgentLoopStreaming(
        initialHistory: List<ChatMessage>,
        userMessage: ChatMessage,
        availableTools: List<ToolDefinition>,
        maxSteps: Int = 25,
        memory: String = "",
        globalMemory: String = "",
        initialPhase: String = "discovery",
        onPhaseChange: ((String) -> Unit)? = null,
        steerProvider: (() -> String?)? = null
    ): List<ChatMessage> = coroutineScope {
        var currentPhase = initialPhase
        var currentPlan: String? = null
        val messages = mutableListOf<ChatMessage>()
        val newMessages = mutableListOf<ChatMessage>()
        var emptyRetries = 0

        messages.add(ChatMessage(MessageRole.SYSTEM, content = ""))
        messages.addAll(initialHistory)
        messages.add(userMessage)
        newMessages.add(userMessage)

        for (step in 0 until maxSteps) {
            ensureActive()

            // Inject steering messages
            val steerText = steerProvider?.invoke()
            if (steerText != null && steerText.isNotBlank()) {
                val steerMsg = ChatMessage(MessageRole.USER, steerText)
                messages.add(steerMsg)
                newMessages.add(steerMsg)
            }

            onDelta(AgentDelta.Status("[step ${step + 1}/$maxSteps]"))

            val activeTools = if (currentPhase == "execution") {
                availableTools
            } else {
                availableTools.filterNot { mutatingTools.contains(it.function.name) }
            }

            messages[0] = ChatMessage(
                MessageRole.SYSTEM,
                content = buildSystemPrompt(activeTools.map { it.function.name }, memory, globalMemory, currentPhase)
            )

            val ephemeral = mutableListOf<ChatMessage>()
            currentPlan?.let {
                ephemeral.add(ChatMessage(MessageRole.SYSTEM, "Current Plan State:\n$it"))
            }

            val compressed = applySemanticSlidingWindow(messages)
            val messagesForApi = compressed + ephemeral

            // Stream the response
            onDelta(AgentDelta.StreamingStart())

            val assistantResponse = client.chatStream(
                messages = messagesForApi,
                tools = activeTools,
                onChunk = { chunk ->
                    when (chunk) {
                        is StreamChunk.Content -> {
                            onDelta(AgentDelta.StreamingContent(chunk.text))
                        }
                        is StreamChunk.ToolCallDelta -> {
                            // Could emit tool call progress here if desired
                        }
                    }
                }
            )

            onDelta(AgentDelta.StreamingEnd(assistantResponse.content ?: ""))
            newMessages.add(assistantResponse)

            // Extract plan from content
            assistantResponse.content?.let { content ->
                val planMatch = Regex("<plan>([\\s\\S]*?)</plan>", RegexOption.IGNORE_CASE).find(content)
                if (planMatch != null) {
                    currentPlan = planMatch.groupValues[1].trim()
                }
            }

            val calls = assistantResponse.toolCalls
            if (!calls.isNullOrEmpty()) {
                val textContent = assistantResponse.content
                if (!textContent.isNullOrBlank()) {
                    onDelta(AgentDelta.Assistant(textContent))
                }
                messages.add(assistantResponse)

                for (call in calls) {
                    ensureActive()
                    val funcName = call.function.name
                    val rawArgs = call.function.arguments

                    val parsedArgs = try {
                        Json.parseToJsonElement(rawArgs).jsonObject
                    } catch (e: Exception) {
                        JsonObject(emptyMap())
                    }

                    if (funcName == "request_phase_change") {
                        val target = parsedArgs["target_phase"]?.jsonPrimitive?.content ?: "discovery"
                        currentPhase = if (target == "execution") "execution" else "discovery"
                        val result = "Phase changed to '$currentPhase'."
                        onPhaseChange?.invoke(currentPhase)

                        onDelta(AgentDelta.ToolOutput(funcName, result))
                        val toolMsg = ChatMessage(MessageRole.TOOL, content = result, toolCallId = call.id)
                        messages.add(toolMsg)
                        newMessages.add(toolMsg)
                        continue
                    }

                    val toolResult = try {
                        toolExecutor(funcName, parsedArgs)
                    } catch (e: Exception) {
                        "Error executing tool $funcName: ${e.message}"
                    }

                    onDelta(AgentDelta.ToolOutput(funcName, toolResult))
                    val toolMsg = ChatMessage(MessageRole.TOOL, content = toolResult, toolCallId = call.id)
                    messages.add(toolMsg)
                    newMessages.add(toolMsg)
                }
                continue
            }

            if (!assistantResponse.content.isNullOrBlank()) {
                messages.add(assistantResponse)
                // Content was already streamed; no need to emit Assistant delta again
                break
            } else {
                emptyRetries++
                if (emptyRetries <= 3) {
                    val nudge = ChatMessage(MessageRole.USER, "You returned an empty response with no text and no tool calls. Please continue your task.")
                    messages.add(nudge)
                    newMessages.add(nudge)
                    onDelta(AgentDelta.Status("[empty response — retrying $emptyRetries/3]"))
                    continue
                }
                break
            }
        }
        newMessages
    }

    suspend fun runAgentLoop(
        initialHistory: List<ChatMessage>,
        userMessage: ChatMessage,
        availableTools: List<ToolDefinition>,
        maxSteps: Int = 25,
        memory: String = "",
        globalMemory: String = "",
        initialPhase: String = "discovery",
        onPhaseChange: ((String) -> Unit)? = null,
        steerProvider: (() -> String?)? = null
    ): List<ChatMessage> = coroutineScope {
        var currentPhase = initialPhase
        var currentPlan: String? = null
        val messages = mutableListOf<ChatMessage>()
        val newMessages = mutableListOf<ChatMessage>()
        var emptyRetries = 0

        messages.add(ChatMessage(MessageRole.SYSTEM, content = ""))
        messages.addAll(initialHistory)
        messages.add(userMessage)
        newMessages.add(userMessage)

        val currentMaxSteps = maxSteps

        for (step in 0 until currentMaxSteps) {
            ensureActive()

            // Inject steering messages from the user if any are pending
            val steerText = steerProvider?.invoke()
            if (steerText != null && steerText.isNotBlank()) {
                val steerMsg = ChatMessage(MessageRole.USER, steerText)
                messages.add(steerMsg)
                newMessages.add(steerMsg)
            }

            onDelta(AgentDelta.Status("[step ${step + 1}/$currentMaxSteps]"))

            val activeTools = if (currentPhase == "execution") {
                availableTools
            } else {
                availableTools.filterNot { mutatingTools.contains(it.function.name) }
            }

            messages[0] = ChatMessage(
                MessageRole.SYSTEM,
                content = buildSystemPrompt(activeTools.map { it.function.name }, memory, globalMemory, currentPhase)
            )

            val ephemeral = mutableListOf<ChatMessage>()
            currentPlan?.let {
                ephemeral.add(ChatMessage(MessageRole.SYSTEM, "Current Plan State:\n$it"))
            }

            val compressed = applySemanticSlidingWindow(messages)
            val messagesForApi = compressed + ephemeral

            val assistantResponse = client.chat(messagesForApi, activeTools)
            newMessages.add(assistantResponse)

            assistantResponse.content?.let { content ->
                val planMatch = Regex("<plan>([\\s\\S]*?)</plan>", RegexOption.IGNORE_CASE).find(content)
                if (planMatch != null) {
                    currentPlan = planMatch.groupValues[1].trim()
                }
            }

            val calls = assistantResponse.toolCalls
            if (!calls.isNullOrEmpty()) {
                val textContent = assistantResponse.content
                if (!textContent.isNullOrBlank()) {
                    onDelta(AgentDelta.Assistant(textContent))
                }
                messages.add(assistantResponse)

                for (call in calls) {
                    ensureActive()
                    val funcName = call.function.name
                    val rawArgs = call.function.arguments

                    val parsedArgs = try {
                        Json.parseToJsonElement(rawArgs).jsonObject
                    } catch (e: Exception) {
                        JsonObject(emptyMap())
                    }

                    if (funcName == "request_phase_change") {
                        val target = parsedArgs["target_phase"]?.jsonPrimitive?.content ?: "discovery"
                        currentPhase = if (target == "execution") "execution" else "discovery"
                        val result = "Phase changed to '$currentPhase'."
                        onPhaseChange?.invoke(currentPhase)

                        onDelta(AgentDelta.ToolOutput(funcName, result))
                        val toolMsg = ChatMessage(MessageRole.TOOL, content = result, toolCallId = call.id)
                        messages.add(toolMsg)
                        newMessages.add(toolMsg)
                        continue
                    }

                    val toolResult = try {
                        toolExecutor(funcName, parsedArgs)
                    } catch (e: Exception) {
                        "Error executing tool $funcName: ${e.message}"
                    }

                    onDelta(AgentDelta.ToolOutput(funcName, toolResult))
                    val toolMsg = ChatMessage(MessageRole.TOOL, content = toolResult, toolCallId = call.id)
                    messages.add(toolMsg)
                    newMessages.add(toolMsg)
                }
                continue
            }

            if (!assistantResponse.content.isNullOrBlank()) {
                messages.add(assistantResponse)
                onDelta(AgentDelta.Assistant(assistantResponse.content))
                break
            } else {
                emptyRetries++
                if (emptyRetries <= 3) {
                    val nudge = ChatMessage(MessageRole.USER, "You returned an empty response with no text and no tool calls. Please continue your task.")
                    messages.add(nudge)
                    newMessages.add(nudge)
                    onDelta(AgentDelta.Status("[empty response — retrying $emptyRetries/3]"))
                    continue
                }
                break
            }
        }
        newMessages
    }

    private fun buildSystemPrompt(toolNames: List<String>, memory: String, globalMem: String, phase: String): String {
        return "You are an autonomous coding agent working inside a CLion project.\n" +
                "Available tools: ${toolNames.joinToString(", ")}.\n" +
                "Current phase: '$phase'. In 'discovery', you only have read-only tools to explore the codebase. " +
                "Once you understand the task, use 'request_phase_change' with target_phase='execution' to unlock mutation tools.\n" +
                (if (globalMem.isNotBlank()) "\n<agent_global_memory>\n$globalMem\n</agent_global_memory>" else "") +
                (if (memory.isNotBlank()) "\n<agent_memory>\n$memory\n</agent_memory>" else "")
    }
}
