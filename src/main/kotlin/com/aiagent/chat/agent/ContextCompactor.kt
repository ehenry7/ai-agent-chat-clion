package com.aiagent.chat.agent

import com.aiagent.chat.debug.DebugLog
import com.aiagent.chat.model.ChatMessage
import com.aiagent.chat.model.MessageRole
import com.aiagent.chat.net.ApiClient

/**
 * LLM-based context compaction (Tier 2).
 *
 * Inspired by refact-main's multi-tier context compaction strategy.
 * When the conversation history grows too long (or a context-limit error is received),
 * this class sends the old messages to the LLM with a structured summarization prompt
 * and replaces them with a single compact system message.
 *
 * The structured prompt asks the LLM to produce:
 *  - Current Task State
 *  - Key Files
 *  - Decisions & Constraints
 *  - Tool Outcomes
 *  - Dropped Context
 *
 * Improvements over original:
 *  - Dynamic compaction threshold scaled to maxContextTokens
 *  - Rolling summary: reuses previous summary instead of re-summarizing everything
 *  - Content truncation increased from 500 to 2000 chars
 *  - Logs warning when MAX_MESSAGES_TO_SUMMARIZE cap is hit
 *  - Token estimation via simple heuristic (chars / 4)
 *  - Fallback compaction strategy when LLM summarization fails or doesn't reduce enough
 *  - needsCompaction checks both message count AND estimated token count
 */
class ContextCompactor(
    private val client: ApiClient,
    val maxContextTokens: Int = 32768
) {
    companion object {
        /** Number of recent messages to keep unsummarized. */
        const val PROTECTED_RECENT = 8
        /** Max messages to include in the summarization request (to avoid the compaction call itself being too large). */
        const val MAX_MESSAGES_TO_SUMMARIZE = 50
        /** Content truncation limit per message in summarization (was 500, now 2000). */
        const val SUMMARIZE_CONTENT_LIMIT = 2000
        /** Rough tokens-per-char ratio for estimation. */
        const val CHARS_PER_TOKEN = 4
        /** Proactive compaction threshold: trigger when estimated tokens exceed this fraction of max. */
        const val PROACTIVE_THRESHOLD_RATIO = 0.80
        /** Fallback: reduce protected recent window to this many messages. */
        const val FALLBACK_PROTECTED_RECENT = 4
        /** Fallback: truncate old messages to this many chars. */
        const val FALLBACK_TRUNCATE_CHARS = 200
    }

    /** Dynamic compaction threshold based on maxContextTokens. Scales with context window. */
    val compactionThreshold: Int get() = (maxContextTokens / 2000).coerceIn(10, 100)

    /** Rolling summary: stores the last compaction summary to avoid re-summarizing everything. */
    private var lastSummary: String? = null
    /** Index of the first message after the last compaction (to know which messages are new). */
    private var lastCompactedUpTo: Int = 0

    /**
     * Estimate token count for a list of messages using a simple heuristic.
     * This is a rough estimate: total content chars / 4, plus overhead per message.
     */
    fun estimateTokens(messages: List<ChatMessage>): Int {
        var totalChars = 0
        for (msg in messages) {
            // Content
            totalChars += msg.content?.length ?: 0
            // Tool calls contribute to token count
            if (msg.toolCalls != null) {
                for (tc in msg.toolCalls) {
                    totalChars += tc.function.name.length + tc.function.arguments.length + 20
                }
            }
            // Per-message overhead (role, formatting)
            totalChars += 10
        }
        return totalChars / CHARS_PER_TOKEN
    }

    /**
     * Check if compaction is needed based on message count AND estimated token count.
     */
    fun needsCompaction(messages: List<ChatMessage>): Boolean {
        val conversationMessages = messages.size - 1
        if (conversationMessages >= compactionThreshold) return true
        // Also check token estimate for proactive compaction
        val estimatedTokens = estimateTokens(messages)
        val threshold = (maxContextTokens * PROACTIVE_THRESHOLD_RATIO).toInt()
        return estimatedTokens >= threshold
    }

    /**
     * Get a diagnostic string for the compress_chat_probe tool.
     */
    fun getCompactionDiagnostics(messages: List<ChatMessage>): String {
        val conversationMessages = messages.size - 1
        val estimatedTokens = estimateTokens(messages)
        val threshold = (maxContextTokens * PROACTIVE_THRESHOLD_RATIO).toInt()
        val pct = if (maxContextTokens > 0) (estimatedTokens * 100 / maxContextTokens) else 0
        return buildString {
            appendLine("Message count: $conversationMessages (threshold: $compactionThreshold)")
            appendLine("Estimated tokens: $estimatedTokens / $maxContextTokens ($pct%)")
            appendLine("Proactive threshold: $threshold tokens (${"${(PROACTIVE_THRESHOLD_RATIO * 100).toInt()}%"})")
            appendLine("Compaction needed: ${needsCompaction(messages)}")
            if (lastSummary != null) {
                appendLine("Rolling summary: active (${lastSummary!!.length} chars, covers messages up to index $lastCompactedUpTo)")
            } else {
                appendLine("Rolling summary: none")
            }
        }
    }

    /**
     * Compact the conversation history by summarizing old messages.
     *
     * @param messages The full message list (including system message at index 0)
     * @return A new message list with old messages replaced by a summary, or the original if compaction failed
     */
    suspend fun compact(messages: List<ChatMessage>): List<ChatMessage> {
        if (messages.size <= PROTECTED_RECENT + 1) {
            DebugLog.info("ContextCompactor", "Not enough messages to compact (size=${messages.size})")
            return messages
        }

        // System message (index 0) is kept as-is
        val systemMsg = messages[0]

        // Messages to summarize: everything between system and the protected recent window
        val toSummarize = messages.subList(1, messages.size - PROTECTED_RECENT)
        // Recent messages to keep unsummarized
        val recent = messages.subList(messages.size - PROTECTED_RECENT, messages.size)

        DebugLog.info("ContextCompactor", "Compacting ${toSummarize.size} messages, keeping ${recent.size} recent (threshold=$compactionThreshold, maxTokens=$maxContextTokens)")

        val summary = try {
            summarizeSegment(toSummarize)
        } catch (e: Exception) {
            DebugLog.error("ContextCompactor", "Summarization failed: ${e.message}", e)
            // Try fallback compaction before giving up
            val fallback = fallbackCompact(messages, systemMsg, recent)
            if (fallback != null) return fallback
            return messages // Return original on failure
        }

        if (summary.isBlank()) {
            DebugLog.warn("ContextCompactor", "Summary was blank, skipping compaction")
            val fallback = fallbackCompact(messages, systemMsg, recent)
            if (fallback != null) return fallback
            return messages
        }

        // Build summary message, incorporating rolling summary if available
        val summaryContent = if (lastSummary != null) {
            "[Context Summary (rolling)]\n## Previous Summary\n${lastSummary!!.take(1000)}\n\n## New Summary\n$summary"
        } else {
            "[Context Summary]\n$summary"
        }

        val summaryMsg = ChatMessage(
            role = MessageRole.SYSTEM,
            content = summaryContent
        )

        // Update rolling summary state
        lastSummary = summary
        lastCompactedUpTo = messages.size - PROTECTED_RECENT

        DebugLog.info("ContextCompactor", "Compaction complete. Summary length: ${summary.length}")
        DebugLog.info("ContextCompactor", "Summary preview: ${summary.take(200)}")

        return listOf(systemMsg, summaryMsg) + recent
    }

    /**
     * Fallback compaction strategy when LLM summarization fails or doesn't reduce enough.
     * Progressively: (1) reduce protected recent to 4, (2) truncate old messages to 200 chars,
     * (3) drop oldest messages entirely.
     * Returns null if fallback cannot help (too few messages).
     */
    fun fallbackCompact(
        messages: List<ChatMessage>,
        systemMsg: ChatMessage? = null,
        recent: List<ChatMessage>? = null
    ): List<ChatMessage>? {
        val sys = systemMsg ?: messages.firstOrNull() ?: return null
        val shouldDropFirst = systemMsg != null || sys.role == MessageRole.SYSTEM
        val protectedRecent = recent ?: messages.takeLast(FALLBACK_PROTECTED_RECENT)
        val toCompress = messages.drop(if (shouldDropFirst) 1 else 0).dropLast(protectedRecent.size)

        if (toCompress.isEmpty()) return null

        // Keep only the last few old messages (truncated), drop the oldest entirely
        val maxOldToKeep = 5
        val oldToKeep = if (toCompress.size > maxOldToKeep) toCompress.takeLast(maxOldToKeep) else toCompress
        val droppedCount = toCompress.size - oldToKeep.size

        DebugLog.warn("ContextCompactor", "Fallback compaction: keeping ${oldToKeep.size} of ${toCompress.size} old messages (dropped $droppedCount), truncating to $FALLBACK_TRUNCATE_CHARS chars, keeping ${protectedRecent.size} recent")

        val truncated = oldToKeep.map { msg ->
            val content = msg.content ?: ""
            if (content.length > FALLBACK_TRUNCATE_CHARS) {
                msg.copy(content = content.take(FALLBACK_TRUNCATE_CHARS) + "... [truncated by fallback compaction]")
            } else {
                msg
            }
        }

        return listOf(sys) + truncated + protectedRecent
    }

    /**
     * Send a summarization request to the LLM with a structured prompt.
     * Uses rolling summary: if a previous summary exists, includes it and only summarizes new messages.
     */
    private suspend fun summarizeSegment(messages: List<ChatMessage>): String {
        // Check if we hit the cap
        if (messages.size > MAX_MESSAGES_TO_SUMMARIZE) {
            DebugLog.warn("ContextCompactor", "Message count (${messages.size}) exceeds MAX_MESSAGES_TO_SUMMARIZE ($MAX_MESSAGES_TO_SUMMARIZE). Messages beyond this cap will be dropped from the summary!")
        }

        // Build a text representation of the messages to summarize
        val conversationText = messages.take(MAX_MESSAGES_TO_SUMMARIZE).joinToString("\n") { msg ->
            val role = msg.role.name.lowercase()
            val content = msg.content?.take(SUMMARIZE_CONTENT_LIMIT) ?: "[tool_calls]"
            val toolInfo = if (msg.toolCalls != null) " [tools: ${msg.toolCalls.joinToString { it.function.name }}]" else ""
            "[$role]$toolInfo: $content"
        }

        // Incorporate rolling summary if available
        val rollingSummarySection = if (lastSummary != null) {
            "## Previous Context Summary\nThe following is a summary of earlier conversation that has already been compacted. Preserve the key information from it:\n\n${lastSummary!!.take(2000)}\n\n"
        } else {
            ""
        }

        val summarizationPrompt = """
You are a context compaction assistant. Summarize the following conversation segment for an autonomous coding agent.
${if (rollingSummarySection.isNotBlank()) rollingSummarySection else ""}Produce a concise summary with these sections:

## Current Task State
What is the agent currently working on? What is the overall goal?

## Key Files
List the important files that have been read, modified, or discussed.

## Decisions & Constraints
What decisions have been made? What constraints or user preferences should be remembered?

## Tool Outcomes
Summarize the key results from tool executions (file reads, searches, commands, etc.).

## Dropped Context
Note any information that was discussed but is no longer relevant and can be safely forgotten.

Keep the summary concise but preserve all critical information the agent needs to continue its task.

--- CONVERSATION SEGMENT ---
$conversationText
--- END SEGMENT ---
        """.trimIndent()

        val summarizationMessages = listOf(
            ChatMessage(MessageRole.SYSTEM, "You are a context compaction assistant. You produce concise, structured summaries of conversation segments."),
            ChatMessage(MessageRole.USER, summarizationPrompt)
        )

        DebugLog.info("ContextCompactor", "Sending summarization request (${summarizationMessages.size} messages, ${conversationText.length} chars, rolling=${lastSummary != null})")

        val response = client.chat(summarizationMessages, tools = null)
        return response.content ?: ""
    }

    /**
     * Reset the rolling summary state (e.g. when a new session starts).
     */
    fun resetRollingSummary() {
        lastSummary = null
        lastCompactedUpTo = 0
    }
}
