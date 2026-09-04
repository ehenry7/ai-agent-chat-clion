package com.aiagent.chat.agent

import com.aiagent.chat.model.ChatMessage
import com.aiagent.chat.model.MessageRole
import com.aiagent.chat.model.Usage
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for UsageTracker.
 * Verifies usage recording, summary computation, compaction events, and token map building.
 */
class UsageTrackerTest {

    @Test
    fun `new tracker has no compaction events`() {
        val tracker = UsageTracker(maxContextTokens = 32768)
        assertTrue(tracker.getCompactionEvents().isEmpty())
    }

    @Test
    fun `recordUsage stores usage data`() {
        val tracker = UsageTracker(maxContextTokens = 32768)
        tracker.recordUsage(Usage(promptTokens = 100, completionTokens = 50, totalTokens = 150))
        tracker.recordUsage(Usage(promptTokens = 200, completionTokens = 80, totalTokens = 280))
        // Verify via computeSummary with empty messages - totals should reflect recorded usage
        val summary = tracker.computeSummary(emptyList())
        assertEquals(300, summary.totalInputTokens)  // 100 + 200
        assertEquals(130, summary.totalOutputTokens) // 50 + 80
    }

    @Test
    fun `recordUsage with null does not crash`() {
        val tracker = UsageTracker(maxContextTokens = 32768)
        tracker.recordUsage(null)
        val summary = tracker.computeSummary(emptyList())
        assertEquals(0, summary.totalInputTokens)
    }

    @Test
    fun `computeSummary with empty messages returns zero current tokens`() {
        val tracker = UsageTracker(maxContextTokens = 32768)
        val summary = tracker.computeSummary(emptyList())
        assertEquals(0, summary.currentSessionTokens)
        assertEquals(0.0, summary.percentage, 0.01)
        assertFalse(summary.isWarning)
        assertFalse(summary.isOverflown)
    }

    @Test
    fun `computeSummary finds last assistant message with usage`() {
        val tracker = UsageTracker(maxContextTokens = 32768)
        val messages = listOf(
            ChatMessage(MessageRole.SYSTEM, "system prompt"),
            ChatMessage(MessageRole.USER, "hello"),
            ChatMessage(MessageRole.ASSISTANT, "hi", usage = Usage(promptTokens = 500, completionTokens = 100, totalTokens = 600)),
            ChatMessage(MessageRole.USER, "do something"),
            ChatMessage(MessageRole.ASSISTANT, "done", usage = Usage(promptTokens = 1000, completionTokens = 200, totalTokens = 1200))
        )
        val summary = tracker.computeSummary(messages)
        assertEquals(1000, summary.currentSessionTokens) // totalInputTokens = promptTokens = 1000
    }

    @Test
    fun `computeSummary percentage is correct`() {
        val tracker = UsageTracker(maxContextTokens = 1000)
        val messages = listOf(
            ChatMessage(MessageRole.ASSISTANT, "response", usage = Usage(promptTokens = 850, completionTokens = 50, totalTokens = 900))
        )
        val summary = tracker.computeSummary(messages)
        assertEquals(85.0, summary.percentage, 0.01)
        assertTrue(summary.isWarning)
        assertFalse(summary.isOverflown)
    }

    @Test
    fun `computeSummary isOverflown when percentage at least 97`() {
        val tracker = UsageTracker(maxContextTokens = 1000)
        val messages = listOf(
            ChatMessage(MessageRole.ASSISTANT, "response", usage = Usage(promptTokens = 980, completionTokens = 20, totalTokens = 1000))
        )
        val summary = tracker.computeSummary(messages)
        assertEquals(98.0, summary.percentage, 0.01)
        assertTrue(summary.isWarning)
        assertTrue(summary.isOverflown)
    }

    @Test
    fun `computeSummary isWarning false when percentage below 85`() {
        val tracker = UsageTracker(maxContextTokens = 1000)
        val messages = listOf(
            ChatMessage(MessageRole.ASSISTANT, "response", usage = Usage(promptTokens = 500, completionTokens = 50, totalTokens = 550))
        )
        val summary = tracker.computeSummary(messages)
        assertEquals(50.0, summary.percentage, 0.01)
        assertFalse(summary.isWarning)
        assertFalse(summary.isOverflown)
    }

    @Test
    fun `computeSummary tokenMap is null for empty messages`() {
        val tracker = UsageTracker(maxContextTokens = 32768)
        val summary = tracker.computeSummary(emptyList())
        assertNull(summary.tokenMap)
    }

    @Test
    fun `computeSummary tokenMap is null when no assistant messages have usage`() {
        val tracker = UsageTracker(maxContextTokens = 32768)
        val messages = listOf(
            ChatMessage(MessageRole.SYSTEM, "system"),
            ChatMessage(MessageRole.USER, "hello"),
            ChatMessage(MessageRole.ASSISTANT, "hi")  // no usage field
        )
        val summary = tracker.computeSummary(messages)
        assertNull(summary.tokenMap)
    }

    @Test
    fun `computeSummary tokenMap has segments when assistant has usage`() {
        val tracker = UsageTracker(maxContextTokens = 10000)
        val messages = listOf(
            ChatMessage(MessageRole.SYSTEM, "system prompt here"),
            ChatMessage(MessageRole.USER, "user message here"),
            ChatMessage(MessageRole.ASSISTANT, "assistant response", usage = Usage(promptTokens = 500, completionTokens = 100, totalTokens = 600))
        )
        val summary = tracker.computeSummary(messages)
        assertNotNull(summary.tokenMap)
        assertTrue("TokenMap should have segments", summary.tokenMap!!.segments.isNotEmpty())
    }

    @Test
    fun `computeSummary tokenMap includes free space segment`() {
        val tracker = UsageTracker(maxContextTokens = 10000)
        val messages = listOf(
            ChatMessage(MessageRole.SYSTEM, "system"),
            ChatMessage(MessageRole.ASSISTANT, "response", usage = Usage(promptTokens = 100, completionTokens = 10, totalTokens = 110))
        )
        val summary = tracker.computeSummary(messages)
        assertNotNull(summary.tokenMap)
        val freeSegment = summary.tokenMap!!.segments.find { it.category == "free" }
        assertNotNull("Should have a free space segment", freeSegment)
        assertTrue("Free space should be positive", freeSegment!!.tokens > 0)
    }

    // --- Compaction events ---

    @Test
    fun `recordCompaction stores event`() {
        val tracker = UsageTracker(maxContextTokens = 32768)
        tracker.recordCompaction(messagesBefore = 20, messagesAfter = 10)
        val events = tracker.getCompactionEvents()
        assertEquals(1, events.size)
        assertEquals(20, events[0].messagesBefore)
        assertEquals(10, events[0].messagesAfter)
        assertTrue("Tokens saved should be positive", events[0].tokensSavedEstimate > 0)
    }

    @Test
    fun `recordCompaction stores multiple events`() {
        val tracker = UsageTracker(maxContextTokens = 32768)
        tracker.recordCompaction(20, 10)
        tracker.recordCompaction(15, 8)
        assertEquals(2, tracker.getCompactionEvents().size)
    }

    // --- reset ---

    @Test
    fun `reset clears all tracking data`() {
        val tracker = UsageTracker(maxContextTokens = 32768)
        tracker.recordUsage(Usage(promptTokens = 100, completionTokens = 50, totalTokens = 150))
        tracker.recordCompaction(20, 10)
        tracker.reset()
        val summary = tracker.computeSummary(emptyList())
        assertEquals(0, summary.totalInputTokens)
        assertEquals(0, summary.totalOutputTokens)
        assertTrue(tracker.getCompactionEvents().isEmpty())
    }

    // --- maxContextTokens ---

    @Test
    fun `maxContextTokens is preserved in summary`() {
        val tracker = UsageTracker(maxContextTokens = 8192)
        val summary = tracker.computeSummary(emptyList())
        assertEquals(8192, summary.maxContextTokens)
    }

    // --- Cache tokens ---

    @Test
    fun `computeSummary aggregates cache tokens`() {
        val tracker = UsageTracker(maxContextTokens = 32768)
        tracker.recordUsage(Usage(
            promptTokens = 100,
            completionTokens = 50,
            totalTokens = 150,
            cacheCreationInputTokens = 30,
            cacheReadInputTokens = 70
        ))
        tracker.recordUsage(Usage(
            promptTokens = 200,
            completionTokens = 80,
            totalTokens = 280,
            cacheCreationInputTokens = 40,
            cacheReadInputTokens = 60
        ))
        val summary = tracker.computeSummary(emptyList())
        assertEquals(70, summary.totalCacheCreationTokens) // 30 + 40
        assertEquals(130, summary.totalCacheReadTokens)    // 70 + 60
    }

    @Test
    fun `Usage totalInputTokens includes cache tokens`() {
        val usage = Usage(
            promptTokens = 100,
            completionTokens = 50,
            totalTokens = 150,
            cacheCreationInputTokens = 30,
            cacheReadInputTokens = 70
        )
        // totalInputTokens = promptTokens + cacheCreationInputTokens + cacheReadInputTokens
        assertEquals(200, usage.totalInputTokens)
    }
}
