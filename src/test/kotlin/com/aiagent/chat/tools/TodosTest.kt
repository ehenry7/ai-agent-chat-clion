package com.aiagent.chat.tools

import org.junit.Assert.*
import org.junit.Test

class TodosTest {

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
}
