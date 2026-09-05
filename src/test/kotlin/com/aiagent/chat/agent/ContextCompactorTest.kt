package com.aiagent.chat.agent

import com.aiagent.chat.model.ChatMessage
import com.aiagent.chat.model.MessageRole
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for ContextCompactor.
 * Tests the pure logic (needsCompaction, compact boundary conditions, token estimation,
 * fallback compaction, rolling summary) without making API calls.
 */
class ContextCompactorTest {

    // --- needsCompaction ---

    @Test
    fun `needsCompaction returns false for small message lists`() {
        val compactor = ContextCompactor(createMockClient())
        val messages = (1..5).map { ChatMessage(MessageRole.USER, "msg $it") }
        assertFalse(compactor.needsCompaction(messages))
    }

    @Test
    fun `needsCompaction returns true when conversation exceeds threshold`() {
        val compactor = ContextCompactor(createMockClient(), maxContextTokens = 32768)
        // Dynamic threshold for 32768 tokens = 32768/2000 = 16, coerced to [10,100] = 16
        // So 17 total messages (1 system + 16 conversation) should trigger
        val messages = listOf(ChatMessage(MessageRole.SYSTEM, "system")) +
            (1..16).map { ChatMessage(MessageRole.USER, "msg $it") }
        assertTrue(compactor.needsCompaction(messages))
    }

    @Test
    fun `needsCompaction subtracts system message from count`() {
        val compactor = ContextCompactor(createMockClient(), maxContextTokens = 32768)
        // threshold = 16, so 17 total = 1 system + 16 conversation = exactly at threshold
        val messages = listOf(ChatMessage(MessageRole.SYSTEM, "system")) +
            (1..16).map { ChatMessage(MessageRole.USER, "msg $it") }
        assertTrue(compactor.needsCompaction(messages))

        // 16 total = 1 system + 15 conversation = below threshold
        val smallMessages = listOf(ChatMessage(MessageRole.SYSTEM, "system")) +
            (1..15).map { ChatMessage(MessageRole.USER, "msg $it") }
        assertFalse(compactor.needsCompaction(smallMessages))
    }

    @Test
    fun `needsCompaction returns false for empty list`() {
        val compactor = ContextCompactor(createMockClient())
        assertFalse(compactor.needsCompaction(emptyList()))
    }

    @Test
    fun `needsCompaction returns false for single message`() {
        val compactor = ContextCompactor(createMockClient())
        assertFalse(compactor.needsCompaction(listOf(ChatMessage(MessageRole.SYSTEM, "system"))))
    }

    @Test
    fun `needsCompaction triggers on token estimate even with few messages`() {
        // With a small maxContextTokens, a few long messages should trigger proactive compaction
        val compactor = ContextCompactor(createMockClient(), maxContextTokens = 800)
        // threshold = 800/2000 = 0.4, coerced to 10
        // Proactive threshold = 800 * 0.80 = 640 tokens
        // System msg: "system" = 6 chars + 10 overhead = 16 chars / 4 = 4 tokens
        // Each user msg: 500 chars + 10 overhead = 510 chars / 4 = 127 tokens
        // Total: 4 + 6*127 = 766 tokens > 640
        val messages = listOf(ChatMessage(MessageRole.SYSTEM, "system")) +
            (1..6).map { ChatMessage(MessageRole.USER, "x".repeat(500)) }
        assertTrue(compactor.needsCompaction(messages))
    }

    // --- Token estimation ---

    @Test
    fun `estimateTokens returns positive for non-empty messages`() {
        val compactor = ContextCompactor(createMockClient())
        val messages = listOf(ChatMessage(MessageRole.USER, "hello world"))
        assertTrue(compactor.estimateTokens(messages) > 0)
    }

    @Test
    fun `estimateTokens returns 0 for empty list`() {
        val compactor = ContextCompactor(createMockClient())
        assertEquals(0, compactor.estimateTokens(emptyList()))
    }

    @Test
    fun `estimateTokens scales with content length`() {
        val compactor = ContextCompactor(createMockClient())
        val short = listOf(ChatMessage(MessageRole.USER, "short"))
        val long = listOf(ChatMessage(MessageRole.USER, "x".repeat(4000)))
        assertTrue(compactor.estimateTokens(long) > compactor.estimateTokens(short))
    }

    // --- Dynamic threshold ---

    @Test
    fun `compactionThreshold scales with maxContextTokens`() {
        val small = ContextCompactor(createMockClient(), maxContextTokens = 8000)
        val medium = ContextCompactor(createMockClient(), maxContextTokens = 32768)
        val large = ContextCompactor(createMockClient(), maxContextTokens = 128000)
        // threshold = maxContextTokens / 2000, coerced to [10, 100]
        assertEquals(10, small.compactionThreshold)   // 8000/2000=4, coerced to 10
        assertEquals(16, medium.compactionThreshold)  // 32768/2000=16
        assertEquals(64, large.compactionThreshold)   // 128000/2000=64
    }

    @Test
    fun `compactionThreshold is at least 10`() {
        val compactor = ContextCompactor(createMockClient(), maxContextTokens = 1000)
        assertEquals(10, compactor.compactionThreshold)
    }

    @Test
    fun `compactionThreshold is at most 100`() {
        val compactor = ContextCompactor(createMockClient(), maxContextTokens = 500000)
        assertEquals(100, compactor.compactionThreshold)
    }

    // --- Constants ---

    @Test
    fun `PROTECTED_RECENT is 8`() {
        assertEquals(8, ContextCompactor.PROTECTED_RECENT)
    }

    @Test
    fun `MAX_MESSAGES_TO_SUMMARIZE is 50`() {
        assertEquals(50, ContextCompactor.MAX_MESSAGES_TO_SUMMARIZE)
    }

    @Test
    fun `SUMMARIZE_CONTENT_LIMIT is 2000`() {
        assertEquals(2000, ContextCompactor.SUMMARIZE_CONTENT_LIMIT)
    }

    // --- Fallback compaction ---

    @Test
    fun `fallbackCompact reduces message count by dropping oldest messages`() {
        val compactor = ContextCompactor(createMockClient())
        val messages = listOf(ChatMessage(MessageRole.SYSTEM, "system")) +
            (1..20).map { ChatMessage(MessageRole.USER, "x".repeat(1000)) }
        val fallback = compactor.fallbackCompact(messages)
        assertNotNull(fallback)
        // Fallback keeps system + max 5 old + 4 recent = 10, vs original 21
        assertTrue(fallback!!.size < messages.size)
    }

    @Test
    fun `fallbackCompact truncates old message content`() {
        val compactor = ContextCompactor(createMockClient())
        val messages = listOf(ChatMessage(MessageRole.SYSTEM, "system")) +
            (1..20).map { ChatMessage(MessageRole.USER, "x".repeat(1000)) }
        val fallback = compactor.fallbackCompact(messages)
        assertNotNull(fallback)
        // Old messages (not system, not in protected recent) should be truncated
        // Find a message that was truncated (not system, not in last 4 recent)
        val oldMsg = fallback!![1] // First message after system
        assertTrue(oldMsg.content!!.length < 1000)
        assertTrue(oldMsg.content!!.contains("[truncated by fallback compaction]"))
    }

    @Test
    fun `fallbackCompact returns null for too few messages`() {
        val compactor = ContextCompactor(createMockClient())
        val messages = listOf(ChatMessage(MessageRole.SYSTEM, "system"))
        assertNull(compactor.fallbackCompact(messages))
    }

    // --- Diagnostics ---

    @Test
    fun `getCompactionDiagnostics returns non-empty string`() {
        val compactor = ContextCompactor(createMockClient(), maxContextTokens = 32768)
        val messages = listOf(ChatMessage(MessageRole.SYSTEM, "system")) +
            (1..5).map { ChatMessage(MessageRole.USER, "msg $it") }
        val diag = compactor.getCompactionDiagnostics(messages)
        assertTrue(diag.contains("Message count:"))
        assertTrue(diag.contains("Estimated tokens:"))
        assertTrue(diag.contains("Compaction needed:"))
    }

    @Test
    fun `getCompactionDiagnostics shows rolling summary status`() {
        val compactor = ContextCompactor(createMockClient(), maxContextTokens = 32768)
        val messages = listOf(ChatMessage(MessageRole.SYSTEM, "system")) +
            (1..5).map { ChatMessage(MessageRole.USER, "msg $it") }
        val diag = compactor.getCompactionDiagnostics(messages)
        assertTrue(diag.contains("Rolling summary:"))
    }

    // --- Rolling summary reset ---

    @Test
    fun `resetRollingSummary does not throw`() {
        val compactor = ContextCompactor(createMockClient())
        compactor.resetRollingSummary()
    }

    /**
     * Create a mock ApiClient that won't actually be called for needsCompaction tests.
     * Uses a dummy URL and key.
     */
    private fun createMockClient(): com.aiagent.chat.net.ApiClient {
        return com.aiagent.chat.net.ApiClient(
            baseUrl = "http://localhost:99999",
            apiKey = "test-key",
            model = "test-model"
        )
    }
}
