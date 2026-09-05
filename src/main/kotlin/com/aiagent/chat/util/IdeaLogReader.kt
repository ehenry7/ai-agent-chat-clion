package com.aiagent.chat.util

import com.aiagent.chat.debug.DebugLog
import java.io.File
import java.nio.file.Paths

/**
 * Reads IntelliJ IDEA log files for diagnostics.
 *
 * Inspired by refact-main's IdeaLogReader.kt.
 * Locates the IDE log file in the sandbox/system/logs directory and provides
 * methods to read recent log entries, filter by level, and search for patterns.
 *
 * The IDE log path is resolved in this order:
 * 1. System property "idea.log.path" (set by the IDE at runtime)
 * 2. Standard IntelliJ sandbox path: <user.home>/.idea-system/<idea.version>/system/logs/idea.log
 * 3. Fallback: <user.home>/.intellij-aiagent/system/logs/idea.log
 */
object IdeaLogReader {

    /** Maximum number of lines to return by default. */
    const val DEFAULT_MAX_LINES = 200

    /** Maximum line length to avoid huge stack traces dominating output. */
    const val MAX_LINE_LENGTH = 500

    /**
     * Find the IDE log file.
     */
    fun findLogFile(): File? {
        // 1. System property
        val sysProp = System.getProperty("idea.log.path")
        if (sysProp != null) {
            val f = File(sysProp)
            if (f.exists()) return f
        }

        // 2. Standard IntelliJ paths
        val userHome = System.getProperty("user.home") ?: return null
        val ideaSystemPath = System.getProperty("idea.system.path")
        val candidates = mutableListOf<File>()

        if (ideaSystemPath != null) {
            candidates.add(File(ideaSystemPath, "logs/idea.log"))
        }

        // Common IntelliJ sandbox locations
        candidates.add(File(userHome, ".idea-system/system/logs/idea.log"))
        candidates.add(File(userHome, ".intellij-aiagent/system/logs/idea.log"))

        // IntelliJ 2024+ uses a versioned path
        val ideaVersion = System.getProperty("idea.version", "")
        if (ideaVersion.isNotBlank()) {
            candidates.add(File(userHome, ".idea-system/$ideaVersion/system/logs/idea.log"))
        }

        for (candidate in candidates) {
            if (candidate.exists()) {
                DebugLog.info("IdeaLogReader", "Found IDE log at: ${candidate.absolutePath}")
                return candidate
            }
        }

        DebugLog.warn("IdeaLogReader", "No IDE log file found in any candidate location")
        return null
    }

    /**
     * Read the last N lines from the IDE log.
     *
     * @param maxLines Maximum number of lines to return (default 200)
     * @return List of log lines, or empty list if log file not found
     */
    fun readRecent(maxLines: Int = DEFAULT_MAX_LINES): List<String> {
        val logFile = findLogFile() ?: return emptyList()
        return try {
            val lines = logFile.readLines()
            val startIdx = (lines.size - maxLines).coerceAtLeast(0)
            lines.subList(startIdx, lines.size).map { line ->
                if (line.length > MAX_LINE_LENGTH) line.take(MAX_LINE_LENGTH) + "..." else line
            }
        } catch (e: Exception) {
            DebugLog.error("IdeaLogReader", "Failed to read log file: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Read recent log entries filtered by severity level.
     *
     * @param level Minimum severity level: "INFO", "WARN", "ERROR" (case-insensitive)
     * @param maxLines Maximum number of lines to return
     * @return Filtered log lines
     */
    fun readByLevel(level: String, maxLines: Int = DEFAULT_MAX_LINES): List<String> {
        val allLines = readRecent(maxLines * 5) // Read more to have enough after filtering
        val levelUpper = level.uppercase()
        val levelPriority = mapOf("INFO" to 0, "WARN" to 1, "ERROR" to 2)
        val minPriority = levelPriority[levelUpper] ?: 0

        return allLines.filter { line ->
            val lineLevel = extractLogLevel(line)
            val priority = levelPriority[lineLevel] ?: -1
            priority >= minPriority
        }.takeLast(maxLines)
    }

    /**
     * Search the IDE log for lines matching a pattern.
     *
     * @param pattern Regex pattern to search for
     * @param maxLines Maximum number of matching lines to return
     * @return Matching log lines
     */
    fun search(pattern: String, maxLines: Int = DEFAULT_MAX_LINES): List<String> {
        val allLines = readRecent(maxLines * 10)
        val regex = try {
            Regex(pattern, RegexOption.IGNORE_CASE)
        } catch (e: Exception) {
            // If pattern is not a valid regex, do literal search
            return allLines.filter { it.contains(pattern, ignoreCase = true) }.takeLast(maxLines)
        }
        return allLines.filter { it.contains(regex) }.takeLast(maxLines)
    }

    /**
     * Extract the log level from a standard IntelliJ log line.
     * IntelliJ log format: "2024-01-15 10:30:45,123 [12345] INFO  com.example - message"
     */
    private fun extractLogLevel(line: String): String {
        // Look for known log level keywords
        val levels = listOf("ERROR", "WARN", "INFO", "DEBUG", "TRACE")
        for (level in levels) {
            if (line.contains(" $level ") || line.contains(" $level\t")) {
                return level
            }
        }
        return ""
    }

    /**
     * Get a summary of the log: total lines, error count, warning count.
     */
    fun getSummary(): String {
        val logFile = findLogFile()
        if (logFile == null) return "IDE log file not found."
        val lines = readRecent(1000)
        var errors = 0
        var warnings = 0
        for (line in lines) {
            val level = extractLogLevel(line)
            when (level) {
                "ERROR" -> errors++
                "WARN" -> warnings++
            }
        }
        return buildString {
            appendLine("IDE Log: ${logFile.absolutePath}")
            appendLine("Recent lines: ${lines.size}")
            appendLine("Errors: $errors")
            appendLine("Warnings: $warnings")
        }.trimEnd()
    }
}
