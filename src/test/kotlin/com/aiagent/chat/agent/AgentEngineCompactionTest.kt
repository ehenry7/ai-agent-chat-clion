package com.aiagent.chat.agent

import com.aiagent.chat.model.ChatMessage
import com.aiagent.chat.model.FunctionCall
import com.aiagent.chat.model.MessageRole
import com.aiagent.chat.model.ToolCall
import com.aiagent.chat.net.ApiClient
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for AgentEngine's context compaction mechanisms:
 * - applySemanticSlidingWindow (Tier 0: compress old tool/assistant messages)
 * - Constants and configuration
 * - Integration with ContextCompactor
 */
class AgentEngineCompactionTest {

    // --- applySemanticSlidingWindow: basic behavior ---

    @Test
    fun `slidingWindow preserves system message at index 0`() {
        val engine = createEngine()
        val systemMsg = ChatMessage(MessageRole.SYSTEM, "important system prompt")
        val messages = listOf(systemMsg) + (1..10).map { ChatMessage(MessageRole.USER, "msg $it") }
        val result = engine.applySemanticSlidingWindow(messages)
        assertEquals(systemMsg, result[0])
        assertEquals("important system prompt", result[0].content)
    }

    @Test
    fun `slidingWindow preserves recent messages in protected window`() {
        val engine = createEngine()
        val messages = listOf(ChatMessage(MessageRole.SYSTEM, "sys")) +
            (1..20).map { ChatMessage(MessageRole.USER, "msg $it") }
        val result = engine.applySemanticSlidingWindow(messages)
        // RECENT_WINDOW_MESSAGES = 8, so last 8 should be preserved
        val recentStart = messages.size - AgentEngine.RECENT_WINDOW_MESSAGES
        for (i in recentStart until messages.size) {
            assertEquals(messages[i], result[i])
        }
    }

    @Test
    fun `slidingWindow preserves short tool messages outside protected window`() {
        val engine = createEngine()
        val shortToolContent = "short tool output"
        val messages = listOf(ChatMessage(MessageRole.SYSTEM, "sys")) +
            (1..10).map { ChatMessage(MessageRole.TOOL, shortToolContent) } +
            (1..8).map { ChatMessage(MessageRole.USER, "recent $it") }
        val result = engine.applySemanticSlidingWindow(messages)
        // Short tool messages should be preserved (below TOOL_COMPRESS_THRESHOLD)
        val oldToolMsg = result[1]
        assertEquals(shortToolContent, oldToolMsg.content)
    }

    // --- applySemanticSlidingWindow: tool message compression ---

    @Test
    fun `slidingWindow compresses long tool messages outside protected window`() {
        val engine = createEngine()
        val longContent = "x".repeat(AgentEngine.TOOL_COMPRESS_THRESHOLD + 1)
        val messages = listOf(ChatMessage(MessageRole.SYSTEM, "sys")) +
            (1..10).map { ChatMessage(MessageRole.TOOL, longContent) } +
            (1..8).map { ChatMessage(MessageRole.USER, "recent $it") }
        val result = engine.applySemanticSlidingWindow(messages)
        // Old tool messages (index 1..10) should be compressed
        val oldToolMsg = result[1]
        assertEquals(AgentEngine.COMPRESSED_TOOL_NOTICE, oldToolMsg.content)
        assertTrue(oldToolMsg.content!!.length < longContent.length)
    }

    @Test
    fun `slidingWindow does not compress tool messages at exactly threshold`() {
        val engine = createEngine()
        // Content at exactly threshold should NOT be compressed (condition is > not >=)
        val exactContent = "x".repeat(AgentEngine.TOOL_COMPRESS_THRESHOLD)
        val messages = listOf(ChatMessage(MessageRole.SYSTEM, "sys")) +
            (1..10).map { ChatMessage(MessageRole.TOOL, exactContent) } +
            (1..8).map { ChatMessage(MessageRole.USER, "recent $it") }
        val result = engine.applySemanticSlidingWindow(messages)
        val oldToolMsg = result[1]
        assertEquals(exactContent, oldToolMsg.content)
    }

    @Test
    fun `slidingWindow compresses tool messages above threshold`() {
        val engine = createEngine()
        val aboveContent = "x".repeat(AgentEngine.TOOL_COMPRESS_THRESHOLD + 1)
        val messages = listOf(ChatMessage(MessageRole.SYSTEM, "sys")) +
            (1..10).map { ChatMessage(MessageRole.TOOL, aboveContent) } +
            (1..8).map { ChatMessage(MessageRole.USER, "recent $it") }
        val result = engine.applySemanticSlidingWindow(messages)
        val oldToolMsg = result[1]
        assertEquals(AgentEngine.COMPRESSED_TOOL_NOTICE, oldToolMsg.content)
    }

    @Test
    fun `slidingWindow preserves long tool messages inside protected window`() {
        val engine = createEngine()
        val longContent = "x".repeat(AgentEngine.TOOL_COMPRESS_THRESHOLD + 1000)
        // Put long tool messages in the recent window (last 8)
        val messages = listOf(ChatMessage(MessageRole.SYSTEM, "sys")) +
            (1..10).map { ChatMessage(MessageRole.USER, "old $it") } +
            (1..8).map { ChatMessage(MessageRole.TOOL, longContent) }
        val result = engine.applySemanticSlidingWindow(messages)
        // Recent tool messages should NOT be compressed
        val recentToolMsg = result[result.size - 1]
        assertEquals(longContent, recentToolMsg.content)
    }

    // --- applySemanticSlidingWindow: assistant message compression ---

    @Test
    fun `slidingWindow compresses long assistant messages outside protected window`() {
        val engine = createEngine()
        val longContent = "x".repeat(AgentEngine.ASSISTANT_COMPRESS_THRESHOLD + 1)
        val messages = listOf(ChatMessage(MessageRole.SYSTEM, "sys")) +
            (1..10).map { ChatMessage(MessageRole.ASSISTANT, longContent) } +
            (1..8).map { ChatMessage(MessageRole.USER, "recent $it") }
        val result = engine.applySemanticSlidingWindow(messages)
        // Old assistant messages should be compressed with preview
        val oldAssistantMsg = result[1]
        assertTrue(oldAssistantMsg.content!!.contains(AgentEngine.COMPRESSED_ASSISTANT_NOTICE))
        assertTrue(oldAssistantMsg.content!!.length < longContent.length)
    }

    @Test
    fun `slidingWindow preserves preview in compressed assistant messages`() {
        val engine = createEngine()
        val longContent = "PREFIX_CONTENT_HERE_" + "x".repeat(AgentEngine.ASSISTANT_COMPRESS_THRESHOLD + 100)
        val messages = listOf(ChatMessage(MessageRole.SYSTEM, "sys")) +
            (1..10).map { ChatMessage(MessageRole.ASSISTANT, longContent) } +
            (1..8).map { ChatMessage(MessageRole.USER, "recent $it") }
        val result = engine.applySemanticSlidingWindow(messages)
        val oldAssistantMsg = result[1]
        // Should contain the first 200 chars as preview
        assertTrue(oldAssistantMsg.content!!.startsWith("PREFIX_CONTENT_HERE_"))
    }

    @Test
    fun `slidingWindow does not compress assistant messages at exactly threshold`() {
        val engine = createEngine()
        val exactContent = "x".repeat(AgentEngine.ASSISTANT_COMPRESS_THRESHOLD)
        val messages = listOf(ChatMessage(MessageRole.SYSTEM, "sys")) +
            (1..10).map { ChatMessage(MessageRole.ASSISTANT, exactContent) } +
            (1..8).map { ChatMessage(MessageRole.USER, "recent $it") }
        val result = engine.applySemanticSlidingWindow(messages)
        val oldAssistantMsg = result[1]
        assertEquals(exactContent, oldAssistantMsg.content)
    }

    @Test
    fun `slidingWindow preserves long assistant messages inside protected window`() {
        val engine = createEngine()
        val longContent = "x".repeat(AgentEngine.ASSISTANT_COMPRESS_THRESHOLD + 1000)
        val messages = listOf(ChatMessage(MessageRole.SYSTEM, "sys")) +
            (1..10).map { ChatMessage(MessageRole.USER, "old $it") } +
            (1..8).map { ChatMessage(MessageRole.ASSISTANT, longContent) }
        val result = engine.applySemanticSlidingWindow(messages)
        // Recent assistant messages should NOT be compressed
        val recentAssistantMsg = result[result.size - 1]
        assertEquals(longContent, recentAssistantMsg.content)
    }

    // --- applySemanticSlidingWindow: edge cases ---

    @Test
    fun `slidingWindow handles empty message list`() {
        val engine = createEngine()
        val result = engine.applySemanticSlidingWindow(emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `slidingWindow handles single message`() {
        val engine = createEngine()
        val messages = listOf(ChatMessage(MessageRole.SYSTEM, "only message"))
        val result = engine.applySemanticSlidingWindow(messages)
        assertEquals(1, result.size)
        assertEquals("only message", result[0].content)
    }

    @Test
    fun `slidingWindow handles messages smaller than recent window`() {
        val engine = createEngine()
        // Fewer messages than RECENT_WINDOW_MESSAGES (8)
        val messages = listOf(ChatMessage(MessageRole.SYSTEM, "sys")) +
            (1..3).map { ChatMessage(MessageRole.TOOL, "x".repeat(5000)) }
        val result = engine.applySemanticSlidingWindow(messages)
        // All messages should be preserved (protectedStart coerced to 1)
        assertEquals(messages.size, result.size)
        // System message preserved
        assertEquals("sys", result[0].content)
        // Tool messages: index 1..3 are all >= protectedStart(1), so preserved
        assertEquals(messages[1], result[1])
    }

    @Test
    fun `slidingWindow preserves user messages outside protected window`() {
        val engine = createEngine()
        val messages = listOf(ChatMessage(MessageRole.SYSTEM, "sys")) +
            (1..10).map { ChatMessage(MessageRole.USER, "user msg $it") } +
            (1..8).map { ChatMessage(MessageRole.USER, "recent $it") }
        val result = engine.applySemanticSlidingWindow(messages)
        // User messages should never be compressed (only TOOL and ASSISTANT are)
        for (i in 1..10) {
            assertEquals(messages[i], result[i])
        }
    }

    @Test
    fun `slidingWindow handles null content tool messages`() {
        val engine = createEngine()
        val messages = listOf(ChatMessage(MessageRole.SYSTEM, "sys")) +
            (1..10).map { ChatMessage(MessageRole.TOOL, content = null) } +
            (1..8).map { ChatMessage(MessageRole.USER, "recent $it") }
        val result = engine.applySemanticSlidingWindow(messages)
        // Null content should not cause issues, message preserved as-is
        assertNull(result[1].content)
    }

    @Test
    fun `slidingWindow handles null content assistant messages`() {
        val engine = createEngine()
        val messages = listOf(ChatMessage(MessageRole.SYSTEM, "sys")) +
            (1..10).map { ChatMessage(MessageRole.ASSISTANT, content = null) } +
            (1..8).map { ChatMessage(MessageRole.USER, "recent $it") }
        val result = engine.applySemanticSlidingWindow(messages)
        assertNull(result[1].content)
    }

    @Test
    fun `slidingWindow returns same size as input`() {
        val engine = createEngine()
        val messages = listOf(ChatMessage(MessageRole.SYSTEM, "sys")) +
            (1..20).map { ChatMessage(MessageRole.TOOL, "x".repeat(3000)) }
        val result = engine.applySemanticSlidingWindow(messages)
        assertEquals(messages.size, result.size)
    }

    @Test
    fun `slidingWindow with mixed message types compresses only tool and assistant`() {
        val engine = createEngine()
        val longTool = "x".repeat(AgentEngine.TOOL_COMPRESS_THRESHOLD + 1)
        val longAssistant = "x".repeat(AgentEngine.ASSISTANT_COMPRESS_THRESHOLD + 1)
        val messages = listOf(ChatMessage(MessageRole.SYSTEM, "sys")) +
            listOf(
                ChatMessage(MessageRole.USER, "x".repeat(5000)),         // should NOT be compressed
                ChatMessage(MessageRole.TOOL, longTool),                  // should be compressed
                ChatMessage(MessageRole.ASSISTANT, longAssistant),        // should be compressed
                ChatMessage(MessageRole.USER, "x".repeat(5000)),         // should NOT be compressed
                ChatMessage(MessageRole.TOOL, longTool),                  // should be compressed
            ) +
            (1..8).map { ChatMessage(MessageRole.USER, "recent $it") }
        val result = engine.applySemanticSlidingWindow(messages)

        // User messages at index 1 and 4 should be preserved
        assertEquals(messages[1], result[1])
        assertEquals(messages[4], result[4])

        // Tool messages at index 2 and 5 should be compressed
        assertEquals(AgentEngine.COMPRESSED_TOOL_NOTICE, result[2].content)
        assertEquals(AgentEngine.COMPRESSED_TOOL_NOTICE, result[5].content)

        // Assistant message at index 3 should be compressed
        assertTrue(result[3].content!!.contains(AgentEngine.COMPRESSED_ASSISTANT_NOTICE))
    }

    // --- Constants ---

    @Test
    fun `RECENT_WINDOW_MESSAGES is 8`() {
        assertEquals(8, AgentEngine.RECENT_WINDOW_MESSAGES)
    }

    @Test
    fun `TOOL_COMPRESS_THRESHOLD is 2000`() {
        assertEquals(2000, AgentEngine.TOOL_COMPRESS_THRESHOLD)
    }

    @Test
    fun `ASSISTANT_COMPRESS_THRESHOLD is 3000`() {
        assertEquals(3000, AgentEngine.ASSISTANT_COMPRESS_THRESHOLD)
    }

    @Test
    fun `MAX_COMPACTION_RETRIES is 2`() {
        assertEquals(2, AgentEngine.MAX_COMPACTION_RETRIES)
    }

    @Test
    fun `PROACTIVE_COMPACTION_RATIO is 0_80`() {
        assertEquals(0.80, AgentEngine.PROACTIVE_COMPACTION_RATIO, 0.001)
    }

    @Test
    fun `COMPRESSED_TOOL_NOTICE is descriptive`() {
        assertTrue(AgentEngine.COMPRESSED_TOOL_NOTICE.contains("compressed"))
    }

    @Test
    fun `COMPRESSED_ASSISTANT_NOTICE is descriptive`() {
        assertTrue(AgentEngine.COMPRESSED_ASSISTANT_NOTICE.contains("compressed"))
    }

    // --- Integration: ContextCompactor + AgentEngine ---

    @Test
    fun `engine with contextCompactor uses it for compaction`() {
        val compactor = ContextCompactor(createMockClient(), maxContextTokens = 32768)
        val engine = createEngine(contextCompactor = compactor)
        // Engine should have the compactor wired
        assertNotNull(engine)
    }

    @Test
    fun `engine without contextCompactor still applies sliding window`() {
        val engine = createEngine(contextCompactor = null)
        val longContent = "x".repeat(AgentEngine.TOOL_COMPRESS_THRESHOLD + 1)
        val messages = listOf(ChatMessage(MessageRole.SYSTEM, "sys")) +
            (1..10).map { ChatMessage(MessageRole.TOOL, longContent) } +
            (1..8).map { ChatMessage(MessageRole.USER, "recent $it") }
        val result = engine.applySemanticSlidingWindow(messages)
        // Sliding window should still work without a compactor
        assertEquals(AgentEngine.COMPRESSED_TOOL_NOTICE, result[1].content)
    }

    // --- System prompt: run_python preference ---

    @Test
    fun `system prompt mentions run_python preference`() {
        val engine = createEngine()
        val prompt = engine.buildSystemPrompt(listOf("read_file", "run_python", "run_command"), "", "", "discovery")
        assertTrue(prompt.contains("run_python"))
    }

    @Test
    fun `system prompt says to prefer run_python over run_command`() {
        val engine = createEngine()
        val prompt = engine.buildSystemPrompt(listOf("read_file", "run_python", "run_command"), "", "", "discovery")
        assertTrue(prompt.contains("Prefer run_python over run_command"))
    }

    @Test
    fun `system prompt lists computation tasks for run_python`() {
        val engine = createEngine()
        val prompt = engine.buildSystemPrompt(listOf("run_python"), "", "", "discovery")
        assertTrue(prompt.contains("computation"))
        assertTrue(prompt.contains("data processing"))
    }

    @Test
    fun `system prompt says run_command only for shell-specific tasks`() {
        val engine = createEngine()
        val prompt = engine.buildSystemPrompt(listOf("run_python", "run_command"), "", "", "discovery")
        assertTrue(prompt.contains("git"))
        assertTrue(prompt.contains("build tools"))
    }

    @Test
    fun `system prompt includes available tool names`() {
        val engine = createEngine()
        val tools = listOf("read_file", "write_file", "run_python", "run_command")
        val prompt = engine.buildSystemPrompt(tools, "", "", "discovery")
        assertTrue(prompt.contains("read_file"))
        assertTrue(prompt.contains("write_file"))
        assertTrue(prompt.contains("run_python"))
        assertTrue(prompt.contains("run_command"))
    }

    @Test
    fun `system prompt includes phase information`() {
        val engine = createEngine()
        val prompt = engine.buildSystemPrompt(listOf("read_file"), "", "", "execution")
        assertTrue(prompt.contains("execution"))
    }

    @Test
    fun `system prompt includes memory when provided`() {
        val engine = createEngine()
        val prompt = engine.buildSystemPrompt(listOf("read_file"), "remember to use tabs", "", "discovery")
        assertTrue(prompt.contains("remember to use tabs"))
        assertTrue(prompt.contains("agent_memory"))
    }

    @Test
    fun `system prompt includes global memory when provided`() {
        val engine = createEngine()
        val prompt = engine.buildSystemPrompt(listOf("read_file"), "", "global context here", "discovery")
        assertTrue(prompt.contains("global context here"))
        assertTrue(prompt.contains("agent_global_memory"))
    }

    @Test
    fun `system prompt excludes memory sections when blank`() {
        val engine = createEngine()
        val prompt = engine.buildSystemPrompt(listOf("read_file"), "", "", "discovery")
        assertFalse(prompt.contains("agent_memory"))
        assertFalse(prompt.contains("agent_global_memory"))
    }

    // --- Helper ---

    private fun createEngine(contextCompactor: ContextCompactor? = null): AgentEngine {
        return AgentEngine(
            client = createMockClient(),
            toolExecutor = { _, _ -> "mock result" },
            onDelta = { },
            contextCompactor = contextCompactor
        )
    }

    private fun createMockClient(): ApiClient {
        return ApiClient(
            baseUrl = "http://localhost:99999",
            apiKey = "test-key",
            model = "test-model"
        )
    }
}
