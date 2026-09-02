package com.aiagent.chat.tools

import com.aiagent.chat.model.TodoItem
import java.security.MessageDigest

object Todos {
    fun parseMarkdownChecklist(md: String): List<TodoItem> {
        val items = mutableListOf<TodoItem>()
        val lines = md.split(Regex("\\r?\\n")).map { it.trim() }.filter { it.isNotEmpty() }
        val regex = Regex("^(?:-\\s*)?\\[\\s*([ xX\\-~])\\s*\\]\\s+(.+)$")

        for (line in lines) {
            val match = regex.find(line) ?: continue
            val mark = match.groupValues[1]
            val content = match.groupValues[2]
            val status = when (mark) {
                "x", "X" -> "completed"
                "-", "~" -> "in_progress"
                else -> "pending"
            }
            val id = md5(content + status)
            items.add(TodoItem(id, content, status))
        }
        return items
    }

    fun todoListToMarkdown(todos: List<TodoItem>): String {
        return todos.joinToString("\n") { t ->
            val box = when (t.status) {
                "completed" -> "[x]"
                "in_progress" -> "[-]"
                else -> "[ ]"
            }
            "$box ${t.content}"
        }
    }

    fun formatReminderSection(todoList: List<TodoItem>): String {
        if (todoList.isEmpty()) {
            return "You have not created a todo list yet. Create one with `update_todo_list` if your task involves multiple steps."
        }
        val lines = mutableListOf("====", "", "REMINDERS", "", "Below is your current list of reminders for this task:", "")
        lines.add("| # | Content | Status |")
        lines.add("|---|---------|--------|")
        todoList.forEachIndexed { i, item ->
            lines.add("| ${i + 1} | ${item.content.replace("|", "\\|")} | ${item.status} |")
        }
        return lines.joinToString("\n")
    }

    private fun md5(str: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(str.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
