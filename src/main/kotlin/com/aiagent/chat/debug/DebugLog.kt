package com.aiagent.chat.debug

import com.intellij.openapi.diagnostic.Logger
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object DebugLog {
    private val logger = Logger.getInstance(DebugLog::class.java)
    private val entries = mutableListOf<LogEntry>()
    private val listeners = mutableListOf<(LogEntry) -> Unit>()
    private val maxEntries = 500
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    data class LogEntry(
        val timestamp: LocalDateTime,
        val level: Level,
        val source: String,
        val message: String
    ) {
        fun format(): String = "[${timestamp.format(timeFormatter)}] [${level.name}] [$source] $message"
    }

    enum class Level {
        DEBUG, INFO, WARN, ERROR
    }

    fun addListener(listener: (LogEntry) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (LogEntry) -> Unit) {
        listeners.remove(listener)
    }

    private fun emit(entry: LogEntry) {
        synchronized(entries) {
            entries.add(entry)
            if (entries.size > maxEntries) {
                entries.removeAt(0)
            }
        }
        listeners.forEach { listener ->
            try {
                listener(entry)
            } catch (_: Exception) { }
        }
    }

    fun debug(source: String, message: String) = log(Level.DEBUG, source, message)
    fun info(source: String, message: String) = log(Level.INFO, source, message)
    fun warn(source: String, message: String) = log(Level.WARN, source, message)
    fun error(source: String, message: String) = log(Level.ERROR, source, message)

    fun error(source: String, message: String, t: Throwable) {
        log(Level.ERROR, source, message)
        logger.error("[$source] $message", t)
    }

    private fun log(level: Level, source: String, message: String) {
        val entry = LogEntry(LocalDateTime.now(), level, source, message)
        emit(entry)

        when (level) {
            Level.DEBUG -> logger.debug("[$source] $message")
            Level.INFO -> logger.info("[$source] $message")
            Level.WARN -> logger.warn("[$source] $message")
            Level.ERROR -> logger.error("[$source] $message")
        }
    }

    fun getEntries(): List<LogEntry> = synchronized(entries) { entries.toList() }

    fun clear() {
        synchronized(entries) { entries.clear() }
    }
}
