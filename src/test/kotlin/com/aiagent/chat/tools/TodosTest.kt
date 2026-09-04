package com.aiagent.chat.tools

import com.aiagent.chat.model.TodoItem
import org.junit.Assert.*
import org.junit.Test

class TodosTest {

    // --- parseMarkdownChecklist ---

    @Test
    fun testParseMarkdownChecklist() {
        val md = """
            - [ ] Pending task
            - [x] Done task
            - [-] Active task
        """.trimIndent()

        val todos = Todos.parseMarkdownChecklist(md)
        assertEquals(3, todos.size)
        assertEquals("pending", todos[0].status)
        assertEquals("completed", todos[1].status)
        assertEquals("in_progress", todos[2].status)
    }

    @Test
    fun `parseMarkdownChecklist handles tilde as in_progress`() {
        val md = "- [~] Active task"
        val todos = Todos.parseMarkdownChecklist(md)
        assertEquals(1, todos.size)
        assertEquals("in_progress", todos[0].status)
    }

    @Test
    fun `parseMarkdownChecklist handles uppercase X as completed`() {
        val md = "- [X] Done task"
        val todos = Todos.parseMarkdownChecklist(md)
        assertEquals(1, todos.size)
        assertEquals("completed", todos[0].status)
    }

    @Test
    fun `parseMarkdownChecklist handles lines without dash prefix`() {
        val md = "[ ] task without dash"
        val todos = Todos.parseMarkdownChecklist(md)
        assertEquals(1, todos.size)
        assertEquals("pending", todos[0].status)
    }

    @Test
    fun `parseMarkdownChecklist ignores non-checklist lines`() {
        val md = """
            Some regular text
            - [ ] Real task
            Another line
            - [x] Another real task
        """.trimIndent()
        val todos = Todos.parseMarkdownChecklist(md)
        assertEquals(2, todos.size)
    }

    @Test
    fun `parseMarkdownChecklist returns empty list for empty input`() {
        assertTrue(Todos.parseMarkdownChecklist("").isEmpty())
    }

    @Test
    fun `parseMarkdownChecklist returns empty list for no checklist items`() {
        assertTrue(Todos.parseMarkdownChecklist("just some text\nno checkboxes").isEmpty())
    }

    @Test
    fun `parseMarkdownChecklist generates unique IDs for different items`() {
        val md = "- [ ] Task A\n- [ ] Task B"
        val todos = Todos.parseMarkdownChecklist(md)
        assertNotEquals(todos[0].id, todos[1].id)
    }

    @Test
    fun `parseMarkdownChecklist generates same ID for same content and status`() {
        val md = "- [ ] Task A\n- [ ] Task A"
        val todos = Todos.parseMarkdownChecklist(md)
        assertEquals(todos[0].id, todos[1].id)
    }

    // --- todoListToMarkdown ---

    @Test
    fun `todoListToMarkdown converts pending item`() {
        val todos = listOf(TodoItem("1", "My task", "pending"))
        val md = Todos.todoListToMarkdown(todos)
        assertEquals("[ ] My task", md)
    }

    @Test
    fun `todoListToMarkdown converts completed item`() {
        val todos = listOf(TodoItem("1", "Done task", "completed"))
        val md = Todos.todoListToMarkdown(todos)
        assertEquals("[x] Done task", md)
    }

    @Test
    fun `todoListToMarkdown converts in_progress item`() {
        val todos = listOf(TodoItem("1", "Active task", "in_progress"))
        val md = Todos.todoListToMarkdown(todos)
        assertEquals("[-] Active task", md)
    }

    @Test
    fun `todoListToMarkdown converts multiple items with newlines`() {
        val todos = listOf(
            TodoItem("1", "Task A", "pending"),
            TodoItem("2", "Task B", "completed")
        )
        val md = Todos.todoListToMarkdown(todos)
        assertEquals("[ ] Task A\n[x] Task B", md)
    }

    @Test
    fun `todoListToMarkdown handles empty list`() {
        assertEquals("", Todos.todoListToMarkdown(emptyList()))
    }

    @Test
    fun `todoListToMarkdown handles unknown status as pending`() {
        val todos = listOf(TodoItem("1", "Weird task", "unknown_status"))
        val md = Todos.todoListToMarkdown(todos)
        assertEquals("[ ] Weird task", md)
    }

    // --- formatReminderSection ---

    @Test
    fun `formatReminderSection returns prompt for empty list`() {
        val result = Todos.formatReminderSection(emptyList())
        assertTrue(result.contains("not created a todo list"))
        assertTrue(result.contains("update_todo_list"))
    }

    @Test
    fun `formatReminderSection includes header and table`() {
        val todos = listOf(TodoItem("1", "Task A", "pending"))
        val result = Todos.formatReminderSection(todos)
        assertTrue(result.contains("REMINDERS"))
        assertTrue(result.contains("| # | Content | Status |"))
        assertTrue(result.contains("Task A"))
        assertTrue(result.contains("pending"))
    }

    @Test
    fun `formatReminderSection numbers items sequentially`() {
        val todos = listOf(
            TodoItem("1", "Task A", "pending"),
            TodoItem("2", "Task B", "completed"),
            TodoItem("3", "Task C", "in_progress")
        )
        val result = Todos.formatReminderSection(todos)
        assertTrue(result.contains("| 1 | Task A | pending |"))
        assertTrue(result.contains("| 2 | Task B | completed |"))
        assertTrue(result.contains("| 3 | Task C | in_progress |"))
    }

    @Test
    fun `formatReminderSection escapes pipe characters in content`() {
        val todos = listOf(TodoItem("1", "Task with | pipe", "pending"))
        val result = Todos.formatReminderSection(todos)
        assertTrue(result.contains("Task with \\| pipe"))
    }

    // --- Round-trip: parse -> toMarkdown -> parse ---

    @Test
    fun `round trip parse to markdown to parse preserves content and status`() {
        val originalMd = """
            - [ ] Task A
            - [x] Task B
            - [-] Task C
        """.trimIndent()
        val parsed = Todos.parseMarkdownChecklist(originalMd)
        val regenerated = Todos.todoListToMarkdown(parsed)
        val reparsed = Todos.parseMarkdownChecklist(regenerated)
        assertEquals(parsed.size, reparsed.size)
        for (i in parsed.indices) {
            assertEquals(parsed[i].content, reparsed[i].content)
            assertEquals(parsed[i].status, reparsed[i].status)
        }
    }
}
