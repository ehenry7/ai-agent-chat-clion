package com.aiagent.chat.agent

import com.aiagent.chat.debug.DebugLog
import com.aiagent.chat.model.*
import com.aiagent.chat.net.ApiClient
import com.aiagent.chat.net.ContextLimitException
import com.aiagent.chat.net.StreamChunk
import com.aiagent.chat.tools.ToolRegistry
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
    /** Command queue update — emitted when the queue contents change. */
    data class QueueUpdate(val pendingCommands: Int, val queueContents: List<String>) : AgentDelta
    /** Context compaction event — emitted when the conversation history is being compacted. */
    data class CompactionNotice(val message: String, val messagesBefore: Int, val messagesAfter: Int) : AgentDelta
    /** Token usage update — emitted after each API response with usage data. */
    data class UsageUpdate(val usage: com.aiagent.chat.model.Usage) : AgentDelta
}

class AgentEngine(
    private val client: ApiClient,
    private val toolExecutor: suspend (name: String, args: JsonObject) -> String,
    private val onDelta: (AgentDelta) -> Unit,
    private val stateMachine: SessionStateMachine = SessionStateMachine(),
    private val commandQueue: CommandQueue = CommandQueue(),
    private val contextCompactor: ContextCompactor? = null,
    val planManager: PlanManager = PlanManager(),
    val providerManager: ProviderManager? = null
) {
    companion object {
        const val RECENT_WINDOW_MESSAGES = 8
        const val TOOL_COMPRESS_THRESHOLD = 2000
        const val COMPRESSED_TOOL_NOTICE = "[Tool executed successfully. Output compressed for memory preservation.]"
        const val ASSISTANT_COMPRESS_THRESHOLD = 3000
        const val COMPRESSED_ASSISTANT_NOTICE = "[Assistant response with code/explanation, compressed for memory preservation.]"
        const val MAX_COMPACTION_RETRIES = 2
        /** Proactive compaction: trigger when estimated tokens exceed this fraction of max. */
        const val PROACTIVE_COMPACTION_RATIO = 0.80
    }

    private val mutatingTools: Set<String> get() = ToolRegistry.mutatingToolNames()

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
            } else if (m.role == MessageRole.ASSISTANT && (m.content?.length ?: 0) > ASSISTANT_COMPRESS_THRESHOLD) {
                // Compress long assistant messages (code blocks, explanations) in the old window
                val preview = m.content?.take(200) ?: ""
                m.copy(content = "$preview\n\n$COMPRESSED_ASSISTANT_NOTICE")
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
     * 2. Command queue for steering and abort
     * 3. Abort checking between API calls and tool executions
     * 4. Tool approval handled by PlatformToolHandler (single gate, driven by ApprovalMode)
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

                // Check if the plan has incomplete steps — if so, nudge the agent
                // to continue instead of ending the loop prematurely.
                if (planManager.hasIncompleteSteps() && step < maxSteps - 1) {
                    val incomplete = planManager.incompleteStepsSummary()
                    val nudge = ChatMessage(MessageRole.USER,
                        "You indicated you are done, but the plan still has incomplete steps:\n$incomplete\n\n" +
                        "Please continue working on the remaining steps. Use update_plan to mark steps as in_progress or completed as you work."
                    )
                    messages.add(nudge)
                    newMessages.add(nudge)
                    onDelta(AgentDelta.Status("[plan has incomplete steps — continuing]"))
                    stateMachine.transitionTo(AgentSessionState.GENERATING, "Plan incomplete, nudging agent to continue")
                    continue
                }

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

                // Check if the plan has incomplete steps — if so, nudge the agent
                // to continue instead of ending the loop prematurely.
                if (planManager.hasIncompleteSteps() && step < maxSteps - 1) {
                    val incomplete = planManager.incompleteStepsSummary()
                    val nudge = ChatMessage(MessageRole.USER,
                        "You indicated you are done, but the plan still has incomplete steps:\n$incomplete\n\n" +
                        "Please continue working on the remaining steps. Use update_plan to mark steps as in_progress or completed as you work."
                    )
                    messages.add(nudge)
                    newMessages.add(nudge)
                    onDelta(AgentDelta.Status("[plan has incomplete steps — continuing]"))
                    stateMachine.transitionTo(AgentSessionState.GENERATING, "Plan incomplete, nudging agent to continue")
                    continue
                }

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
     * Call the LLM API with automatic context compaction.
     *
     * Two compaction modes:
     * 1. Proactive: before each API call, estimate token count. If estimated tokens
     *    exceed 80% of maxContextTokens, compact before sending (avoids wasted round-trips).
     * 2. Reactive: if the API returns ContextLimitException, compact and retry.
     *
     * Fallback strategy: if LLM summarization doesn't reduce enough, use fallbackCompact
     * (truncate old messages to 200 chars, reduce protected window to 4).
     *
     * Retries up to MAX_COMPACTION_RETRIES times.
     */
    private suspend fun callWithCompaction(
        messages: MutableList<ChatMessage>,
        tools: List<ToolDefinition>,
        ephemeral: List<ChatMessage>
    ): ChatMessage {
        var compactionAttempts = 0
        while (true) {
            // --- Proactive compaction: check token estimate before API call ---
            if (contextCompactor != null) {
                val estimatedTokens = contextCompactor.estimateTokens(messages)
                val proactiveThreshold = (contextCompactor.maxContextTokens * PROACTIVE_COMPACTION_RATIO).toInt()
                if (estimatedTokens >= proactiveThreshold && contextCompactor.needsCompaction(messages)) {
                    DebugLog.info("AgentEngine", "Proactive compaction: estimated $estimatedTokens tokens >= $proactiveThreshold threshold, compacting before API call")
                    onDelta(AgentDelta.Status("[proactive context compaction: ~${estimatedTokens} tokens estimated]"))
                    val sizeBefore = messages.size
                    val compacted = contextCompactor.compact(messages)
                    if (compacted.size < sizeBefore) {
                        messages.clear()
                        messages.addAll(compacted)
                        onDelta(AgentDelta.CompactionNotice("Proactive compaction", sizeBefore, compacted.size))
                        DebugLog.info("AgentEngine", "Proactive compaction successful: $sizeBefore -> ${compacted.size} messages")
                    }
                }
            }

            val compressed = applySemanticSlidingWindow(messages)
            val messagesForApi = compressed + ephemeral
            DebugLog.info("AgentEngine", "Sending ${messagesForApi.size} messages to API, ${tools.size} tools (compaction attempts: $compactionAttempts)")
            try {
                val response = client.chat(messagesForApi, tools)
                // Emit usage update if the response contains usage data
                response.usage?.let { onDelta(AgentDelta.UsageUpdate(it)) }
                return response
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
                    // LLM compaction didn't help — try fallback strategy
                    DebugLog.warn("AgentEngine", "LLM compaction did not reduce message count, trying fallback strategy")
                    val fallback = contextCompactor.fallbackCompact(messages)
                    if (fallback != null && fallback.size < sizeBefore) {
                        messages.clear()
                        messages.addAll(fallback)
                        onDelta(AgentDelta.CompactionNotice("Fallback compaction (truncate old messages)", sizeBefore, fallback.size))
                        DebugLog.info("AgentEngine", "Fallback compaction: $sizeBefore -> ${fallback.size} messages")
                    } else {
                        DebugLog.warn("AgentEngine", "Fallback compaction also failed, giving up")
                        throw e
                    }
                }
            }
        }
    }

    internal fun buildSystemPrompt(toolNames: List<String>, memory: String, globalMem: String, phase: String): String {
        return "You are an autonomous coding agent working inside a CLion project.\n" +
                "Available tools: ${toolNames.joinToString(", ")}.\n" +
                "Current phase: '$phase'. In 'discovery', you only have read-only tools to explore the codebase. " +
                "Once you understand the task, use 'request_phase_change' with target_phase='execution' to unlock mutation tools.\n" +
                "If a tool call is denied by the user, respect the denial reason and adjust your approach accordingly.\n" +
                "Use set_plan to create a structured task plan, get_plan to check it, and update_plan to mark steps as completed.\n" +
                "IMPORTANT: You MUST update plan steps in real time as you work. Before starting a step, mark it as 'in_progress' using update_plan. " +
                "Immediately after completing a step, mark it as 'completed' using update_plan. " +
                "Do NOT batch all plan updates at the end — the user needs to see your progress as you go.\n" +
                "Do NOT claim the task is done or provide a final summary while any plan step is still 'pending' or 'in_progress'. " +
                "Only give a final summary when ALL plan steps are marked 'completed' or 'skipped'.\n" +
                "Use compress_chat_probe to check if context is getting long, and compress_chat_apply to compact it.\n" +
                "Use ask_questions to ask the user structured questions when you need clarification.\n" +
                "Use undo_textdoc to revert the last file edit if you made a mistake.\n" +
                "IMPORTANT: Prefer run_python over run_command for any computation, data processing, file parsing, or scripting tasks. " +
                "Use run_python for calculations, string manipulation, JSON/XML processing, regex operations, and any logic that can be expressed in Python. " +
                "Only use run_command for tasks that genuinely require shell features (e.g. git, build tools, process management) " +
                "or when Python is not suitable for the task.\n" +
                (if (globalMem.isNotBlank()) "\n<agent_global_memory>\n$globalMem\n</agent_global_memory>" else "") +
                (if (memory.isNotBlank()) "\n<agent_memory>\n$memory\n</agent_memory>" else "") +
                planManager.toSystemPromptSection() +
                (providerManager?.toSystemPromptSection() ?: "")
    }
}
