package com.aiagent.chat.tools

import java.io.File

object SlashCommands {

    data class Command(
        val name: String,
        val description: String,
        val content: String
    )

    private val BUILT_IN = mapOf(
        "config" to Command("config", "Show active extension configuration", "Display config"),
        "help" to Command("help", "List all available slash commands", "List commands"),
        "memory" to Command("memory", "Display active persistent memory", "Show memory"),
        "status" to Command("status", "Show agent and workspace diagnostics", "Show status"),
        "init" to Command("init", "Analyze repository and create AGENTS.md", "Analyze codebase and create AGENTS.md"),
        "clear" to Command("clear", "Clear current conversation", "Clear chat"),
        "new" to Command("new", "Start new session", "New chat")
    )

    fun isLocalCommand(text: String): Boolean {
        val cmd = text.trim().removePrefix("/").split("\\s+".toRegex())[0].lowercase()
        return BUILT_IN.containsKey(cmd)
    }

    fun processCommand(text: String, projectRoot: String): String? {
        val parts = text.trim().removePrefix("/").split("\\s+".toRegex())
        val name = parts[0].lowercase()
        return BUILT_IN[name]?.content
    }
}
