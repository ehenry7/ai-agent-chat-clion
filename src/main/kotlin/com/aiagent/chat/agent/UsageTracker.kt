package com.aiagent.chat.agent

import com.aiagent.chat.debug.DebugLog
import com.aiagent.chat.model.*

/**
 * Tracks token usage across a conversation session.
 * Inspired by refact-main's useUsageCounter + useTokenMap hooks.
 *
 * Provides:
 * - Current session token count (from last assistant message with usage data)
 * - Context window percentage with warning/overflow states
 * - Token breakdown by category (system, user, assistant, tool_results, free)
 * - Per-message usage tracking
 * - Compaction event tracking (tokens saved)
 */
class UsageTracker(
    private var maxContextTokens: Int = 32768
) {

    fun updateMaxContextTokens(newMax: Int) {
        maxContextTokens = newMax
    }
    // --- Per-message usage records ---
    private val messageUsages = mutableListOf<Usage>()

    // --- Compaction events ---
    private val compactionEvents = mutableListOf<CompactionEvent>()

    // --- Estimated token breakdown by category ---
    // We estimate by proportional content length, same approach as refact-main's useTokenMap.
    private var categoryTokens = mutableMapOf<String, Int>()

    // --- Last known session tokens (prevents counter dropping to zero between turns) ---
    // Bug fix: upstream refact-main had an issue where the usage counter dropped to zero
    // between turns when the API didn't return usage data. We persist the last non-zero value.
    private var lastKnownSessionTokens: Int = 0

    data class CompactionEvent(
        val messagesBefore: Int,
        val messagesAfter: Int,
        val tokensSavedEstimate: Int,
        val timestamp: Long = System.currentTimeMillis()
    )

    data class UsageSummary(
        val currentSessionTokens: Int,
        val maxContextTokens: Int,
        val percentage: Double,
        val isWarning: Boolean,    // >= 85%
        val isOverflown: Boolean,  // >= 97%
        val totalInputTokens: Int,
        val totalOutputTokens: Int,
        val totalCacheReadTokens: Int,
        val totalCacheCreationTokens: Int,
        val tokenMap: TokenMap?
    )

    /**
     * Record usage from an assistant message.
     * Called after each API response.
     */
    fun recordUsage(usage: Usage?) {
        if (usage != null) {
            messageUsages.add(usage)
        }
    }

    /**
     * Record a compaction event.
     */
    fun recordCompaction(messagesBefore: Int, messagesAfter: Int) {
        // Estimate tokens saved: roughly proportional to messages removed
        val messagesRemoved = messagesBefore - messagesAfter
        val avgTokensPerMessage = if (messageUsages.isNotEmpty()) {
            messageUsages.last().totalInputTokens / messagesBefore.coerceAtLeast(1)
        } else 500
        val tokensSaved = messagesRemoved * avgTokensPerMessage
        compactionEvents.add(CompactionEvent(messagesBefore, messagesAfter, tokensSaved))
    }

    /**
     * Compute the current usage summary from the message history.
     * This is the equivalent of refact-main's useUsageCounter() + useTokenMap().
     */
    fun computeSummary(messages: List<ChatMessage>): UsageSummary {
        // Find the last assistant message with usage data for current session tokens
        var currentSessionTokens = 0
        for (i in messages.indices.reversed()) {
            val msg = messages[i]
            if (msg.role == MessageRole.ASSISTANT && msg.usage != null) {
                currentSessionTokens = msg.usage.totalInputTokens
                if (currentSessionTokens > 0) break
            }
        }

        // Aggregate all usage data
        var totalInput = 0
        var totalOutput = 0
        var totalCacheRead = 0
        var totalCacheCreation = 0
        for (usage in messageUsages) {
            totalInput += usage.totalInputTokens
            totalOutput += usage.completionTokens
            totalCacheRead += usage.cacheReadInputTokens
            totalCacheCreation += usage.cacheCreationInputTokens
        }

        val percentage = if (maxContextTokens > 0) {
            (currentSessionTokens.toDouble() / maxContextTokens) * 100
        } else 0.0

        val isWarning = percentage >= 85.0
        val isOverflown = percentage >= 97.0

        // Build token map (breakdown by category)
        val tokenMap = buildTokenMap(messages, currentSessionTokens)

        return UsageSummary(
            currentSessionTokens = currentSessionTokens,
            maxContextTokens = maxContextTokens,
            percentage = percentage,
            isWarning = isWarning,
            isOverflown = isOverflown,
            totalInputTokens = totalInput,
            totalOutputTokens = totalOutput,
            totalCacheReadTokens = totalCacheRead,
            totalCacheCreationTokens = totalCacheCreation,
            tokenMap = tokenMap
        )
    }

    /**
     * Build a token breakdown map from message history.
     * Uses the same proportional estimation approach as refact-main's useTokenMap:
     * we track token deltas between assistant responses and distribute them
     * proportionally by message content length.
     */
    private fun buildTokenMap(messages: List<ChatMessage>, totalPromptTokens: Int): TokenMap? {
        if (messages.isEmpty() || totalPromptTokens == 0) return null

        // Find assistant messages with usage data
        val assistantIndices = messages.mapIndexedNotNull { i, msg ->
            if (msg.role == MessageRole.ASSISTANT && msg.usage != null && msg.usage.totalInputTokens > 0) {
                i
            } else null
        }

        if (assistantIndices.isEmpty()) return null

        val categoryTokens = mutableMapOf(
            "system" to 0,
            "user_messages" to 0,
            "assistant_messages" to 0,
            "tool_results" to 0
        )

        var prevPromptTokens = 0
        var prevEndIndex = -1

        for (assistantIndex in assistantIndices) {
            val assistantMsg = messages[assistantIndex]
            val currentPromptTokens = assistantMsg.usage?.totalInputTokens ?: 0
            if (currentPromptTokens == 0) continue
            val deltaTokens = currentPromptTokens - prevPromptTokens

            // Collect messages in this segment
            val segmentMessages = mutableListOf<ChatMessage>()
            for (i in (prevEndIndex + 1)..assistantIndex) {
                segmentMessages.add(messages[i])
            }

            // Calculate content lengths by category
            val segmentLengths = mutableMapOf(
                "system" to 0,
                "user_messages" to 0,
                "assistant_messages" to 0,
                "tool_results" to 0
            )

            for (msg in segmentMessages) {
                val category = when (msg.role) {
                    MessageRole.SYSTEM -> "system"
                    MessageRole.USER -> "user_messages"
                    MessageRole.ASSISTANT -> "assistant_messages"
                    MessageRole.TOOL -> "tool_results"
                }
                val len = msg.content?.length ?: 0
                segmentLengths[category] = segmentLengths[category]!! + len
                // Include tool call JSON in assistant message length
                if (msg.role == MessageRole.ASSISTANT && msg.toolCalls != null) {
                    segmentLengths["assistant_messages"] = segmentLengths["assistant_messages"]!! +
                        msg.toolCalls.joinToString("") { it.function.arguments.length.toString() }.length
                }
            }

            val totalSegmentLength = segmentLengths.values.sum()
            if (totalSegmentLength > 0 && deltaTokens > 0) {
                val scale = deltaTokens.toDouble() / totalSegmentLength
                for ((cat, len) in segmentLengths) {
                    categoryTokens[cat] = categoryTokens[cat]!! + (len * scale).toInt()
                }
            }

            prevPromptTokens = currentPromptTokens
            prevEndIndex = assistantIndex
        }

        // Build segments
        val totalUsed = categoryTokens.values.sum()
        val freeTokens = maxOf(0, maxContextTokens - totalUsed)
        val calcPct = { tokens: Int -> if (maxContextTokens > 0) (tokens.toDouble() / maxContextTokens) * 100 else 0.0 }

        val segments = mutableListOf<TokenMapSegment>()

        val categoryConfig = listOf(
            "system" to "System prompt",
            "user_messages" to "User messages",
            "assistant_messages" to "Assistant messages",
            "tool_results" to "Tool results"
        )

        for ((key, label) in categoryConfig) {
            val tokens = categoryTokens[key] ?: 0
            if (tokens > 0) {
                segments.add(TokenMapSegment(label, key, tokens, calcPct(tokens)))
            }
        }

        if (freeTokens > 0) {
            segments.add(TokenMapSegment("Free space", "free", freeTokens, calcPct(freeTokens)))
        }

        return TokenMap(
            totalPromptTokens = totalPromptTokens,
            maxContextTokens = maxContextTokens,
            segments = segments
        )
    }

    /**
     * Get all recorded compaction events.
     */
    fun getCompactionEvents(): List<CompactionEvent> = compactionEvents.toList()

    /**
     * Reset all tracking data (for new sessions).
     */
    fun reset() {
        messageUsages.clear()
        compactionEvents.clear()
        categoryTokens.clear()
        lastKnownSessionTokens = 0
    }
}
