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
    private val memoryFile = File(projectRoot, "AGENTS.md")
    private val globalMemoryFile = File(System.getProperty("user.home"), ".ai-agent-chat/GLOBAL_AGENTS.md")

    init {
        sessionDir.mkdirs()
        globalMemoryFile.parentFile?.mkdirs()
    }

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
