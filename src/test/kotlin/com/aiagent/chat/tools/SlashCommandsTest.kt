package com.aiagent.chat.tools

import com.aiagent.chat.model.AuthHeaderType
import com.aiagent.chat.model.ModelInfo
import com.aiagent.chat.model.ProviderConfig
import com.aiagent.chat.model.TodoItem
import org.junit.Assert.*
import org.junit.Test

class SlashCommandsTest {

    private fun makeContext(
        baseUrl: String = "http://localhost:8080",
        model: String = "test-model",
        apiKey: String = "sk-test-12345678",
        maxSteps: Int = 25,
        approvalMode: String = "BALANCED",
        maxContextTokens: Int = 32768,
        maxOutputTokens: Int = 4096,
        multiProviderEnabled: Boolean = false,
        dynamicRoutingEnabled: Boolean = false,
        providers: List<ProviderConfig> = emptyList(),
        folderMemory: String = "",
        globalMemory: String = "",
        summaryMemory: String = "",
        sessionCount: Int = 1,
        activeMessageCount: Int = 0,
        todoCount: Int = 0,
        todoItems: List<TodoItem> = emptyList(),
        hasPlan: Boolean = false,
        planSummary: String = "",
        currentSessionTokens: Int = 0,
        totalInputTokens: Int = 0,
        totalOutputTokens: Int = 0,
        projectRoot: String = System.getProperty("java.io.tmpdir")
    ) = SlashCommandContext(
        projectRoot = projectRoot,
        baseUrl = baseUrl,
        model = model,
        apiKey = apiKey,
        maxSteps = maxSteps,
        approvalMode = approvalMode,
        maxContextTokens = maxContextTokens,
        maxOutputTokens = maxOutputTokens,
        multiProviderEnabled = multiProviderEnabled,
        dynamicRoutingEnabled = dynamicRoutingEnabled,
        providers = providers,
        folderMemory = folderMemory,
        globalMemory = globalMemory,
        summaryMemory = summaryMemory,
        sessionCount = sessionCount,
        activeMessageCount = activeMessageCount,
        todoCount = todoCount,
        todoItems = todoItems,
        hasPlan = hasPlan,
        planSummary = planSummary,
        currentSessionTokens = currentSessionTokens,
        totalInputTokens = totalInputTokens,
        totalOutputTokens = totalOutputTokens
    )

    // --- isLocalCommand tests ---

    @Test
    fun `isLocalCommand returns true for known commands`() {
        assertTrue(SlashCommands.isLocalCommand("/help"))
        assertTrue(SlashCommands.isLocalCommand("/config"))
        assertTrue(SlashCommands.isLocalCommand("/memory"))
        assertTrue(SlashCommands.isLocalCommand("/status"))
        assertTrue(SlashCommands.isLocalCommand("/init"))
        assertTrue(SlashCommands.isLocalCommand("/clear"))
        assertTrue(SlashCommands.isLocalCommand("/new"))
    }

    @Test
    fun `isLocalCommand returns false for unknown commands`() {
        assertFalse(SlashCommands.isLocalCommand("/unknown"))
        assertFalse(SlashCommands.isLocalCommand("/foobar"))
    }

    @Test
    fun `isLocalCommand returns false for non-slash text`() {
        assertFalse(SlashCommands.isLocalCommand("hello world"))
        assertFalse(SlashCommands.isLocalCommand("just a message"))
    }

    @Test
    fun `isLocalCommand is case insensitive`() {
        assertTrue(SlashCommands.isLocalCommand("/HELP"))
        assertTrue(SlashCommands.isLocalCommand("/Help"))
        assertTrue(SlashCommands.isLocalCommand("/CONFIG"))
    }

    @Test
    fun `isLocalCommand handles command with extra args`() {
        assertTrue(SlashCommands.isLocalCommand("/help extra args here"))
        assertTrue(SlashCommands.isLocalCommand("/clear now"))
    }

    @Test
    fun `isLocalCommand handles leading whitespace`() {
        assertTrue(SlashCommands.isLocalCommand("  /help"))
        assertTrue(SlashCommands.isLocalCommand(" /config"))
    }

    // --- processCommand routing tests ---

    @Test
    fun `processCommand returns non-null for each built-in command`() {
        val ctx = makeContext()
        // /logs is excluded because it requires IDE log infrastructure (IdeaLogReader)
        // that is not available in unit tests.
        for (cmd in listOf("/config", "/help", "/memory", "/status", "/init", "/clear", "/new", "/health", "/plan", "/todo")) {
            val result = SlashCommands.processCommand(cmd, ctx)
            assertNotNull("Command $cmd should return a result", result)
            assertTrue("Command $cmd should have non-empty message", result!!.message.isNotEmpty())
        }
    }

    @Test
    fun `processCommand returns null for unknown command`() {
        val ctx = makeContext()
        val result = SlashCommands.processCommand("/unknown", ctx)
        assertNull(result)
    }

    @Test
    fun `processCommand is case insensitive`() {
        val ctx = makeContext()
        val lower = SlashCommands.processCommand("/help", ctx)
        val upper = SlashCommands.processCommand("/HELP", ctx)
        assertNotNull(lower)
        assertNotNull(upper)
        assertEquals(lower!!.message, upper!!.message)
    }

    @Test
    fun `processCommand handles command with extra args`() {
        val ctx = makeContext()
        val result = SlashCommands.processCommand("/help extra args", ctx)
        assertNotNull(result)
        assertTrue(result!!.message.isNotEmpty())
    }

    @Test
    fun `processCommand handles text without slash prefix`() {
        val ctx = makeContext()
        val result = SlashCommands.processCommand("help", ctx)
        assertNotNull(result)
    }

    // --- /config tests ---

    @Test
    fun `config command shows base URL`() {
        val ctx = makeContext(baseUrl = "http://my-api.example.com:9090")
        val result = SlashCommands.processCommand("/config", ctx)
        assertNotNull(result)
        assertTrue(result!!.message.contains("http://my-api.example.com:9090"))
    }

    @Test
    fun `config command shows model name`() {
        val ctx = makeContext(model = "gpt-4-test")
        val result = SlashCommands.processCommand("/config", ctx)
        assertNotNull(result)
        assertTrue(result!!.message.contains("gpt-4-test"))
    }

    @Test
    fun `config command masks API key`() {
        val ctx = makeContext(apiKey = "sk-abcdefgh12345678")
        val result = SlashCommands.processCommand("/config", ctx)
        assertNotNull(result)
        val msg = result!!.message
        assertTrue("Should contain masked key", msg.contains("sk-a****5678"))
        assertFalse("Should not contain full key", msg.contains("sk-abcdefgh12345678"))
    }

    @Test
    fun `config command shows not set for blank API key`() {
        val ctx = makeContext(apiKey = "")
        val result = SlashCommands.processCommand("/config", ctx)
        assertNotNull(result)
        assertTrue(result!!.message.contains("Not set"))
    }

    @Test
    fun `config command shows multi-provider status`() {
        val ctxEnabled = makeContext(multiProviderEnabled = true, providers = listOf(
            ProviderConfig("p1", "Provider1", "http://p1.com", "key1", AuthHeaderType.BEARER, models = emptyList())
        ))
        val result = SlashCommands.processCommand("/config", ctxEnabled)
        assertNotNull(result)
        assertTrue(result!!.message.contains("Multi-Provider"))
        assertTrue(result.message.contains("Enabled"))
        assertTrue(result.message.contains("Provider1"))
    }

    @Test
    fun `config command shows dynamic routing status`() {
        val ctx = makeContext(dynamicRoutingEnabled = true)
        val result = SlashCommands.processCommand("/config", ctx)
        assertNotNull(result)
        assertTrue(result!!.message.contains("Dynamic Routing"))
        assertTrue(result.message.contains("Enabled"))
    }

    @Test
    fun `config command shows max steps and token limits`() {
        val ctx = makeContext(maxSteps = 50, maxContextTokens = 65536, maxOutputTokens = 8192)
        val result = SlashCommands.processCommand("/config", ctx)
        assertNotNull(result)
        assertTrue(result!!.message.contains("50"))
        assertTrue(result.message.contains("65536"))
        assertTrue(result.message.contains("8192"))
    }

    // --- /help tests ---

    @Test
    fun `help command lists all 11 commands`() {
        val ctx = makeContext()
        val result = SlashCommands.processCommand("/help", ctx)
        assertNotNull(result)
        val msg = result!!.message
        for (cmd in listOf("/config", "/help", "/memory", "/status", "/init", "/clear", "/new", "/logs", "/health", "/plan", "/todo")) {
            assertTrue("Help should list $cmd", msg.contains(cmd))
        }
    }

    @Test
    fun `help command includes descriptions`() {
        val ctx = makeContext()
        val result = SlashCommands.processCommand("/help", ctx)
        assertNotNull(result)
        val msg = result!!.message
        assertTrue(msg.contains("Show active extension configuration"))
        assertTrue(msg.contains("List all available slash commands"))
        assertTrue(msg.contains("Clear current conversation"))
    }

    // --- /memory tests ---

    @Test
    fun `memory command shows all three memory levels`() {
        val ctx = makeContext(
            folderMemory = "# AGENTS.md\nProject rules here",
            globalMemory = "# Global rules",
            summaryMemory = "Session summary"
        )
        val result = SlashCommands.processCommand("/memory", ctx)
        assertNotNull(result)
        val msg = result!!.message
        assertTrue(msg.contains("L1 Summary"))
        assertTrue(msg.contains("L2 Workspace Rules"))
        assertTrue(msg.contains("L3 Global Rules"))
        assertTrue(msg.contains("Project rules here"))
        assertTrue(msg.contains("Global rules"))
        assertTrue(msg.contains("Session summary"))
    }

    @Test
    fun `memory command shows placeholder when no memory exists`() {
        val ctx = makeContext(folderMemory = "", globalMemory = "", summaryMemory = "")
        val result = SlashCommands.processCommand("/memory", ctx)
        assertNotNull(result)
        val msg = result!!.message
        assertTrue(msg.contains("No summary memory"))
        assertTrue(msg.contains("No AGENTS.md"))
        assertTrue(msg.contains("No global memory"))
    }

    @Test
    fun `memory command suggests init when no AGENTS_MD`() {
        val ctx = makeContext(folderMemory = "")
        val result = SlashCommands.processCommand("/memory", ctx)
        assertNotNull(result)
        assertTrue(result!!.message.contains("/init"))
    }

    @Test
    fun `memory command truncates long memory content`() {
        val longMemory = "x".repeat(2000)
        val ctx = makeContext(folderMemory = longMemory)
        val result = SlashCommands.processCommand("/memory", ctx)
        assertNotNull(result)
        assertTrue(result!!.message.contains("2000 chars total"))
    }

    // --- /status tests ---

    @Test
    fun `status command shows session count`() {
        val ctx = makeContext(sessionCount = 3)
        val result = SlashCommands.processCommand("/status", ctx)
        assertNotNull(result)
        assertTrue(result!!.message.contains("3"))
    }

    @Test
    fun `status command shows message count`() {
        val ctx = makeContext(activeMessageCount = 15)
        val result = SlashCommands.processCommand("/status", ctx)
        assertNotNull(result)
        assertTrue(result!!.message.contains("15"))
    }

    @Test
    fun `status command shows todo count`() {
        val ctx = makeContext(todoCount = 5)
        val result = SlashCommands.processCommand("/status", ctx)
        assertNotNull(result)
        assertTrue(result!!.message.contains("5"))
    }

    @Test
    fun `status command shows plan status when plan exists`() {
        val ctx = makeContext(hasPlan = true, planSummary = "## Plan: Do something")
        val result = SlashCommands.processCommand("/status", ctx)
        assertNotNull(result)
        assertTrue(result!!.message.contains("Yes"))
    }

    @Test
    fun `status command shows no plan when absent`() {
        val ctx = makeContext(hasPlan = false)
        val result = SlashCommands.processCommand("/status", ctx)
        assertNotNull(result)
        assertTrue(result!!.message.contains("No"))
    }

    @Test
    fun `status command shows token usage`() {
        val ctx = makeContext(currentSessionTokens = 12000, maxContextTokens = 32768, totalInputTokens = 50000, totalOutputTokens = 8000)
        val result = SlashCommands.processCommand("/status", ctx)
        assertNotNull(result)
        val msg = result!!.message
        assertTrue(msg.contains("12000"))
        assertTrue(msg.contains("32768"))
        assertTrue(msg.contains("50000"))
        assertTrue(msg.contains("8000"))
    }

    @Test
    fun `status command shows context window percentage`() {
        val ctx = makeContext(currentSessionTokens = 16384, maxContextTokens = 32768)
        val result = SlashCommands.processCommand("/status", ctx)
        assertNotNull(result)
        assertTrue(result!!.message.contains("50%"))
    }

    @Test
    fun `status command shows active model`() {
        val ctx = makeContext(model = "claude-3-opus")
        val result = SlashCommands.processCommand("/status", ctx)
        assertNotNull(result)
        assertTrue(result!!.message.contains("claude-3-opus"))
    }

    // --- /clear tests ---

    @Test
    fun `clear command returns clear action`() {
        val ctx = makeContext()
        val result = SlashCommands.processCommand("/clear", ctx)
        assertNotNull(result)
        assertEquals(SlashCommandAction.CLEAR_CONVERSATION, result!!.action)
        assertTrue(result.message.isNotEmpty())
    }

    // --- /new tests ---

    @Test
    fun `new command returns new session action`() {
        val ctx = makeContext()
        val result = SlashCommands.processCommand("/new", ctx)
        assertNotNull(result)
        assertEquals(SlashCommandAction.NEW_SESSION, result!!.action)
        assertTrue(result.message.isNotEmpty())
    }

    // --- /init tests ---

    @Test
    fun `init command creates AGENTS_MD in temp directory`() {
        val tempDir = java.io.File(System.getProperty("java.io.tmpdir"), "slash_init_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        try {
            // Create some sample files
            java.io.File(tempDir, "build.gradle").writeText("")
            java.io.File(tempDir, "src").mkdirs()
            java.io.File(tempDir, "src/main.kt").writeText("fun main() {}")
            java.io.File(tempDir, ".git").mkdirs()

            val ctx = makeContext(projectRoot = tempDir.absolutePath)
            val result = SlashCommands.processCommand("/init", ctx)
            assertNotNull(result)
            val msg = result!!.message
            assertTrue("Should report creating AGENTS.md", msg.contains("AGENTS.md"))

            val agentsFile = java.io.File(tempDir, "AGENTS.md")
            assertTrue("AGENTS.md should exist", agentsFile.exists())
            val content = agentsFile.readText()
            assertTrue("Should contain project overview", content.contains("Project Overview"))
            assertTrue("Should detect Gradle", content.contains("Gradle"))
            assertTrue("Should detect Git", content.contains("Git"))
            assertTrue("Should contain coding guidelines", content.contains("Coding Guidelines"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `init command returns error for non-existent directory`() {
        val ctx = makeContext(projectRoot = "/nonexistent/path/that/does/not/exist")
        val result = SlashCommands.processCommand("/init", ctx)
        assertNotNull(result)
        assertTrue(result!!.message.contains("Error"))
    }

    // --- maskApiKey tests ---

    @Test
    fun `maskApiKey returns not set for blank key`() {
        assertEquals("_Not set_", SlashCommands.maskApiKey(""))
    }

    @Test
    fun `maskApiKey masks short key`() {
        assertEquals("****", SlashCommands.maskApiKey("short"))
    }

    @Test
    fun `maskApiKey masks long key with first and last 4 chars`() {
        val masked = SlashCommands.maskApiKey("sk-abcdefgh12345678")
        assertEquals("sk-a****5678", masked)
    }

    @Test
    fun `maskApiKey does not reveal middle of key`() {
        val key = "sk-super-secret-key-12345"
        val masked = SlashCommands.maskApiKey(key)
        assertFalse(masked.contains("secret"))
    }

    // --- BUILT_IN map tests ---

    @Test
    fun `BUILT_IN contains exactly 11 commands`() {
        assertEquals(11, SlashCommands.BUILT_IN.size)
    }

    @Test
    fun `BUILT_IN commands have correct names`() {
        val names = SlashCommands.BUILT_IN.keys
        assertTrue(names.contains("config"))
        assertTrue(names.contains("help"))
        assertTrue(names.contains("memory"))
        assertTrue(names.contains("status"))
        assertTrue(names.contains("init"))
        assertTrue(names.contains("clear"))
        assertTrue(names.contains("new"))
        assertTrue(names.contains("logs"))
        assertTrue(names.contains("health"))
        assertTrue(names.contains("plan"))
        assertTrue(names.contains("todo"))
    }

    @Test
    fun `BUILT_IN commands have non-blank descriptions`() {
        for ((_, cmd) in SlashCommands.BUILT_IN) {
            assertTrue("Command ${cmd.name} should have non-blank description", cmd.description.isNotBlank())
        }
    }
}
