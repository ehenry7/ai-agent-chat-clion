package com.aiagent.chat.persistence

import com.aiagent.chat.model.ChatMessage
import com.aiagent.chat.model.ChatMeta
import com.aiagent.chat.model.MessageRole
import com.aiagent.chat.model.SessionState
import com.aiagent.chat.model.TodoItem
import com.aiagent.chat.model.UiLogEntry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PersistenceManagerTest {

    private fun createTempManager(): Pair<PersistenceManager, File> {
        val tempDir = Files.createTempDirectory("persistence_test").toFile()
        val pm = PersistenceManager(tempDir.absolutePath)
        return pm to tempDir
    }

    private fun cleanup(dir: File) {
        dir.deleteRecursively()
    }

    // --- Single-session API ---

    @Test
    fun `saveSession and loadSession round trip`() {
        val (pm, dir) = createTempManager()
        try {
            val state = SessionState(
                history = listOf(ChatMessage(role = MessageRole.USER, content = "hello")),
                uiLog = listOf(UiLogEntry("user", "hello")),
                selectedModel = "test-model",
                chatId = "chat1",
                chatName = "Test Chat"
            )
            pm.saveSession(state)
            val loaded = pm.loadSession()
            assertNotNull(loaded)
            assertEquals("test-model", loaded!!.selectedModel)
            assertEquals("chat1", loaded.chatId)
            assertEquals(1, loaded.history.size)
            assertEquals("hello", loaded.history[0].content)
        } finally { cleanup(dir) }
    }

    @Test
    fun `loadSession returns null when no session file exists`() {
        val (pm, dir) = createTempManager()
        try {
            assertNull(pm.loadSession())
        } finally { cleanup(dir) }
    }

    @Test
    fun `clearSession deletes session file`() {
        val (pm, dir) = createTempManager()
        try {
            pm.saveSession(SessionState(chatId = "c1"))
            assertNotNull(pm.loadSession())
            pm.clearSession()
            assertNull(pm.loadSession())
        } finally { cleanup(dir) }
    }

    // --- Multi-session API ---

    @Test
    fun `saveSessionById and loadSessionById round trip`() {
        val (pm, dir) = createTempManager()
        try {
            val state = SessionState(
                history = listOf(ChatMessage(role = MessageRole.ASSISTANT, content = "response")),
                chatId = "session-42",
                chatName = "My Session"
            )
            pm.saveSessionById(state)
            val loaded = pm.loadSessionById("session-42")
            assertNotNull(loaded)
            assertEquals("session-42", loaded!!.chatId)
            assertEquals("My Session", loaded.chatName)
            assertEquals(1, loaded.history.size)
        } finally { cleanup(dir) }
    }

    @Test
    fun `loadSessionById returns null for non-existent session`() {
        val (pm, dir) = createTempManager()
        try {
            assertNull(pm.loadSessionById("nonexistent"))
        } finally { cleanup(dir) }
    }

    @Test
    fun `loadSessionsIndex returns empty list when no index exists`() {
        val (pm, dir) = createTempManager()
        try {
            assertTrue(pm.loadSessionsIndex().isEmpty())
        } finally { cleanup(dir) }
    }

    @Test
    fun `saveSessionById updates sessions index`() {
        val (pm, dir) = createTempManager()
        try {
            pm.saveSessionById(SessionState(chatId = "s1", chatName = "Session 1"))
            pm.saveSessionById(SessionState(chatId = "s2", chatName = "Session 2"))
            val index = pm.loadSessionsIndex()
            assertEquals(2, index.size)
            assertTrue(index.any { it.id == "s1" && it.name == "Session 1" })
            assertTrue(index.any { it.id == "s2" && it.name == "Session 2" })
        } finally { cleanup(dir) }
    }

    @Test
    fun `saveSessionById replaces existing index entry`() {
        val (pm, dir) = createTempManager()
        try {
            pm.saveSessionById(SessionState(chatId = "s1", chatName = "Old Name"))
            pm.saveSessionById(SessionState(chatId = "s1", chatName = "New Name"))
            val index = pm.loadSessionsIndex()
            assertEquals(1, index.size)
            assertEquals("New Name", index[0].name)
        } finally { cleanup(dir) }
    }

    @Test
    fun `deleteSessionById removes session and updates index`() {
        val (pm, dir) = createTempManager()
        try {
            pm.saveSessionById(SessionState(chatId = "s1", chatName = "Session 1"))
            pm.saveSessionById(SessionState(chatId = "s2", chatName = "Session 2"))
            pm.deleteSessionById("s1")
            assertNull(pm.loadSessionById("s1"))
            assertNotNull(pm.loadSessionById("s2"))
            val index = pm.loadSessionsIndex()
            assertEquals(1, index.size)
            assertEquals("s2", index[0].id)
        } finally { cleanup(dir) }
    }

    @Test
    fun `deleteSessionById handles non-existent session gracefully`() {
        val (pm, dir) = createTempManager()
        try {
            pm.deleteSessionById("nonexistent")
            // Should not throw
        } finally { cleanup(dir) }
    }

    // --- Memory APIs ---

    @Test
    fun `saveFolderMemory and loadFolderMemory round trip`() {
        val (pm, dir) = createTempManager()
        try {
            pm.saveFolderMemory("# Project Rules\nBe awesome.")
            assertEquals("# Project Rules\nBe awesome.", pm.loadFolderMemory())
        } finally { cleanup(dir) }
    }

    @Test
    fun `loadFolderMemory returns empty string when no file exists`() {
        val (pm, dir) = createTempManager()
        try {
            assertEquals("", pm.loadFolderMemory())
        } finally { cleanup(dir) }
    }

    @Test
    fun `saveSummaryMemory and loadSummaryMemory round trip`() {
        val (pm, dir) = createTempManager()
        try {
            pm.saveSummaryMemory("L1 Summary content")
            assertEquals("L1 Summary content", pm.loadSummaryMemory())
        } finally { cleanup(dir) }
    }

    @Test
    fun `loadSummaryMemory returns empty string when no file exists`() {
        val (pm, dir) = createTempManager()
        try {
            assertEquals("", pm.loadSummaryMemory())
        } finally { cleanup(dir) }
    }

    @Test
    fun `clearSession also deletes summary memory`() {
        val (pm, dir) = createTempManager()
        try {
            pm.saveSummaryMemory("summary content")
            pm.saveSession(SessionState(chatId = "c1"))
            pm.clearSession()
            assertEquals("", pm.loadSummaryMemory())
        } finally { cleanup(dir) }
    }

    // --- SessionState serialization ---

    @Test
    fun `SessionState with todoList serializes correctly`() {
        val (pm, dir) = createTempManager()
        try {
            val state = SessionState(
                history = listOf(ChatMessage(role = MessageRole.USER, content = "test")),
                todoList = listOf(
                    TodoItem("t1", "Task 1", "pending"),
                    TodoItem("t2", "Task 2", "completed")
                ),
                chatId = "todo-test"
            )
            pm.saveSessionById(state)
            val loaded = pm.loadSessionById("todo-test")
            assertNotNull(loaded)
            assertEquals(2, loaded!!.todoList.size)
            assertEquals("Task 1", loaded.todoList[0].content)
            assertEquals("pending", loaded.todoList[0].status)
        } finally { cleanup(dir) }
    }

    @Test
    fun `SessionState with uiLog serializes correctly`() {
        val (pm, dir) = createTempManager()
        try {
            val state = SessionState(
                uiLog = listOf(
                    UiLogEntry("user", "hello", null),
                    UiLogEntry("assistant", "hi there", "Response")
                ),
                chatId = "uilog-test"
            )
            pm.saveSessionById(state)
            val loaded = pm.loadSessionById("uilog-test")
            assertNotNull(loaded)
            assertEquals(2, loaded!!.uiLog.size)
            assertEquals("hello", loaded.uiLog[0].text)
            assertEquals("Response", loaded.uiLog[1].title)
        } finally { cleanup(dir) }
    }

    @Test
    fun `ChatMeta in sessions index preserves model field`() {
        val (pm, dir) = createTempManager()
        try {
            pm.saveSessionById(SessionState(chatId = "m1", chatName = "Model Test", selectedModel = "gpt-4"))
            val index = pm.loadSessionsIndex()
            assertEquals(1, index.size)
            assertEquals("gpt-4", index[0].model)
        } finally { cleanup(dir) }
    }
}
