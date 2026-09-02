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

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolDefinition>? = null
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
