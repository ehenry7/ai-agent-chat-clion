package com.aiagent.chat.tools

import org.junit.Assert.*
import org.junit.Test

class SlashCommandsTest {

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

    @Test
    fun `processCommand returns content for known command`() {
        val result = SlashCommands.processCommand("/help", "/project")
        assertNotNull(result)
        assertTrue(result!!.isNotEmpty())
    }

    @Test
    fun `processCommand returns content for each built-in command`() {
        for (cmd in listOf("/config", "/help", "/memory", "/status", "/init", "/clear", "/new")) {
            val result = SlashCommands.processCommand(cmd, "/project")
            assertNotNull("Command $cmd should return content", result)
        }
    }

    @Test
    fun `processCommand returns null for unknown command`() {
        val result = SlashCommands.processCommand("/unknown", "/project")
        assertNull(result)
    }

    @Test
    fun `processCommand is case insensitive`() {
        val lower = SlashCommands.processCommand("/help", "/project")
        val upper = SlashCommands.processCommand("/HELP", "/project")
        assertEquals(lower, upper)
        assertNotNull(lower)
    }

    @Test
    fun `processCommand handles command with extra args`() {
        val result = SlashCommands.processCommand("/help extra args", "/project")
        assertNotNull(result)
    }

    @Test
    fun `processCommand handles text without slash prefix`() {
        // The removePrefix only removes "/" if present, but "help" without "/" should still work
        // because removePrefix("/") on "help" returns "help" unchanged
        val result = SlashCommands.processCommand("help", "/project")
        assertNotNull(result)
    }
}
