package com.aiagent.chat.agent

import com.aiagent.chat.model.ChatMessage
import com.aiagent.chat.model.MessageRole
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for ContextCompactor.
 * Tests the pure logic (needsCompaction, compact boundary conditions) without making API calls.
 */
class ContextCompactorTest {

    @Test
    fun `needsCompaction returns false for small message lists`() {
        val compactor = ContextCompactor(createMockClient())
        val messages = (1..5).map { ChatMessage(MessageRole.USER, "msg $it") }
        assertFalse(compactor.needsCompaction(messages))
    }

    @Test
    fun `needsCompaction returns true when conversation exceeds threshold`() {
        val compactor = ContextCompactor(createMockClient())
        // COMPACTION_THRESHOLD = 20, so 21 total messages (1 system + 20 conversation) should trigger
        val messages = listOf(ChatMessage(MessageRole.SYSTEM, "system")) +
            (1..20).map { ChatMessage(MessageRole.USER, "msg $it") }
        assertTrue(compactor.needsCompaction(messages))
    }

    @Test
    fun `needsCompaction subtracts system message from count`() {
        val compactor = ContextCompactor(createMockClient())
        // 21 messages total = 1 system + 20 conversation = exactly at threshold
        val messages = listOf(ChatMessage(MessageRole.SYSTEM, "system")) +
            (1..20).map { ChatMessage(MessageRole.USER, "msg $it") }
        assertTrue(compactor.needsCompaction(messages))

        // 20 messages total = 1 system + 19 conversation = below threshold
        val smallMessages = listOf(ChatMessage(MessageRole.SYSTEM, "system")) +
            (1..19).map { ChatMessage(MessageRole.USER, "msg $it") }
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

    // --- Constants ---

    @Test
    fun `COMPACTION_THRESHOLD is 20`() {
        assertEquals(20, ContextCompactor.COMPACTION_THRESHOLD)
    }

    @Test
    fun `PROTECTED_RECENT is 8`() {
        assertEquals(8, ContextCompactor.PROTECTED_RECENT)
    }

    @Test
    fun `MAX_MESSAGES_TO_SUMMARIZE is 50`() {
        assertEquals(50, ContextCompactor.MAX_MESSAGES_TO_SUMMARIZE)
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
