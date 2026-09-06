package com.aiagent.chat.tools

import com.aiagent.chat.model.ProviderConfig
import com.aiagent.chat.util.IdeaLogReader
import java.io.File

/**
 * Context passed to slash commands to enable real behavior.
 * Contains all the data commands need to produce meaningful output.
 */
data class SlashCommandContext(
    val projectRoot: String,
    // Configuration
    val baseUrl: String,
    val model: String,
    val apiKey: String,
    val maxSteps: Int,
    val approvalMode: String,
    val maxContextTokens: Int,
    val maxOutputTokens: Int,
    val multiProviderEnabled: Boolean,
    val dynamicRoutingEnabled: Boolean,
    val providers: List<ProviderConfig>,
    // Memory
    val folderMemory: String,
    val globalMemory: String,
    val summaryMemory: String,
    // Session info
    val sessionCount: Int,
    val activeMessageCount: Int,
    val todoCount: Int,
    val hasPlan: Boolean,
    val planSummary: String,
    // Usage
    val currentSessionTokens: Int,
    val totalInputTokens: Int,
    val totalOutputTokens: Int
)

/**
 * Result of a slash command execution.
 * Contains the message to display and an optional action for the panel to execute.
 */
data class SlashCommandResult(
    val message: String,
    val action: SlashCommandAction? = null
)

/**
 * Actions that slash commands can request the panel to perform.
 */
enum class SlashCommandAction {
    CLEAR_CONVERSATION,
    NEW_SESSION
}

object SlashCommands {

    data class Command(
        val name: String,
        val description: String
    )

    val BUILT_IN: Map<String, Command> = mapOf(
        "config" to Command("config", "Show active extension configuration"),
        "help" to Command("help", "List all available slash commands"),
        "memory" to Command("memory", "Display active persistent memory"),
        "status" to Command("status", "Show agent and workspace diagnostics"),
        "init" to Command("init", "Analyze repository and create AGENTS.md"),
        "clear" to Command("clear", "Clear current conversation"),
        "new" to Command("new", "Start new session"),
        "logs" to Command("logs", "Show recent IDE log entries"),
        "health" to Command("health", "Show runtime health diagnostics")
    )

    fun isLocalCommand(text: String): Boolean {
        val cmd = text.trim().removePrefix("/").split("\\s+".toRegex())[0].lowercase()
        return BUILT_IN.containsKey(cmd)
    }

    fun processCommand(text: String, context: SlashCommandContext): SlashCommandResult? {
        val parts = text.trim().removePrefix("/").split("\\s+".toRegex())
        val name = parts[0].lowercase()

        return when (name) {
            "config" -> SlashCommandResult(formatConfig(context))
            "help" -> SlashCommandResult(formatHelp())
            "memory" -> SlashCommandResult(formatMemory(context))
            "status" -> SlashCommandResult(formatStatus(context))
            "init" -> SlashCommandResult(initProject(context))
            "clear" -> SlashCommandResult("Conversation cleared.", SlashCommandAction.CLEAR_CONVERSATION)
            "new" -> SlashCommandResult("New session started.", SlashCommandAction.NEW_SESSION)
            "logs" -> SlashCommandResult(formatLogs(parts.drop(1)))
            "health" -> SlashCommandResult(formatHealth())
            else -> null
        }
    }

    // --- Command implementations ---

    fun formatConfig(context: SlashCommandContext): String {
        val lines = mutableListOf<String>()
        lines.add("## Configuration")
        lines.add("")
        lines.add("**Base URL:** `${context.baseUrl}`")
        lines.add("**Model:** `${context.model}`")
        lines.add("**API Key:** ${maskApiKey(context.apiKey)}")
        lines.add("**Max Steps:** ${context.maxSteps}")
        lines.add("**Approval Mode:** ${context.approvalMode}")
        lines.add("**Max Context Tokens:** ${context.maxContextTokens}")
        lines.add("**Max Output Tokens:** ${context.maxOutputTokens}")
        lines.add("**Multi-Provider:** ${if (context.multiProviderEnabled) "Enabled" else "Disabled"}")
        lines.add("**Dynamic Routing:** ${if (context.dynamicRoutingEnabled) "Enabled" else "Disabled"}")

        if (context.providers.isNotEmpty()) {
            lines.add("")
            lines.add("### Providers (${context.providers.size})")
            for (p in context.providers) {
                lines.add("- **${p.name}** -- `${p.baseUrl}` (${p.models.size} models, auth: ${p.authHeaderType})")
            }
        }

        return lines.joinToString("\n")
    }

    fun formatHelp(): String {
        val lines = mutableListOf<String>()
        lines.add("## Available Slash Commands")
        lines.add("")
        for ((_, cmd) in BUILT_IN) {
            lines.add("- `/${cmd.name}` -- ${cmd.description}")
        }
        lines.add("")
        lines.add("Type a command followed by arguments if needed.")
        return lines.joinToString("\n")
    }

    fun formatMemory(context: SlashCommandContext): String {
        val lines = mutableListOf<String>()
        lines.add("## Persistent Memory")
        lines.add("")

        // L1 Summary
        lines.add("### L1 Summary (Rolling Context)")
        if (context.summaryMemory.isNotBlank()) {
            lines.add("```")
            lines.add(context.summaryMemory.take(500))
            if (context.summaryMemory.length > 500) lines.add("... (${context.summaryMemory.length} chars total)")
            lines.add("```")
        } else {
            lines.add("_No summary memory yet._")
        }
        lines.add("")

        // L2 Folder Memory (AGENTS.md)
        lines.add("### L2 Workspace Rules (AGENTS.md)")
        if (context.folderMemory.isNotBlank()) {
            lines.add("```")
            lines.add(context.folderMemory.take(1000))
            if (context.folderMemory.length > 1000) lines.add("... (${context.folderMemory.length} chars total)")
            lines.add("```")
        } else {
            lines.add("_No AGENTS.md found. Use `/init` to create one._")
        }
        lines.add("")

        // L3 Global Memory
        lines.add("### L3 Global Rules")
        if (context.globalMemory.isNotBlank()) {
            lines.add("```")
            lines.add(context.globalMemory.take(500))
            if (context.globalMemory.length > 500) lines.add("... (${context.globalMemory.length} chars total)")
            lines.add("```")
        } else {
            lines.add("_No global memory set._")
        }

        return lines.joinToString("\n")
    }

    fun formatStatus(context: SlashCommandContext): String {
        val lines = mutableListOf<String>()
        lines.add("## Status")
        lines.add("")
        lines.add("### Session")
        lines.add("- **Open Sessions:** ${context.sessionCount}")
        lines.add("- **Active Conversation Messages:** ${context.activeMessageCount}")
        lines.add("- **Todo Items:** ${context.todoCount}")
        lines.add("- **Active Plan:** ${if (context.hasPlan) "Yes" else "No"}")
        if (context.hasPlan && context.planSummary.isNotBlank()) {
            lines.add("  - ${context.planSummary}")
        }
        lines.add("")
        lines.add("### Token Usage")
        lines.add("- **Current Session Tokens:** ${context.currentSessionTokens} / ${context.maxContextTokens}")
        val pct = if (context.maxContextTokens > 0) (context.currentSessionTokens.toDouble() / context.maxContextTokens * 100).toInt() else 0
        lines.add("- **Context Window:** ${pct}%")
        lines.add("- **Total Input Tokens:** ${context.totalInputTokens}")
        lines.add("- **Total Output Tokens:** ${context.totalOutputTokens}")
        lines.add("")
        lines.add("### Model")
        lines.add("- **Active Model:** `${context.model}`")
        lines.add("- **Base URL:** `${context.baseUrl}`")
        if (context.multiProviderEnabled) {
            lines.add("- **Multi-Provider:** Enabled (${context.providers.size} providers)")
        }

        return lines.joinToString("\n")
    }

    fun initProject(context: SlashCommandContext): String {
        val projectDir = File(context.projectRoot)
        if (!projectDir.exists() || !projectDir.isDirectory) {
            return "Error: Project directory not found at `${context.projectRoot}`"
        }

        val agentsFile = File(projectDir, "AGENTS.md")

        // Build a basic AGENTS.md from project analysis
        val sb = StringBuilder()
        sb.appendLine("# AGENTS.md")
        sb.appendLine()
        sb.appendLine("## Project Overview")
        sb.appendLine()
        sb.appendLine("Project root: `${context.projectRoot}`")
        sb.appendLine()

        // Detect build system
        val hasGradle = File(projectDir, "build.gradle").exists() || File(projectDir, "build.gradle.kts").exists()
        val hasMaven = File(projectDir, "pom.xml").exists()
        val hasGit = File(projectDir, ".git").exists()
        val hasCargo = File(projectDir, "Cargo.toml").exists()
        val hasPackageJson = File(projectDir, "package.json").exists()

        sb.appendLine("## Build System")
        sb.appendLine()
        when {
            hasGradle -> sb.appendLine("- **Gradle** (build.gradle or build.gradle.kts detected)")
            hasMaven -> sb.appendLine("- **Maven** (pom.xml detected)")
            hasCargo -> sb.appendLine("- **Cargo** (Cargo.toml detected)")
            hasPackageJson -> sb.appendLine("- **npm/Node.js** (package.json detected)")
            else -> sb.appendLine("- No standard build system detected")
        }
        if (hasGit) sb.appendLine("- **Git** repository")
        sb.appendLine()

        // List top-level directories
        sb.appendLine("## Project Structure")
        sb.appendLine()
        sb.appendLine("```")
        val topDirs = projectDir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }?.sortedBy { it.name } ?: emptyList()
        val topFiles = projectDir.listFiles { f -> f.isFile && !f.name.startsWith(".") }?.sortedBy { it.name } ?: emptyList()
        for (d in topDirs.take(15)) {
            val fileCount = countFilesRecursively(d, maxDepth = 2)
            sb.appendLine("$d/ ($fileCount files)")
        }
        for (f in topFiles.take(10)) {
            sb.appendLine(f.name)
        }
        if (topDirs.size > 15) sb.appendLine("... (${topDirs.size - 15} more directories)")
        sb.appendLine("```")
        sb.appendLine()

        // Language detection
        sb.appendLine("## Languages")
        sb.appendLine()
        val langCounts = mutableMapOf<String, Int>()
        countFilesByExtension(projectDir, langCounts, depth = 0, maxDepth = 3)
        val sortedLangs = langCounts.entries.sortedByDescending { it.value }.take(10)
        for ((ext, count) in sortedLangs) {
            sb.appendLine("- .$ext -- $count files")
        }
        sb.appendLine()

        sb.appendLine("## Coding Guidelines")
        sb.appendLine()
        sb.appendLine("- Follow existing code style in the project")
        sb.appendLine("- Write clear, self-documenting code")
        sb.appendLine("- Add tests for new functionality")
        sb.appendLine("- Keep changes focused and reviewable")
        sb.appendLine()

        // Write the file
        return try {
            agentsFile.writeText(sb.toString())
            "Created `AGENTS.md` at project root.\n\nAnalyzed ${topDirs.size} directories, ${sortedLangs.size} file types. Review and customize as needed."
        } catch (e: Exception) {
            "Error writing AGENTS.md: ${e.message}"
        }
    }

    fun maskApiKey(key: String): String {
        if (key.isBlank()) return "_Not set_"
        if (key.length <= 8) return "****"
        return key.take(4) + "****" + key.takeLast(4)
    }

    /**
     * Format IDE log entries for the /logs slash command.
     * Supports optional arguments: /logs error, /logs warn, /logs <search-pattern>
     */
    fun formatLogs(args: List<String>): String {
        val lines = mutableListOf<String>()
        lines.add("## IDE Logs")
        lines.add("")

        val summary = IdeaLogReader.getSummary()
        lines.add("```")
        lines.add(summary)
        lines.add("```")
        lines.add("")

        val logLines = if (args.isNotEmpty()) {
            val arg = args.joinToString(" ")
            when (arg.uppercase()) {
                "ERROR", "ERR" -> IdeaLogReader.readByLevel("ERROR", 50)
                "WARN", "WARNING" -> IdeaLogReader.readByLevel("WARN", 50)
                "INFO" -> IdeaLogReader.readByLevel("INFO", 50)
                else -> IdeaLogReader.search(arg, 50)
            }
        } else {
            IdeaLogReader.readRecent(50)
        }

        if (logLines.isEmpty()) {
            lines.add("_No log entries found._")
        } else {
            lines.add("### Recent Entries (${logLines.size})")
            lines.add("```")
            for (line in logLines) {
                lines.add(line)
            }
            lines.add("```")
        }

        return lines.joinToString("\n")
    }

    /**
     * Format runtime health diagnostics for the /health slash command.
     */
    fun formatHealth(): String {
        val lines = mutableListOf<String>()
        lines.add("## Runtime Health")
        lines.add("")

        // Memory info
        val runtime = Runtime.getRuntime()
        val usedMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val maxMB = runtime.maxMemory() / (1024 * 1024)
        val pct = if (maxMB > 0) (usedMB.toDouble() / maxMB) * 100 else 0.0
        lines.add("### Memory")
        lines.add("- **Heap Used:** ${usedMB}MB / ${maxMB}MB (${pct.toInt()}%)")
        if (pct >= 85.0) {
            lines.add("- **WARNING:** Memory usage above 85%")
        }
        if (pct >= 95.0) {
            lines.add("- **CRITICAL:** Memory usage above 95%")
        }
        lines.add("")

        // Thread info
        val threadCount = Thread.activeCount()
        lines.add("### Threads")
        lines.add("- **Active Threads:** $threadCount")
        if (threadCount > 200) {
            lines.add("- **WARNING:** High thread count (possible coroutine leak)")
        }
        lines.add("")

        // Available processors
        lines.add("### System")
        lines.add("- **Available Processors:** ${runtime.availableProcessors()}")
        lines.add("- **Java Version:** ${System.getProperty("java.version")}")
        lines.add("")

        return lines.joinToString("\n")
    }

    private fun countFilesRecursively(dir: File, maxDepth: Int, currentDepth: Int = 0): Int {
        if (currentDepth >= maxDepth) return 0
        var count = 0
        dir.listFiles()?.forEach { f ->
            if (f.isDirectory && !f.name.startsWith(".") && f.name != "build" && f.name != "target" && f.name != "node_modules") {
                count += countFilesRecursively(f, maxDepth, currentDepth + 1)
            } else if (f.isFile) {
                count++
            }
        }
        return count
    }

    private fun countFilesByExtension(dir: File, counts: MutableMap<String, Int>, depth: Int, maxDepth: Int) {
        if (depth >= maxDepth) return
        dir.listFiles()?.forEach { f ->
            if (f.isDirectory && !f.name.startsWith(".") && f.name != "build" && f.name != "target" && f.name != "node_modules" && f.name != ".gradle") {
                countFilesByExtension(f, counts, depth + 1, maxDepth)
            } else if (f.isFile) {
                val ext = f.extension.ifBlank { "no-ext" }
                counts[ext] = (counts[ext] ?: 0) + 1
            }
        }
    }
}
