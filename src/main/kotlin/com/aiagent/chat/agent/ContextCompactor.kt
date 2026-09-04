package com.aiagent.chat.agent

import com.aiagent.chat.debug.DebugLog
import com.aiagent.chat.model.ChatMessage
import com.aiagent.chat.model.MessageRole
import com.aiagent.chat.net.ApiClient
import kotlinx.serialization.json.Json

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
 */
class ContextCompactor(
    private val client: ApiClient
) {
    companion object {
        /** Compact when message count exceeds this threshold. */
        const val COMPACTION_THRESHOLD = 20
        /** Number of recent messages to keep unsummarized. */
        const val PROTECTED_RECENT = 8
        /** Max messages to include in the summarization request (to avoid the compaction call itself being too large). */
        const val MAX_MESSAGES_TO_SUMMARIZE = 50
    }

    /**
     * Check if compaction is needed based on message count.
     */
    fun needsCompaction(messages: List<ChatMessage>): Boolean {
        // Subtract 1 for the system message at index 0
        val conversationMessages = messages.size - 1
        return conversationMessages >= COMPACTION_THRESHOLD
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

        DebugLog.info("ContextCompactor", "Compacting ${toSummarize.size} messages, keeping ${recent.size} recent")

        val summary = try {
            summarizeSegment(toSummarize)
        } catch (e: Exception) {
            DebugLog.error("ContextCompactor", "Summarization failed: ${e.message}", e)
            return messages // Return original on failure
        }

        if (summary.isBlank()) {
            DebugLog.warn("ContextCompactor", "Summary was blank, skipping compaction")
            return messages
        }

        val summaryMsg = ChatMessage(
            role = MessageRole.SYSTEM,
            content = "[Context Summary]\n$summary"
        )

        DebugLog.info("ContextCompactor", "Compaction complete. Summary length: ${summary.length}")
        DebugLog.info("ContextCompactor", "Summary preview: ${summary.take(200)}")

        return listOf(systemMsg, summaryMsg) + recent
    }

    /**
     * Send a summarization request to the LLM with a structured prompt.
     */
    private suspend fun summarizeSegment(messages: List<ChatMessage>): String {
        // Build a text representation of the messages to summarize
        val conversationText = messages.take(MAX_MESSAGES_TO_SUMMARIZE).joinToString("\n") { msg ->
            val role = msg.role.name.lowercase()
            val content = msg.content?.take(500) ?: "[tool_calls]"
            val toolInfo = if (msg.toolCalls != null) " [tools: ${msg.toolCalls.joinToString { it.function.name }}]" else ""
            "[$role]$toolInfo: $content"
        }

        val summarizationPrompt = """
You are a context compaction assistant. Summarize the following conversation segment for an autonomous coding agent.
Produce a concise summary with these sections:

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

        DebugLog.info("ContextCompactor", "Sending summarization request (${summarizationMessages.size} messages, ${conversationText.length} chars)")

        val response = client.chat(summarizationMessages, tools = null)
        return response.content ?: ""
    }
}
