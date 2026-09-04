package com.aiagent.chat.agent

import com.aiagent.chat.debug.DebugLog
import com.aiagent.chat.model.*
import com.aiagent.chat.net.ApiClient
import com.aiagent.chat.net.ContextLimitException
import com.aiagent.chat.net.StreamChunk
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

sealed interface AgentDelta {
    data class Status(val text: String) : AgentDelta
    data class Assistant(val text: String) : AgentDelta
    data class ToolOutput(val name: String, val text: String) : AgentDelta
    /** Incremental streaming content — emitted token-by-token during SSE streaming. */
    data class StreamingContent(val text: String) : AgentDelta
    /** Incremental reasoning/thinking content — emitted during SSE streaming when </think>...</think> tags are detected. */
    data class StreamingReasoning(val text: String) : AgentDelta
    /** Signals the start of a streaming response. */
    data class StreamingStart(val text: String = "") : AgentDelta
    /** Signals the end of a streaming response, with the full accumulated text. */
    data class StreamingEnd(val fullText: String) : AgentDelta
    /** State machine transition — emitted whenever the session state changes. */
    data class StateChange(val from: AgentSessionState, val to: AgentSessionState, val reason: String) : AgentDelta
    /** Tool approval request — emitted when a tool needs user approval. */
    data class ToolApprovalRequest(val toolCallId: String, val toolName: String, val toolArgs: String, val category: ToolCategory) : AgentDelta
    /** Command queue update — emitted when the queue contents change. */
    data class QueueUpdate(val pendingCommands: Int, val queueContents: List<String>) : AgentDelta
    /** Context compaction event — emitted when the conversation history is being compacted. */
    data class CompactionNotice(val message: String, val messagesBefore: Int, val messagesAfter: Int) : AgentDelta
}

class AgentEngine(
    private val client: ApiClient,
    private val toolExecutor: suspend (name: String, args: JsonObject) -> String,
    private val onDelta: (AgentDelta) -> Unit,
    private val stateMachine: SessionStateMachine = SessionStateMachine(),
    private val commandQueue: CommandQueue = CommandQueue(),
    private val contextCompactor: ContextCompactor? = null
) {
    companion object {
        const val RECENT_WINDOW_MESSAGES = 8
        const val TOOL_COMPRESS_THRESHOLD = 2000
        const val COMPRESSED_TOOL_NOTICE = "[Tool executed successfully. Output compressed for memory preservation.]"
        const val MAX_COMPACTION_RETRIES = 2
    }

    private val mutatingTools = setOf(
        "write_file", "edit_file", "apply_patch", "apply_diff",
        "search_replace", "delete_file", "delete_directory",
        "rename_file", "create_directory", "run_command", "run_python", "git_commit"
    )

    /** Expose state machine for external queries (e.g. UI status display). */
    val state: AgentSessionState get() = stateMachine.state
    val queue: CommandQueue get() = commandQueue

    init {
        // Wire state machine transitions to AgentDelta emissions
        stateMachine.addListener { transition ->
            onDelta(AgentDelta.StateChange(transition.from, transition.to, transition.reason))
        }
        // Wire command queue changes to AgentDelta emissions
        commandQueue.addListener { snapshot ->
            onDelta(AgentDelta.QueueUpdate(snapshot.size, snapshot.map { it::class.simpleName ?: "Unknown" }))
        }
    }

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
     * Streaming variant of runAgentLoop with state machine + command queue integration.
     *
     * Key improvements over the original:
     * 1. Explicit state transitions (IDLE -> GENERATING -> EXECUTING_TOOLS -> PAUSED -> COMPLETED)
     * 2. Command queue for steering, abort, and tool decisions
     * 3. Abort checking between API calls and tool executions
     * 4. Tool approval via CompletableDeferred (non-blocking, with deny reasons)
     * 5. Steering from both the command queue and the legacy steerProvider
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

        // Register this coroutine as the active job so Abort can cancel it
        val thisJob = coroutineContext[Job]
        if (thisJob != null) commandQueue.setActiveJob(thisJob)
        commandQueue.resetAbort()

        // Transition: IDLE -> GENERATING (first step)
        stateMachine.transitionTo(AgentSessionState.GENERATING, "Starting agent loop")

        for (step in 0 until maxSteps) {
            ensureActive()

            // --- Abort check (from command queue) ---
            if (commandQueue.isAborted()) {
                DebugLog.info("AgentEngine", "Abort detected at step ${step + 1}, stopping loop")
                stateMachine.transitionTo(AgentSessionState.COMPLETED, "Aborted by user")
                break
            }

            DebugLog.info("AgentEngine", "Step ${step + 1}/$maxSteps, phase: $currentPhase, state: ${stateMachine.state}, message count: ${messages.size}")

            // --- Process command queue: steering ---
            // Drain all pending Steer commands from the queue
            while (true) {
                val cmd = commandQueue.peek()
                if (cmd is AgentCommand.Steer) {
                    commandQueue.dequeue()
                    DebugLog.info("AgentEngine", "Injecting steer message from command queue: ${cmd.text.take(100)}")
                    val steerMsg = ChatMessage(MessageRole.USER, "[Steering] ${cmd.text}")
                    messages.add(steerMsg)
                    newMessages.add(steerMsg)
                    // State transition: signal that we're processing a steer
                    stateMachine.transitionTo(AgentSessionState.GENERATING, "Steering: ${cmd.text.take(50)}")
                } else {
                    break
                }
            }

            // --- Legacy steering provider (backward compatibility) ---
            val steerText = steerProvider?.invoke()
            if (steerText != null && steerText.isNotBlank()) {
                DebugLog.info("AgentEngine", "Injecting steer message from provider: ${steerText.take(100)}")
                val steerMsg = ChatMessage(MessageRole.USER, "[Steering] $steerText")
                messages.add(steerMsg)
                newMessages.add(steerMsg)
            }

            onDelta(AgentDelta.Status("[step ${step + 1}/$maxSteps]"))

            val activeTools = if (currentPhase == "execution") {
                availableTools
            } else {
                availableTools.filterNot { mutatingTools.contains(it.function.name) }
            }
            DebugLog.info("AgentEngine", "Active tools: ${activeTools.size} (phase=$currentPhase, mutatingTools filtered=${currentPhase != "execution"})")

            val systemPrompt = buildSystemPrompt(activeTools.map { it.function.name }, memory, globalMemory, currentPhase)
            DebugLog.info("AgentEngine", "System prompt: ${systemPrompt.take(200)}...")
            messages[0] = ChatMessage(
                MessageRole.SYSTEM,
                content = systemPrompt
            )

            val ephemeral = mutableListOf<ChatMessage>()
            currentPlan?.let {
                ephemeral.add(ChatMessage(MessageRole.SYSTEM, "Current Plan State:\n$it"))
            }

            // --- State: GENERATING ---
            stateMachine.transitionTo(AgentSessionState.GENERATING, "Step ${step + 1}: requesting LLM response")

            // Use non-streaming mode (more reliable) with context compaction support
            DebugLog.info("AgentEngine", "Sending non-streaming request to API (messages=${messages.size}, tools=${activeTools.size})")
            val assistantResponse = try {
                callWithCompaction(messages, activeTools, ephemeral)
            } catch (e: CancellationException) {
                DebugLog.info("AgentEngine", "API call cancelled (likely abort)")
                stateMachine.transitionTo(AgentSessionState.COMPLETED, "Cancelled")
                throw e
            } catch (e: ContextLimitException) {
                DebugLog.error("AgentEngine", "Context limit exceeded after max compaction retries")
                stateMachine.transitionTo(AgentSessionState.ERROR, "Context limit exceeded")
                onDelta(AgentDelta.Status("[error: context limit exceeded after compaction]"))
                break
            }
            DebugLog.info("AgentEngine", "Non-streaming response received: ${assistantResponse.content?.take(100) ?: "[no content]"}")
            newMessages.add(assistantResponse)

            // --- Abort check after API response ---
            if (commandQueue.isAborted()) {
                DebugLog.info("AgentEngine", "Abort detected after API response, stopping")
                stateMachine.transitionTo(AgentSessionState.COMPLETED, "Aborted by user")
                break
            }

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

                // --- State: EXECUTING_TOOLS ---
                stateMachine.transitionTo(AgentSessionState.EXECUTING_TOOLS, "Executing ${calls.size} tool call(s)")

                for (call in calls) {
                    ensureActive()

                    // --- Abort check before each tool ---
                    if (commandQueue.isAborted()) {
                        DebugLog.info("AgentEngine", "Abort detected before tool $call, stopping")
                        stateMachine.transitionTo(AgentSessionState.COMPLETED, "Aborted by user")
                        break
                    }

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
                        DebugLog.info("AgentEngine", "Phase change requested: $target -> $currentPhase")
                        val result = "Phase changed to '$currentPhase'."
                        onPhaseChange?.invoke(currentPhase)

                        onDelta(AgentDelta.ToolOutput(funcName, result))
                        val toolMsg = ChatMessage(MessageRole.TOOL, content = result, toolCallId = call.id)
                        messages.add(toolMsg)
                        newMessages.add(toolMsg)
                        continue
                    }

                    // --- Tool approval via command queue ---
                    val category = getToolCategoryForApproval(funcName)
                    if (category != ToolCategory.READ_ONLY) {
                        // Emit approval request to UI
                        onDelta(AgentDelta.ToolApprovalRequest(call.id, funcName, parsedArgs.toString(), category))

                        // Pause state machine while waiting for approval
                        stateMachine.pause("Tool approval required: $funcName (category: $category)")

                        // Create a deferred and wait for user decision
                        val deferred = commandQueue.createToolDecisionPending()
                        val decision = try {
                            deferred.await()
                        } catch (e: CancellationException) {
                            DebugLog.info("AgentEngine", "Tool approval cancelled (likely abort)")
                            commandQueue.clearToolDecisionPending()
                            stateMachine.transitionTo(AgentSessionState.COMPLETED, "Cancelled during tool approval")
                            throw e
                        }

                        commandQueue.clearToolDecisionPending()

                        // Resume state machine
                        stateMachine.resume("User decision: ${if (decision.acceptedToolCallIds.contains(call.id)) "approved" else "denied"}")

                        // Check if this specific tool call was denied
                        if (!decision.acceptedToolCallIds.contains(call.id)) {
                            val denyReason = decision.deniedToolCallIds[call.id] ?: "No reason provided"
                            DebugLog.info("AgentEngine", "Tool $funcName denied by user: $denyReason")
                            val denyResult = "Tool call denied by user. Reason: $denyReason"
                            onDelta(AgentDelta.ToolOutput(funcName, denyResult))
                            val toolMsg = ChatMessage(MessageRole.TOOL, content = denyResult, toolCallId = call.id)
                            messages.add(toolMsg)
                            newMessages.add(toolMsg)
                            continue
                        }

                        // If auto-approve session is requested, apply it via the tool executor
                        if (decision.autoApproveSession) {
                            DebugLog.info("AgentEngine", "Auto-approve session requested for future $funcName calls")
                        }
                    }

                    // --- Abort check after approval, before execution ---
                    if (commandQueue.isAborted()) {
                        DebugLog.info("AgentEngine", "Abort detected after approval, stopping")
                        stateMachine.transitionTo(AgentSessionState.COMPLETED, "Aborted by user")
                        break
                    }

                    DebugLog.info("AgentEngine", "Calling tool: $funcName with args: ${parsedArgs.toString().take(100)}")
                    val toolResult = try {
                        toolExecutor(funcName, parsedArgs)
                    } catch (e: Exception) {
                        DebugLog.error("AgentEngine", "Tool $funcName threw: ${e.message}", e)
                        "Error executing tool $funcName: ${e.message}"
                    }

                    DebugLog.info("AgentEngine", "Tool $funcName returned: ${toolResult.take(100)}")
                    onDelta(AgentDelta.ToolOutput(funcName, toolResult))
                    val toolMsg = ChatMessage(MessageRole.TOOL, content = toolResult, toolCallId = call.id)
                    messages.add(toolMsg)
                    newMessages.add(toolMsg)
                }

                // After all tools, go back to GENERATING for next step
                if (stateMachine.state == AgentSessionState.EXECUTING_TOOLS) {
                    stateMachine.transitionTo(AgentSessionState.GENERATING, "Tools completed, continuing loop")
                }
                continue
            }

            if (!assistantResponse.content.isNullOrBlank()) {
                messages.add(assistantResponse)
                onDelta(AgentDelta.Assistant(assistantResponse.content))
                stateMachine.transitionTo(AgentSessionState.COMPLETED, "Assistant provided final response")
                break
            } else {
                emptyRetries++
                if (emptyRetries <= 3) {
                    val nudge = ChatMessage(MessageRole.USER, "You returned an empty response with no text and no tool calls. Please continue your task.")
                    messages.add(nudge)
                    newMessages.add(nudge)
                    onDelta(AgentDelta.Status("[empty response — retrying $emptyRetries/3]"))
                    stateMachine.transitionTo(AgentSessionState.GENERATING, "Empty response retry $emptyRetries/3")
                    continue
                }
                stateMachine.transitionTo(AgentSessionState.COMPLETED, "Max empty retries reached")
                break
            }
        }

        // Final state cleanup
        if (stateMachine.state != AgentSessionState.COMPLETED && stateMachine.state != AgentSessionState.ERROR) {
            stateMachine.transitionTo(AgentSessionState.COMPLETED, "Loop ended (max steps or break)")
        }
        commandQueue.clearActiveJob()
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

        // Register this coroutine as the active job so Abort can cancel it
        val thisJob = coroutineContext[Job]
        if (thisJob != null) commandQueue.setActiveJob(thisJob)
        commandQueue.resetAbort()

        // Transition: IDLE -> GENERATING (first step)
        stateMachine.transitionTo(AgentSessionState.GENERATING, "Starting agent loop")

        for (step in 0 until maxSteps) {
            ensureActive()

            // --- Abort check (from command queue) ---
            if (commandQueue.isAborted()) {
                DebugLog.info("AgentEngine", "Abort detected at step ${step + 1}, stopping loop")
                stateMachine.transitionTo(AgentSessionState.COMPLETED, "Aborted by user")
                break
            }

            // --- Process command queue: steering ---
            while (true) {
                val cmd = commandQueue.peek()
                if (cmd is AgentCommand.Steer) {
                    commandQueue.dequeue()
                    DebugLog.info("AgentEngine", "Injecting steer message from command queue: ${cmd.text.take(100)}")
                    val steerMsg = ChatMessage(MessageRole.USER, "[Steering] ${cmd.text}")
                    messages.add(steerMsg)
                    newMessages.add(steerMsg)
                    stateMachine.transitionTo(AgentSessionState.GENERATING, "Steering: ${cmd.text.take(50)}")
                } else {
                    break
                }
            }

            // --- Legacy steering provider (backward compatibility) ---
            val steerText = steerProvider?.invoke()
            if (steerText != null && steerText.isNotBlank()) {
                val steerMsg = ChatMessage(MessageRole.USER, "[Steering] $steerText")
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

            // --- State: GENERATING ---
            stateMachine.transitionTo(AgentSessionState.GENERATING, "Step ${step + 1}: requesting LLM response")

            val assistantResponse = try {
                callWithCompaction(messages, activeTools, ephemeral)
            } catch (e: CancellationException) {
                stateMachine.transitionTo(AgentSessionState.COMPLETED, "Cancelled")
                throw e
            } catch (e: ContextLimitException) {
                DebugLog.error("AgentEngine", "Context limit exceeded after max compaction retries")
                stateMachine.transitionTo(AgentSessionState.ERROR, "Context limit exceeded")
                onDelta(AgentDelta.Status("[error: context limit exceeded after compaction]"))
                break
            }
            newMessages.add(assistantResponse)

            // --- Abort check after API response ---
            if (commandQueue.isAborted()) {
                stateMachine.transitionTo(AgentSessionState.COMPLETED, "Aborted by user")
                break
            }

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

                // --- State: EXECUTING_TOOLS ---
                stateMachine.transitionTo(AgentSessionState.EXECUTING_TOOLS, "Executing ${calls.size} tool call(s)")

                for (call in calls) {
                    ensureActive()

                    // --- Abort check before each tool ---
                    if (commandQueue.isAborted()) {
                        stateMachine.transitionTo(AgentSessionState.COMPLETED, "Aborted by user")
                        break
                    }

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
                        DebugLog.info("AgentEngine", "Phase change requested: $target -> $currentPhase")
                        val result = "Phase changed to '$currentPhase'."
                        onPhaseChange?.invoke(currentPhase)

                        onDelta(AgentDelta.ToolOutput(funcName, result))
                        val toolMsg = ChatMessage(MessageRole.TOOL, content = result, toolCallId = call.id)
                        messages.add(toolMsg)
                        newMessages.add(toolMsg)
                        continue
                    }

                    // --- Tool approval via command queue ---
                    val category = getToolCategoryForApproval(funcName)
                    if (category != ToolCategory.READ_ONLY) {
                        onDelta(AgentDelta.ToolApprovalRequest(call.id, funcName, parsedArgs.toString(), category))
                        stateMachine.pause("Tool approval required: $funcName (category: $category)")

                        val deferred = commandQueue.createToolDecisionPending()
                        val decision = try {
                            deferred.await()
                        } catch (e: CancellationException) {
                            commandQueue.clearToolDecisionPending()
                            stateMachine.transitionTo(AgentSessionState.COMPLETED, "Cancelled during tool approval")
                            throw e
                        }

                        commandQueue.clearToolDecisionPending()
                        stateMachine.resume("User decision received")

                        if (!decision.acceptedToolCallIds.contains(call.id)) {
                            val denyReason = decision.deniedToolCallIds[call.id] ?: "No reason provided"
                            DebugLog.info("AgentEngine", "Tool $funcName denied by user: $denyReason")
                            val denyResult = "Tool call denied by user. Reason: $denyReason"
                            onDelta(AgentDelta.ToolOutput(funcName, denyResult))
                            val toolMsg = ChatMessage(MessageRole.TOOL, content = denyResult, toolCallId = call.id)
                            messages.add(toolMsg)
                            newMessages.add(toolMsg)
                            continue
                        }
                    }

                    // --- Abort check after approval ---
                    if (commandQueue.isAborted()) {
                        stateMachine.transitionTo(AgentSessionState.COMPLETED, "Aborted by user")
                        break
                    }

                    DebugLog.info("AgentEngine", "Calling tool: $funcName with args: ${parsedArgs.toString().take(100)}")
                    val toolResult = try {
                        toolExecutor(funcName, parsedArgs)
                    } catch (e: Exception) {
                        DebugLog.error("AgentEngine", "Tool $funcName threw: ${e.message}", e)
                        "Error executing tool $funcName: ${e.message}"
                    }

                    DebugLog.info("AgentEngine", "Tool $funcName returned: ${toolResult.take(100)}")
                    onDelta(AgentDelta.ToolOutput(funcName, toolResult))
                    val toolMsg = ChatMessage(MessageRole.TOOL, content = toolResult, toolCallId = call.id)
                    messages.add(toolMsg)
                    newMessages.add(toolMsg)
                }

                if (stateMachine.state == AgentSessionState.EXECUTING_TOOLS) {
                    stateMachine.transitionTo(AgentSessionState.GENERATING, "Tools completed, continuing loop")
                }
                continue
            }

            if (!assistantResponse.content.isNullOrBlank()) {
                messages.add(assistantResponse)
                onDelta(AgentDelta.Assistant(assistantResponse.content))
                stateMachine.transitionTo(AgentSessionState.COMPLETED, "Assistant provided final response")
                break
            } else {
                emptyRetries++
                if (emptyRetries <= 3) {
                    val nudge = ChatMessage(MessageRole.USER, "You returned an empty response with no text and no tool calls. Please continue your task.")
                    messages.add(nudge)
                    newMessages.add(nudge)
                    onDelta(AgentDelta.Status("[empty response — retrying $emptyRetries/3]"))
                    stateMachine.transitionTo(AgentSessionState.GENERATING, "Empty response retry $emptyRetries/3")
                    continue
                }
                stateMachine.transitionTo(AgentSessionState.COMPLETED, "Max empty retries reached")
                break
            }
        }

        if (stateMachine.state != AgentSessionState.COMPLETED && stateMachine.state != AgentSessionState.ERROR) {
            stateMachine.transitionTo(AgentSessionState.COMPLETED, "Loop ended (max steps or break)")
        }
        commandQueue.clearActiveJob()
        newMessages
    }

    /**
     * Call the LLM API with automatic context compaction on context-limit errors.
     * If a ContextLimitException is thrown, compacts the message history and retries.
     * Retries up to MAX_COMPACTION_RETRIES times.
     */
    private suspend fun callWithCompaction(
        messages: MutableList<ChatMessage>,
        tools: List<ToolDefinition>,
        ephemeral: List<ChatMessage>
    ): ChatMessage {
        var compactionAttempts = 0
        while (true) {
            val compressed = applySemanticSlidingWindow(messages)
            val messagesForApi = compressed + ephemeral
            DebugLog.info("AgentEngine", "Sending ${messagesForApi.size} messages to API, ${tools.size} tools (compaction attempts: $compactionAttempts)")
            try {
                return client.chat(messagesForApi, tools)
            } catch (e: ContextLimitException) {
                compactionAttempts++
                if (compactionAttempts > MAX_COMPACTION_RETRIES || contextCompactor == null) {
                    DebugLog.warn("AgentEngine", "Context limit exceeded, no more compaction retries (attempts=$compactionAttempts, compactor=${contextCompactor != null})")
                    throw e
                }
                DebugLog.info("AgentEngine", "Context limit exceeded, attempting compaction (attempt $compactionAttempts/$MAX_COMPACTION_RETRIES)")
                onDelta(AgentDelta.Status("[context limit exceeded, compacting conversation...]"))
                val sizeBefore = messages.size
                val compacted = contextCompactor.compact(messages)
                val sizeAfter = compacted.size
                if (sizeAfter < sizeBefore) {
                    messages.clear()
                    messages.addAll(compacted)
                    onDelta(AgentDelta.CompactionNotice("Context compacted to fit within limits", sizeBefore, sizeAfter))
                    DebugLog.info("AgentEngine", "Compaction successful: $sizeBefore -> $sizeAfter messages")
                } else {
                    DebugLog.warn("AgentEngine", "Compaction did not reduce message count, giving up")
                    throw e
                }
            }
        }
    }

    /**
     * Get the tool category for approval routing.
     * Mirrors the categorization in PlatformToolHandler but kept here for the engine's own logic.
     */
    private fun getToolCategoryForApproval(toolName: String): ToolCategory {
        val readOnlyTools = setOf(
            "read_file", "read_file_lines", "list_directory", "find_files",
            "search_in_files", "get_active_editor", "fetch_url", "web_search",
            "git_status", "git_diff", "git_log", "format_document",
            "update_todo_list", "request_phase_change"
        )
        val dangerousTools = setOf("run_command", "run_python")

        return when (toolName) {
            in readOnlyTools -> ToolCategory.READ_ONLY
            in dangerousTools -> ToolCategory.DANGEROUS
            else -> ToolCategory.MUTATING
        }
    }

    private fun buildSystemPrompt(toolNames: List<String>, memory: String, globalMem: String, phase: String): String {
        return "You are an autonomous coding agent working inside a CLion project.\n" +
                "Available tools: ${toolNames.joinToString(", ")}.\n" +
                "Current phase: '$phase'. In 'discovery', you only have read-only tools to explore the codebase. " +
                "Once you understand the task, use 'request_phase_change' with target_phase='execution' to unlock mutation tools.\n" +
                "If a tool call is denied by the user, respect the denial reason and adjust your approach accordingly.\n" +
                (if (globalMem.isNotBlank()) "\n<agent_global_memory>\n$globalMem\n</agent_global_memory>" else "") +
                (if (memory.isNotBlank()) "\n<agent_memory>\n$memory\n</agent_memory>" else "")
    }
}
