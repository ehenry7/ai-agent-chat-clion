package com.aiagent.chat.net

import com.aiagent.chat.model.AuthHeaderType
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for ApiClient internal logic — retry classification, context limit detection,
 * thinking-tag processing, and partial tag matching.
 * Uses reflection to access private methods (same pattern as ApiClientSecurityTest).
 */
class ApiClientLogicTest {

    private val client = ApiClient(baseUrl = "http://localhost", apiKey = "test-key")

    // --- isRetriableError ---
    // Note: Int? maps to java.lang.Integer in JVM bytecode, not int.class

    private fun invokeIsRetriableError(e: Throwable, statusCode: Int?): Boolean {
        val method = ApiClient::class.java.getDeclaredMethod(
            "isRetriableError", Throwable::class.java, java.lang.Integer::class.java
        )
        method.isAccessible = true
        return method.invoke(client, e, statusCode) as Boolean
    }

    @Test
    fun `isRetriableError returns true for 429`() {
        assertTrue("429 should be retriable", invokeIsRetriableError(RuntimeException("rate limited"), 429))
    }

    @Test
    fun `isRetriableError returns true for 503`() {
        assertTrue(invokeIsRetriableError(RuntimeException("service unavailable"), 503))
    }

    @Test
    fun `isRetriableError returns true for 502`() {
        assertTrue(invokeIsRetriableError(RuntimeException("bad gateway"), 502))
    }

    @Test
    fun `isRetriableError returns true for 504`() {
        assertTrue(invokeIsRetriableError(RuntimeException("gateway timeout"), 504))
    }

    @Test
    fun `isRetriableError returns true for 408`() {
        assertTrue(invokeIsRetriableError(RuntimeException("timeout"), 408))
    }

    @Test
    fun `isRetriableError returns false for 400`() {
        assertFalse("400 should not be retriable", invokeIsRetriableError(RuntimeException("bad request"), 400))
    }

    @Test
    fun `isRetriableError returns false for 401`() {
        assertFalse(invokeIsRetriableError(RuntimeException("unauthorized"), 401))
    }

    @Test
    fun `isRetriableError returns false for 404`() {
        assertFalse(invokeIsRetriableError(RuntimeException("not found"), 404))
    }

    @Test
    fun `isRetriableError returns true for IOException`() {
        assertTrue("IOException should be retriable", invokeIsRetriableError(java.io.IOException("connection reset"), null))
    }

    @Test
    fun `isRetriableError returns true for message containing 'timed out'`() {
        assertTrue(invokeIsRetriableError(RuntimeException("request timed out"), 500))
    }

    @Test
    fun `isRetriableError returns false for CancellationException`() {
        assertFalse("CancellationException should not be retriable",
            invokeIsRetriableError(kotlinx.coroutines.CancellationException("cancelled"), null))
    }

    @Test
    fun `isRetriableError returns false for ContextLimitException`() {
        assertFalse("ContextLimitException should not be retriable",
            invokeIsRetriableError(ContextLimitException(413, "too large"), 413))
    }

    // --- isContextLimitError ---

    private fun invokeIsContextLimitError(statusCode: Int, body: String): Boolean {
        val method = ApiClient::class.java.getDeclaredMethod(
            "isContextLimitError", Int::class.java, String::class.java
        )
        method.isAccessible = true
        return method.invoke(client, statusCode, body) as Boolean
    }

    @Test
    fun `isContextLimitError returns true for 413`() {
        assertTrue(invokeIsContextLimitError(413, "payload too large"))
    }

    @Test
    fun `isContextLimitError returns true for context_length_exceeded in body`() {
        assertTrue(invokeIsContextLimitError(400, """{"error": "context_length_exceeded"}"""))
    }

    @Test
    fun `isContextLimitError returns true for 'maximum context length' in body`() {
        assertTrue(invokeIsContextLimitError(400, "This model's maximum context length is 8192 tokens"))
    }

    @Test
    fun `isContextLimitError returns true for 'token limit exceeded' in body`() {
        assertTrue(invokeIsContextLimitError(400, "token limit exceeded"))
    }

    @Test
    fun `isContextLimitError returns true for 'context window' in body`() {
        assertTrue(invokeIsContextLimitError(400, "exceeded context window"))
    }

    @Test
    fun `isContextLimitError returns false for normal error`() {
        assertFalse(invokeIsContextLimitError(500, "internal server error"))
    }

    @Test
    fun `isContextLimitError is case insensitive`() {
        assertTrue(invokeIsContextLimitError(400, "CONTEXT_LENGTH_EXCEEDED"))
    }

    // --- findPartialTagMatch ---

    private fun invokeFindPartialTagMatch(text: String, tag: String): Int {
        val method = ApiClient::class.java.getDeclaredMethod(
            "findPartialTagMatch", String::class.java, String::class.java
        )
        method.isAccessible = true
        return method.invoke(client, text, tag) as Int
    }

    @Test
    fun `findPartialTagMatch returns 0 when no partial match`() {
        assertEquals(0, invokeFindPartialTagMatch("hello world", "</thinking>"))
    }

    @Test
    fun `findPartialTagMatch returns length of partial match at end`() {
        // "</thi" is a partial match for "</thinking>"
        assertEquals(5, invokeFindPartialTagMatch("some text</thi", "</thinking>"))
    }

    @Test
    fun `findPartialTagMatch returns 0 for empty text`() {
        assertEquals(0, invokeFindPartialTagMatch("", "</thinking>"))
    }

    @Test
    fun `findPartialTagMatch returns 0 when full tag is at end`() {
        // When the full tag is present, the text ends with ">" not with a prefix of the tag,
        // so there is no partial match. The method only detects *partial* (incomplete) tags.
        val result = invokeFindPartialTagMatch("text</thinking>", "</thinking>")
        assertEquals("Full tag at end should not be a partial match", 0, result)
    }

    @Test
    fun `findPartialTagMatch is case insensitive`() {
        val result = invokeFindPartialTagMatch("text</THI", "</thinking>")
        assertTrue("Should be case insensitive, got $result", result > 0)
    }

    // --- processThinkingTags ---
    // TagProcessResult is a private inner class, so we use reflection to read stillInsideThinking.
    // (StreamChunk) -> Unit maps to kotlin.jvm.functions.Function1 in JVM bytecode.

    private fun invokeProcessThinkingTags(buffer: StringBuilder, inside: Boolean): Pair<Boolean, List<StreamChunk>> {
        val method = ApiClient::class.java.getDeclaredMethod(
            "processThinkingTags",
            StringBuilder::class.java, Boolean::class.java, kotlin.jvm.functions.Function1::class.java
        )
        method.isAccessible = true

        val chunks = mutableListOf<StreamChunk>()
        val onChunk: (StreamChunk) -> Unit = { chunks.add(it) }
        val result = method.invoke(client, buffer, inside, onChunk)

        // Read stillInsideThinking via reflection (TagProcessResult is private)
        val resultClass = result!!.javaClass
        val field = resultClass.getDeclaredField("stillInsideThinking")
        field.isAccessible = true
        val stillInside = field.getBoolean(result)

        return stillInside to chunks
    }

    @Test
    fun `processThinkingTags emits content when no thinking tags`() {
        val (stillInside, chunks) = invokeProcessThinkingTags(StringBuilder("Hello world"), false)
        assertFalse(stillInside)
        assertEquals(1, chunks.size)
        assertTrue(chunks[0] is StreamChunk.Content)
        assertEquals("Hello world", (chunks[0] as StreamChunk.Content).text)
    }

    @Test
    fun `processThinkingTags emits reasoning inside thinking tags`() {
        val (stillInside, chunks) = invokeProcessThinkingTags(
            StringBuilder("<thinking>reasoning here</thinking>after"), false
        )
        assertFalse(stillInside)
        assertTrue(chunks.any { it is StreamChunk.Reasoning && it.text == "reasoning here" })
        assertTrue(chunks.any { it is StreamChunk.Content && it.text == "after" })
    }

    @Test
    fun `processThinkingTags returns stillInside true when closing tag missing`() {
        val (stillInside, chunks) = invokeProcessThinkingTags(StringBuilder("still thinking..."), true)
        assertTrue("Should still be inside thinking", stillInside)
        assertTrue(chunks.any { it is StreamChunk.Reasoning })
    }

    @Test
    fun `processThinkingTags handles empty buffer`() {
        val (stillInside, chunks) = invokeProcessThinkingTags(StringBuilder(""), false)
        assertFalse(stillInside)
        assertTrue(chunks.isEmpty())
    }

    @Test
    fun `processThinkingTags handles multiple thinking blocks`() {
        val (stillInside, chunks) = invokeProcessThinkingTags(
            StringBuilder("<thinking>r1</thinking>text1<thinking>r2</thinking>text2"), false
        )
        assertFalse(stillInside)
        assertEquals(4, chunks.size)
        assertTrue(chunks[0] is StreamChunk.Reasoning)
        assertEquals("r1", (chunks[0] as StreamChunk.Reasoning).text)
        assertTrue(chunks[1] is StreamChunk.Content)
        assertEquals("text1", (chunks[1] as StreamChunk.Content).text)
        assertTrue(chunks[2] is StreamChunk.Reasoning)
        assertEquals("r2", (chunks[2] as StreamChunk.Reasoning).text)
        assertTrue(chunks[3] is StreamChunk.Content)
        assertEquals("text2", (chunks[3] as StreamChunk.Content).text)
    }

    @Test
    fun `processThinkingTags is case insensitive for thinking tags`() {
        val (stillInside, chunks) = invokeProcessThinkingTags(
            StringBuilder("<THINKING>reasoning</THINKING>content"), false
        )
        assertFalse(stillInside)
        assertTrue(chunks.any { it is StreamChunk.Reasoning && it.text == "reasoning" })
        assertTrue(chunks.any { it is StreamChunk.Content && it.text == "content" })
    }

    @Test
    fun `processThinkingTags keeps partial closing tag in buffer`() {
        val (stillInside, chunks) = invokeProcessThinkingTags(
            StringBuilder("reasoning here</thin"), true
        )
        assertTrue("Should still be inside thinking (partial closing tag)", stillInside)
        assertTrue(chunks.any { it is StreamChunk.Reasoning && it.text == "reasoning here" })
    }

    // --- StreamChunk sealed class ---

    @Test
    fun `StreamChunk Content holds text`() {
        val chunk = StreamChunk.Content("hello")
        assertEquals("hello", chunk.text)
    }

    @Test
    fun `StreamChunk Reasoning holds text`() {
        val chunk = StreamChunk.Reasoning("thinking...")
        assertEquals("thinking...", chunk.text)
    }

    @Test
    fun `StreamChunk ToolCallDelta holds name and arguments`() {
        val chunk = StreamChunk.ToolCallDelta("write_file", "{\"path\":\"test.kt\"}")
        assertEquals("write_file", chunk.toolName)
        assertEquals("{\"path\":\"test.kt\"}", chunk.argumentsDelta)
    }

    // --- ApiException ---

    @Test
    fun `ApiException stores status code and message`() {
        val ex = ApiException(429, "rate limited")
        assertEquals(429, ex.statusCode)
        assertEquals("rate limited", ex.message)
    }

    // --- ContextLimitException ---

    @Test
    fun `ContextLimitException stores status code and message`() {
        val ex = ContextLimitException(413, "too large")
        assertEquals(413, ex.statusCode)
        assertEquals("too large", ex.message)
    }

    // --- ApiClient constructor defaults ---

    @Test
    fun `ApiClient has default authHeaderType BEARER`() {
        val c = ApiClient(baseUrl = "http://localhost", apiKey = "key")
        assertEquals(AuthHeaderType.BEARER, c.authHeaderType)
    }

    @Test
    fun `ApiClient accepts X_API_KEY authHeaderType`() {
        val c = ApiClient(baseUrl = "http://localhost", apiKey = "key", authHeaderType = AuthHeaderType.X_API_KEY)
        assertEquals(AuthHeaderType.X_API_KEY, c.authHeaderType)
    }

    @Test
    fun `ApiClient exposes baseUrl, apiKey, model as public fields`() {
        val c = ApiClient(baseUrl = "http://test:9999", apiKey = "secret", model = "test-model")
        assertEquals("http://test:9999", c.baseUrl)
        assertEquals("secret", c.apiKey)
        assertEquals("test-model", c.model)
    }
}
