package com.aiagent.chat.agent

import com.aiagent.chat.model.ChatMessage

/**
 * Sealed class representing commands that can be queued for the agent loop.
 *
 * Inspired by refact-main's CommandRequest system.
 *
 * Commands are processed by the CommandQueue in priority order:
 *  - ABORT and STEER are high-priority (inserted at front)
 *  - SEND and TOOL_DECISION are normal-priority (appended at end)
 */
sealed class AgentCommand {
    /** Priority for queue ordering. Lower number = higher priority. */
    abstract val priority: Int

    /**
     * User sends a new message to start or continue a conversation.
     */
    data class Send(
        val message: ChatMessage,
        val displayText: String = message.content ?: "",
        val referencedFiles: List<String> = emptyList()
    ) : AgentCommand() {
        override val priority = 10
    }

    /**
     * User steers the running agent with additional context/instructions.
     * High priority — inserted at front of queue, processed at next loop iteration.
     */
    data class Steer(
        val text: String
    ) : AgentCommand() {
        override val priority = 1
    }

    /**
     * User requests to stop the agent loop entirely.
     * Highest priority — causes immediate interruption of active stream + tool calls.
     */
    object Abort : AgentCommand() {
        override val priority = 0
    }

    /**
     * User requests to regenerate the last response.
     * Removes the last assistant message and re-runs from that point.
     */
    object Regenerate : AgentCommand() {
        override val priority = 5
    }

    /**
     * User makes a decision on pending tool approval(s).
     * Contains accepted/denied tool call IDs with optional deny reasons.
     */
    data class ToolDecision(
        val acceptedToolCallIds: Set<String> = emptySet(),
        val deniedToolCallIds: Map<String, String> = emptyMap(), // id -> deny reason
        val autoApproveSession: Boolean = false
    ) : AgentCommand() {
        override val priority = 2
    }
}
