package com.aiagent.chat.model

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for streaming model serialization/deserialization.
 * Verifies that SSE chunk parsing models work correctly with kotlinx.serialization.
 */
class StreamingModelsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `ChatCompletionRequest with stream=true serializes correctly`() {
        val req = ChatCompletionRequest(
            model = "gpt-4",
            messages = listOf(ChatMessage(MessageRole.USER, "hello")),
            stream = true
        )
        val encoded = json.encodeToString(ChatCompletionRequest.serializer(), req)
        assertTrue("stream field should be true", encoded.contains("\"stream\":true"))
    }

    @Test
    fun `ChatCompletionRequest with stream=false is default`() {
        val req = ChatCompletionRequest(
            model = "gpt-4",
            messages = listOf(ChatMessage(MessageRole.USER, "hello"))
        )
        assertFalse(req.stream)
    }

    @Test
    fun `ChatCompletionChunk deserializes content delta`() {
        val raw = """{"id":"chatcmpl-123","choices":[{"index":0,"delta":{"content":"Hello"},"finish_reason":null}]}"""
        val chunk = json.decodeFromString<ChatCompletionChunk>(raw)
        assertEquals("chatcmpl-123", chunk.id)
        assertEquals(1, chunk.choices.size)
        assertEquals("Hello", chunk.choices[0].delta?.content)
        assertNull(chunk.choices[0].finishReason)
    }

    @Test
    fun `ChatCompletionChunk deserializes tool call delta`() {
        val raw = """{"id":"chatcmpl-456","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_abc","type":"function","function":{"name":"read_file","arguments":"{\"path\":"}}]}}]}"""
        val chunk = json.decodeFromString<ChatCompletionChunk>(raw)
        assertEquals(1, chunk.choices.size)
        val tc = chunk.choices[0].delta?.toolCalls?.get(0)
        assertNotNull(tc)
        assertEquals(0, tc!!.index)
        assertEquals("call_abc", tc.id)
        assertEquals("read_file", tc.function?.name)
        assertEquals("{\"path\":", tc.function?.arguments)
    }

    @Test
    fun `ChatCompletionChunk deserializes finish_reason`() {
        val raw = """{"id":"chatcmpl-789","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}"""
        val chunk = json.decodeFromString<ChatCompletionChunk>(raw)
        assertEquals("stop", chunk.choices[0].finishReason)
    }

    @Test
    fun `ChatCompletionChunk handles empty choices array`() {
        val raw = """{"id":"chatcmpl-000","choices":[]}"""
        val chunk = json.decodeFromString<ChatCompletionChunk>(raw)
        assertEquals(0, chunk.choices.size)
    }

    @Test
    fun `ChatCompletionChunk ignores unknown fields`() {
        val raw = """{"id":"x","object":"chat.completion.chunk","created":1234567890,"model":"gpt-4","choices":[{"index":0,"delta":{"content":"Hi"},"finish_reason":null}]}"""
        val chunk = json.decodeFromString<ChatCompletionChunk>(raw)
        assertEquals("Hi", chunk.choices[0].delta?.content)
    }

    @Test
    fun `StreamDelta with only role field deserializes`() {
        val raw = """{"role":"assistant"}"""
        val delta = json.decodeFromString<StreamDelta>(raw)
        assertEquals("assistant", delta.role)
        assertNull(delta.content)
        assertNull(delta.toolCalls)
    }

    @Test
    fun `StreamToolCallFunction with only name deserializes`() {
        val raw = """{"name":"write_file"}"""
        val func = json.decodeFromString<StreamToolCallFunction>(raw)
        assertEquals("write_file", func.name)
        assertNull(func.arguments)
    }
}
