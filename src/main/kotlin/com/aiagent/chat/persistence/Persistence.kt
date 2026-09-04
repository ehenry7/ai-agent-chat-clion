package com.aiagent.chat.persistence

import com.aiagent.chat.model.ChatMeta
import com.aiagent.chat.model.SessionState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.charset.StandardCharsets

class PersistenceManager(private val projectRoot: String) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val sessionDir = File(projectRoot, ".ai-agent-chat")
    private val sessionFile = File(sessionDir, "session.json")
    private val sessionsIndexFile = File(sessionDir, "sessions_index.json")
    private val l1SummaryFile = File(sessionDir, "L1_SUMMARY.md") // Rolling Context Summary
    private val memoryFile = File(projectRoot, "AGENTS.md") // L2 Workspace Rules
    private val globalMemoryFile = File(System.getProperty("user.home"), ".ai-agent-chat/GLOBAL_AGENTS.md") // L3 Global Rules

    init {
        sessionDir.mkdirs()
        globalMemoryFile.parentFile?.mkdirs()
    }

    // --- Single-session API (backward compatible) ---

    fun loadSession(): SessionState? {
        return try {
            if (sessionFile.exists()) {
                json.decodeFromString<SessionState>(sessionFile.readText(StandardCharsets.UTF_8))
            } else null
        } catch (_: Exception) {
            null
        }
    }

    fun saveSession(state: SessionState) {
        try {
            sessionFile.writeText(json.encodeToString(state), StandardCharsets.UTF_8)
        } catch (_: Exception) {}
    }

    fun clearSession() {
        if (sessionFile.exists()) sessionFile.delete()
        if (l1SummaryFile.exists()) l1SummaryFile.delete()
    }

    // --- Multi-session API (session restore on restart) ---

    /**
     * Save a session to its own file in the sessions directory.
     * Also updates the sessions index.
     */
    fun saveSessionById(state: SessionState) {
        try {
            val sessionsSubDir = File(sessionDir, "sessions")
            sessionsSubDir.mkdirs()
            val file = File(sessionsSubDir, "${state.chatId ?: "default"}.json")
            file.writeText(json.encodeToString(state), StandardCharsets.UTF_8)
            updateSessionsIndex(state)
        } catch (_: Exception) {}
    }

    /**
     * Load a specific session by its chat ID.
     */
    fun loadSessionById(chatId: String): SessionState? {
        return try {
            val file = File(sessionDir, "sessions/$chatId.json")
            if (file.exists()) {
                json.decodeFromString<SessionState>(file.readText(StandardCharsets.UTF_8))
            } else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Load the sessions index — a list of chat metadata for all saved sessions.
     * Used to restore the tab list on plugin restart.
     */
    fun loadSessionsIndex(): List<ChatMeta> {
        return try {
            if (sessionsIndexFile.exists()) {
                json.decodeFromString<List<ChatMeta>>(sessionsIndexFile.readText(StandardCharsets.UTF_8))
            } else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Delete a session by its chat ID.
     */
    fun deleteSessionById(chatId: String) {
        try {
            val file = File(sessionDir, "sessions/$chatId.json")
            if (file.exists()) file.delete()
            // Remove from index
            val index = loadSessionsIndex().filterNot { it.id == chatId }
            sessionsIndexFile.writeText(json.encodeToString(index), StandardCharsets.UTF_8)
        } catch (_: Exception) {}
    }

    /**
     * Update the sessions index with the metadata from a saved session.
     */
    private fun updateSessionsIndex(state: SessionState) {
        try {
            val current = loadSessionsIndex().toMutableList()
            val chatId = state.chatId ?: return
            val now = System.currentTimeMillis()
            val meta = ChatMeta(
                id = chatId,
                name = state.chatName ?: "Session",
                createdAt = state.createdAt ?: now,
                updatedAt = now,
                model = state.selectedModel
            )
            // Replace existing entry or add new
            current.removeAll { it.id == chatId }
            current.add(meta)
            sessionsIndexFile.writeText(json.encodeToString(current), StandardCharsets.UTF_8)
        } catch (_: Exception) {}
    }

    fun loadSummaryMemory(): String {
        return if (l1SummaryFile.exists()) l1SummaryFile.readText(StandardCharsets.UTF_8) else ""
    }

    fun saveSummaryMemory(content: String) {
        l1SummaryFile.writeText(content, StandardCharsets.UTF_8)
    }

    fun loadFolderMemory(): String {
        return if (memoryFile.exists()) memoryFile.readText(StandardCharsets.UTF_8) else ""
    }

    fun saveFolderMemory(content: String) {
        memoryFile.writeText(content, StandardCharsets.UTF_8)
    }

    fun loadGlobalMemory(): String {
        return if (globalMemoryFile.exists()) globalMemoryFile.readText(StandardCharsets.UTF_8) else ""
    }

    fun saveGlobalMemory(content: String) {
        globalMemoryFile.writeText(content, StandardCharsets.UTF_8)
    }
}
