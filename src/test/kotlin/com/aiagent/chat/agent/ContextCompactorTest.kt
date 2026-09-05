package com.aiagent.chat.agent

import com.aiagent.chat.model.ChatMessage
import com.aiagent.chat.model.FunctionCall
import com.aiagent.chat.model.MessageRole
import com.aiagent.chat.model.ToolCall
import kotlinx.coroutines.runBlocking
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

    @Test
    fun `needsCompaction does not trigger when below both thresholds`() {
        val compactor = ContextCompactor(createMockClient(), maxContextTokens = 32768)
        // threshold = 16 messages, proactive token threshold = 26214 tokens
        // 5 short messages = well below both
        val messages = listOf(ChatMessage(MessageRole.SYSTEM, "system")) +
            (1..4).map { ChatMessage(MessageRole.USER, "short msg $it") }
        assertFalse(compactor.needsCompaction(messages))
    }

    @Test
    fun `needsCompaction triggers on message count even with tiny token estimate`() {
        val compactor = ContextCompactor(createMockClient(), maxContextTokens = 20000)
        // threshold = 20000/2000 = 10
        // 11 total messages (1 system + 10 conversation) with tiny content
        val messages = listOf(ChatMessage(MessageRole.SYSTEM, "s")) +
            (1..10).map { ChatMessage(MessageRole.USER, "x") }
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

    @Test
    fun `estimateTokens accounts for tool calls`() {
        val compactor = ContextCompactor(createMockClient())
        val noTools = listOf(ChatMessage(MessageRole.USER, "hello"))
        val withTools = listOf(
            ChatMessage(
                role = MessageRole.ASSISTANT,
                content = "hello",
                toolCalls = listOf(
                    ToolCall(id = "tc1", function = FunctionCall(name = "read_file", arguments = "{\"path\":\"/test/file.txt\"}"))
                )
            )
        )
        // Tool call adds: name.length(9) + arguments.length(24) + 20 = 53 extra chars
        assertTrue(compactor.estimateTokens(withTools) > compactor.estimateTokens(noTools))
    }

    @Test
    fun `estimateTokens accounts for multiple tool calls`() {
        val compactor = ContextCompactor(createMockClient())
        val oneTool = listOf(
            ChatMessage(
                role = MessageRole.ASSISTANT,
                content = "x",
                toolCalls = listOf(ToolCall(function = FunctionCall(name = "a", arguments = "{}")))
            )
        )
        val twoTools = listOf(
            ChatMessage(
                role = MessageRole.ASSISTANT,
                content = "x",
                toolCalls = listOf(
                    ToolCall(function = FunctionCall(name = "a", arguments = "{}")),
                    ToolCall(function = FunctionCall(name = "b", arguments = "{}"))
                )
            )
        )
        assertTrue(compactor.estimateTokens(twoTools) > compactor.estimateTokens(oneTool))
    }

    @Test
    fun `estimateTokens handles null content`() {
        val compactor = ContextCompactor(createMockClient())
        val messages = listOf(ChatMessage(MessageRole.ASSISTANT, content = null, toolCalls = listOf(ToolCall(function = FunctionCall(name = "test", arguments = "{}")))))
        // Should still count the tool call overhead, not crash
        assertTrue(compactor.estimateTokens(messages) > 0)
    }

    @Test
    fun `estimateTokens includes per-message overhead`() {
        val compactor = ContextCompactor(createMockClient())
        val oneMsg = listOf(ChatMessage(MessageRole.USER, "x"))
        val twoMsgs = listOf(ChatMessage(MessageRole.USER, "x"), ChatMessage(MessageRole.USER, "x"))
        // Two messages should have more tokens due to per-message overhead (10 chars each)
        assertTrue(compactor.estimateTokens(twoMsgs) > compactor.estimateTokens(oneMsg))
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

    @Test
    fun `compactionThreshold uses default 32768 when not specified`() {
        val compactor = ContextCompactor(createMockClient())
        // default maxContextTokens = 32768, threshold = 16
        assertEquals(16, compactor.compactionThreshold)
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

    @Test
    fun `CHARS_PER_TOKEN is 4`() {
        assertEquals(4, ContextCompactor.CHARS_PER_TOKEN)
    }

    @Test
    fun `PROACTIVE_THRESHOLD_RATIO is 0_80`() {
        assertEquals(0.80, ContextCompactor.PROACTIVE_THRESHOLD_RATIO, 0.001)
    }

    @Test
    fun `FALLBACK_PROTECTED_RECENT is 4`() {
        assertEquals(4, ContextCompactor.FALLBACK_PROTECTED_RECENT)
    }

    @Test
    fun `FALLBACK_TRUNCATE_CHARS is 200`() {
        assertEquals(200, ContextCompactor.FALLBACK_TRUNCATE_CHARS)
    }

    // --- compact() boundary conditions ---

    @Test
    fun `compact returns original when too few messages`() {
        val compactor = ContextCompactor(createMockClient())
        // PROTECTED_RECENT + 1 = 9 messages, so 9 should be too few (needs > 9)
        val messages = (1..9).map { ChatMessage(MessageRole.USER, "msg $it") }
        val result = runBlocking { compactor.compact(messages) }
        assertSame(messages, result)
    }

    @Test
    fun `compact returns original for exactly PROTECTED_RECENT plus 1 messages`() {
        val compactor = ContextCompactor(createMockClient())
        // 9 messages = PROTECTED_RECENT(8) + 1, condition is <= so returns original
        val messages = (1..9).map { ChatMessage(MessageRole.USER, "msg $it") }
        val result = runBlocking { compactor.compact(messages) }
        assertSame(messages, result)
    }

    @Test
    fun `compact returns original for empty list`() {
        val compactor = ContextCompactor(createMockClient())
        val result = runBlocking { compactor.compact(emptyList()) }
        assertTrue(result.isEmpty())
    }

    @Test
    fun `compact returns original for single message`() {
        val compactor = ContextCompactor(createMockClient())
        val messages = listOf(ChatMessage(MessageRole.SYSTEM, "system"))
        val result = runBlocking { compactor.compact(messages) }
        assertSame(messages, result)
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

    @Test
    fun `fallbackCompact returns null for empty list`() {
        val compactor = ContextCompactor(createMockClient())
        assertNull(compactor.fallbackCompact(emptyList()))
    }

    @Test
    fun `fallbackCompact preserves system message as first element`() {
        val compactor = ContextCompactor(createMockClient())
        val systemMsg = ChatMessage(MessageRole.SYSTEM, "important system prompt")
        val messages = listOf(systemMsg) + (1..20).map { ChatMessage(MessageRole.USER, "x".repeat(1000)) }
        val fallback = compactor.fallbackCompact(messages)
        assertNotNull(fallback)
        assertEquals(MessageRole.SYSTEM, fallback!![0].role)
        assertEquals("important system prompt", fallback[0].content)
    }

    @Test
    fun `fallbackCompact preserves recent messages at the end`() {
        val compactor = ContextCompactor(createMockClient())
        val recentContent = "RECENT_MARKER"
        val messages = listOf(ChatMessage(MessageRole.SYSTEM, "system")) +
            (1..20).map { ChatMessage(MessageRole.USER, "x".repeat(1000)) } +
            (1..4).map { ChatMessage(MessageRole.USER, "$recentContent $it") }
        val fallback = compactor.fallbackCompact(messages)
        assertNotNull(fallback)
        // Last 4 messages should be the recent ones, preserved unmodified
        val lastFour = fallback!!.takeLast(4)
        lastFour.forEach { msg ->
            assertTrue(msg.content!!.contains(recentContent))
            assertFalse(msg.content!!.contains("[truncated"))
        }
    }

    @Test
    fun `fallbackCompact drops oldest messages keeping at most 5`() {
        val compactor = ContextCompactor(createMockClient())
        val messages = listOf(ChatMessage(MessageRole.SYSTEM, "system")) +
            (1..50).map { ChatMessage(MessageRole.USER, "msg $it") }
        val fallback = compactor.fallbackCompact(messages)
        assertNotNull(fallback)
        // system(1) + max 5 old + 4 recent = 10
        assertEquals(10, fallback!!.size)
    }

    @Test
    fun `fallbackCompact keeps all old messages when fewer than 5`() {
        val compactor = ContextCompactor(createMockClient())
        val messages = listOf(ChatMessage(MessageRole.SYSTEM, "system")) +
            (1..3).map { ChatMessage(MessageRole.USER, "x".repeat(1000)) } +
            (1..4).map { ChatMessage(MessageRole.USER, "recent $it") }
        val fallback = compactor.fallbackCompact(messages)
        assertNotNull(fallback)
        // system(1) + 3 old + 4 recent = 8
        assertEquals(8, fallback!!.size)
    }

    @Test
    fun `fallbackCompact does not truncate short messages`() {
        val compactor = ContextCompactor(createMockClient())
        val shortContent = "short"
        val messages = listOf(ChatMessage(MessageRole.SYSTEM, "system")) +
            (1..3).map { ChatMessage(MessageRole.USER, shortContent) } +
            (1..4).map { ChatMessage(MessageRole.USER, "recent $it") }
        val fallback = compactor.fallbackCompact(messages)
        assertNotNull(fallback)
        // Old messages that are short should not be truncated
        val oldMsg = fallback!![1]
        assertEquals(shortContent, oldMsg.content)
    }

    @Test
    fun `fallbackCompact with explicit systemMsg and recent params`() {
        val compactor = ContextCompactor(createMockClient())
        val systemMsg = ChatMessage(MessageRole.SYSTEM, "sys")
        val recent = (1..4).map { ChatMessage(MessageRole.USER, "recent $it") }
        val oldMessages = (1..10).map { ChatMessage(MessageRole.USER, "x".repeat(1000)) }
        val allMessages = listOf(systemMsg) + oldMessages + recent
        val fallback = compactor.fallbackCompact(allMessages, systemMsg, recent)
        assertNotNull(fallback)
        assertTrue(fallback!!.size < allMessages.size)
        assertEquals(MessageRole.SYSTEM, fallback[0].role)
    }

    @Test
    fun `fallbackCompact returns null when only system and recent exist`() {
        val compactor = ContextCompactor(createMockClient())
        val systemMsg = ChatMessage(MessageRole.SYSTEM, "sys")
        val recent = (1..4).map { ChatMessage(MessageRole.USER, "recent $it") }
        val allMessages = listOf(systemMsg) + recent
        // No old messages to compress
        val fallback = compactor.fallbackCompact(allMessages, systemMsg, recent)
        assertNull(fallback)
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

    @Test
    fun `getCompactionDiagnostics shows none for rolling summary initially`() {
        val compactor = ContextCompactor(createMockClient(), maxContextTokens = 32768)
        val messages = listOf(ChatMessage(MessageRole.SYSTEM, "system")) +
            (1..5).map { ChatMessage(MessageRole.USER, "msg $it") }
        val diag = compactor.getCompactionDiagnostics(messages)
        assertTrue(diag.contains("Rolling summary: none"))
    }

    @Test
    fun `getCompactionDiagnostics includes threshold value`() {
        val compactor = ContextCompactor(createMockClient(), maxContextTokens = 32768)
        val messages = listOf(ChatMessage(MessageRole.SYSTEM, "system"))
        val diag = compactor.getCompactionDiagnostics(messages)
        assertTrue(diag.contains("threshold: 16"))
    }

    @Test
    fun `getCompactionDiagnostics includes percentage`() {
        val compactor = ContextCompactor(createMockClient(), maxContextTokens = 32768)
        val messages = listOf(ChatMessage(MessageRole.SYSTEM, "system")) +
            (1..5).map { ChatMessage(MessageRole.USER, "x".repeat(1000)) }
        val diag = compactor.getCompactionDiagnostics(messages)
        assertTrue(diag.contains("%"))
    }

    @Test
    fun `getCompactionDiagnostics includes proactive threshold`() {
        val compactor = ContextCompactor(createMockClient(), maxContextTokens = 32768)
        val messages = listOf(ChatMessage(MessageRole.SYSTEM, "system"))
        val diag = compactor.getCompactionDiagnostics(messages)
        assertTrue(diag.contains("Proactive threshold:"))
        assertTrue(diag.contains("80%"))
    }

    @Test
    fun `getCompactionDiagnostics for empty messages`() {
        val compactor = ContextCompactor(createMockClient(), maxContextTokens = 32768)
        val diag = compactor.getCompactionDiagnostics(emptyList())
        assertTrue(diag.contains("Message count: -1"))
        assertTrue(diag.contains("Estimated tokens: 0"))
    }

    // --- Rolling summary reset ---

    @Test
    fun `resetRollingSummary does not throw`() {
        val compactor = ContextCompactor(createMockClient())
        compactor.resetRollingSummary()
    }

    @Test
    fun `resetRollingSummary clears rolling summary status in diagnostics`() {
        val compactor = ContextCompactor(createMockClient(), maxContextTokens = 32768)
        val messages = listOf(ChatMessage(MessageRole.SYSTEM, "system"))
        // Reset and verify diagnostics shows "none"
        compactor.resetRollingSummary()
        val diag = compactor.getCompactionDiagnostics(messages)
        assertTrue(diag.contains("Rolling summary: none"))
    }

    // --- maxContextTokens property ---

    @Test
    fun `maxContextTokens is accessible and matches constructor value`() {
        val compactor = ContextCompactor(createMockClient(), maxContextTokens = 65536)
        assertEquals(65536, compactor.maxContextTokens)
    }

    @Test
    fun `maxContextTokens defaults to 32768`() {
        val compactor = ContextCompactor(createMockClient())
        assertEquals(32768, compactor.maxContextTokens)
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
