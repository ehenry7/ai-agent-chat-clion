package com.aiagent.chat.model

import kotlinx.serialization.*
import kotlinx.serialization.json.*

@Serializable
enum class MessageRole {
    @SerialName("system") SYSTEM,
    @SerialName("user") USER,
    @SerialName("assistant") ASSISTANT,
    @SerialName("tool") TOOL
}

/**
 * Tool category for approval routing.
 * Inspired by refact-main's tool dependency/category declarations.
 *
 *  READ_ONLY   - No approval needed (read_file, list_directory, search, etc.)
 *  MUTATING    - Requires approval (write_file, edit_file, apply_patch, etc.)
 *  DANGEROUS   - Always requires approval, no auto-approve (run_command with destructive ops)
 */
enum class ToolCategory {
    READ_ONLY,
    MUTATING,
    DANGEROUS
}

@Serializable
data class FunctionCall(
    val name: String,
    val arguments: String
)

@Serializable
data class ToolCall(
    val id: String = "",
    val type: String = "function",
    val function: FunctionCall
)

@Serializable
data class ChatMessage(
    val role: MessageRole,
    val content: String? = null,
    val name: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null
)

@Serializable
data class ToolFunctionDef(
    val name: String,
    val description: String,
    val parameters: JsonObject
)

@Serializable
data class ToolDefinition(
    val type: String = "function",
    val function: ToolFunctionDef
)

/**
 * Pairs a tool definition with its safety category.
 * This is the single source of truth for tool metadata — the category travels
 * with the definition so there's no separate lookup map that can get out of sync.
 *
 * Inspired by refact-main's tool registry with per-tool dependency/category declarations.
 */
data class ToolDeclaration(
    val definition: ToolDefinition,
    val category: ToolCategory
) {
    val name: String get() = definition.function.name
}

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolDefinition>? = null,
    val stream: Boolean = false
)

// --- Streaming models (Phase 9) ---

@Serializable
data class StreamDelta(
    val role: String? = null,
    val content: String? = null,
    /** Reasoning/thinking content from models that support it (e.g. DeepSeek, GLM with thinking mode). */
    val reasoning: String? = null,
    @SerialName("tool_calls") val toolCalls: List<StreamToolCall>? = null
)

@Serializable
data class StreamToolCall(
    val index: Int = 0,
    val id: String? = null,
    val type: String? = null,
    val function: StreamToolCallFunction? = null
)

@Serializable
data class StreamToolCallFunction(
    val name: String? = null,
    val arguments: String? = null
)

@Serializable
data class StreamChoice(
    val index: Int = 0,
    val delta: StreamDelta? = null,
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
data class ChatCompletionChunk(
    val id: String? = null,
    val choices: List<StreamChoice> = emptyList()
)

@Serializable
data class ChatChoice(
    val index: Int = 0,
    val message: ChatMessage,
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
data class ChatCompletionResponse(
    val choices: List<ChatChoice>
)

@Serializable
data class ModelItem(val id: String)

@Serializable
data class ModelsListResponse(
    val data: List<ModelItem> = emptyList()
)

@Serializable
data class TodoItem(
    val id: String,
    val content: String,
    val status: String // "pending", "in_progress", "completed"
)

@Serializable
data class UiLogEntry(
    val role: String,
    val text: String,
    val title: String? = null
)

@Serializable
data class SessionState(
    val version: Int = 1,
    val history: List<ChatMessage> = emptyList(),
    val uiLog: List<UiLogEntry> = emptyList(),
    val todoList: List<TodoItem> = emptyList(),
    val selectedModel: String = "",
    val savedAt: Long = 0L,
    val chatId: String? = null,
    val chatName: String? = null,
    val createdAt: Long? = null,
    val updatedAt: Long? = null
)

@Serializable
data class ChatMeta(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val model: String
)
