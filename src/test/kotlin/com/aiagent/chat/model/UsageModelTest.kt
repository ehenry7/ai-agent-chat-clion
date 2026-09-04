package com.aiagent.chat.model

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for Usage and related model serialization.
 * Verifies that token usage data deserializes correctly from OpenAI-compatible API responses.
 */
class UsageModelTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `Usage deserializes from standard OpenAI format`() {
        val raw = """{"prompt_tokens": 100, "completion_tokens": 50, "total_tokens": 150}"""
        val usage = json.decodeFromString<Usage>(raw)
        assertEquals(100, usage.promptTokens)
        assertEquals(50, usage.completionTokens)
        assertEquals(150, usage.totalTokens)
    }

    @Test
    fun `Usage deserializes with cache tokens`() {
        val raw = """{"prompt_tokens": 100, "completion_tokens": 50, "total_tokens": 150, "cache_creation_input_tokens": 30, "cache_read_input_tokens": 70}"""
        val usage = json.decodeFromString<Usage>(raw)
        assertEquals(100, usage.promptTokens)
        assertEquals(30, usage.cacheCreationInputTokens)
        assertEquals(70, usage.cacheReadInputTokens)
    }

    @Test
    fun `Usage totalInputTokens equals prompt plus cache tokens`() {
        val usage = Usage(
            promptTokens = 100,
            completionTokens = 50,
            totalTokens = 150,
            cacheCreationInputTokens = 30,
            cacheReadInputTokens = 70
        )
        assertEquals(200, usage.totalInputTokens)
    }

    @Test
    fun `Usage with zero cache tokens has totalInputTokens equal to promptTokens`() {
        val usage = Usage(promptTokens = 500, completionTokens = 100, totalTokens = 600)
        assertEquals(500, usage.totalInputTokens)
    }

    @Test
    fun `Usage defaults to zero when fields missing`() {
        val raw = """{}"""
        val usage = json.decodeFromString<Usage>(raw)
        assertEquals(0, usage.promptTokens)
        assertEquals(0, usage.completionTokens)
        assertEquals(0, usage.totalTokens)
        assertEquals(0, usage.cacheCreationInputTokens)
        assertEquals(0, usage.cacheReadInputTokens)
    }

    @Test
    fun `Usage ignores unknown fields`() {
        val raw = """{"prompt_tokens": 100, "completion_tokens": 50, "total_tokens": 150, "extra_field": "ignored"}"""
        val usage = json.decodeFromString<Usage>(raw)
        assertEquals(100, usage.promptTokens)
    }

    @Test
    fun `ChatCompletionResponse deserializes with usage`() {
        val raw = """{"choices":[{"index":0,"message":{"role":"assistant","content":"hello"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}"""
        val response = json.decodeFromString<ChatCompletionResponse>(raw)
        assertNotNull(response.usage)
        assertEquals(10, response.usage!!.promptTokens)
        assertEquals(5, response.usage!!.completionTokens)
        assertEquals(15, response.usage!!.totalTokens)
    }

    @Test
    fun `ChatCompletionResponse deserializes without usage`() {
        val raw = """{"choices":[{"index":0,"message":{"role":"assistant","content":"hello"},"finish_reason":"stop"}]}"""
        val response = json.decodeFromString<ChatCompletionResponse>(raw)
        assertNull(response.usage)
    }

    @Test
    fun `ChatMessage with usage serializes and deserializes`() {
        val msg = ChatMessage(
            role = MessageRole.ASSISTANT,
            content = "response",
            usage = Usage(promptTokens = 100, completionTokens = 50, totalTokens = 150)
        )
        val encoded = json.encodeToString(ChatMessage.serializer(), msg)
        val decoded = json.decodeFromString<ChatMessage>(encoded)
        assertNotNull(decoded.usage)
        assertEquals(100, decoded.usage!!.promptTokens)
    }

    @Test
    fun `ChatMessage without usage has null usage field`() {
        val msg = ChatMessage(role = MessageRole.USER, content = "hello")
        val encoded = json.encodeToString(ChatMessage.serializer(), msg)
        val decoded = json.decodeFromString<ChatMessage>(encoded)
        assertNull(decoded.usage)
    }

    // --- ToolCategory enum ---

    @Test
    fun `ToolCategory has exactly 3 values`() {
        assertEquals(3, ToolCategory.entries.size)
    }

    @Test
    fun `ToolCategory values are READ_ONLY MUTATING DANGEROUS`() {
        assertEquals(ToolCategory.READ_ONLY, ToolCategory.valueOf("READ_ONLY"))
        assertEquals(ToolCategory.MUTATING, ToolCategory.valueOf("MUTATING"))
        assertEquals(ToolCategory.DANGEROUS, ToolCategory.valueOf("DANGEROUS"))
    }

    // --- ToolDeclaration ---

    @Test
    fun `ToolDeclaration name shortcut returns function name`() {
        val decl = ToolDeclaration(
            definition = ToolDefinition(
                function = ToolFunctionDef(
                    name = "test_tool",
                    description = "A test tool",
                    parameters = kotlinx.serialization.json.buildJsonObject { }
                )
            ),
            category = ToolCategory.READ_ONLY
        )
        assertEquals("test_tool", decl.name)
    }
}
