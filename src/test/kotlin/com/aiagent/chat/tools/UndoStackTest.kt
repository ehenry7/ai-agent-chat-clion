package com.aiagent.chat.tools

import org.junit.Assert.*
import org.junit.Test

class UndoStackTest {

    @Test
    fun testPushAndPop() {
        val stack = UndoStack()
        stack.push("file1.txt", "original content")
        assertEquals(1, stack.size())

        val snapshot = stack.pop()
        assertNotNull(snapshot)
        assertEquals("file1.txt", snapshot!!.path)
        assertEquals("original content", snapshot.content)
        assertTrue(stack.isEmpty())
    }

    @Test
    fun testPopEmpty() {
        val stack = UndoStack()
        assertNull(stack.pop())
    }

    @Test
    fun testPopForPath() {
        val stack = UndoStack()
        stack.push("file1.txt", "content1")
        stack.push("file2.txt", "content2")
        stack.push("file3.txt", "content3")

        val snapshot = stack.popForPath("file2.txt")
        assertNotNull(snapshot)
        assertEquals("file2.txt", snapshot!!.path)
        assertEquals("content2", snapshot.content)
        assertEquals(2, stack.size())
    }

    @Test
    fun testPopForPathNotFound() {
        val stack = UndoStack()
        stack.push("file1.txt", "content1")
        assertNull(stack.popForPath("nonexistent.txt"))
        assertEquals(1, stack.size())
    }

    @Test
    fun testMaxStackSize() {
        val stack = UndoStack()
        for (i in 1..(UndoStack.MAX_STACK_SIZE + 10)) {
            stack.push("file$i.txt", "content$i")
        }
        assertEquals(UndoStack.MAX_STACK_SIZE, stack.size())
        // The oldest entries should have been dropped
        val last = stack.pop()
        assertNotNull(last)
        assertEquals("file${UndoStack.MAX_STACK_SIZE + 10}.txt", last!!.path)
    }

    @Test
    fun testPeek() {
        val stack = UndoStack()
        stack.push("file1.txt", "content1")
        stack.push("file2.txt", "content2")

        val peeked = stack.peek()
        assertNotNull(peeked)
        assertEquals("file2.txt", peeked!!.path)
        assertEquals(2, stack.size()) // peek doesn't remove
    }

    @Test
    fun testClear() {
        val stack = UndoStack()
        stack.push("file1.txt", "content1")
        stack.push("file2.txt", "content2")
        stack.clear()
        assertTrue(stack.isEmpty())
    }

    @Test
    fun testLifoOrder() {
        val stack = UndoStack()
        stack.push("file1.txt", "content1")
        stack.push("file2.txt", "content2")
        stack.push("file3.txt", "content3")

        assertEquals("file3.txt", stack.pop()!!.path)
        assertEquals("file2.txt", stack.pop()!!.path)
        assertEquals("file1.txt", stack.pop()!!.path)
    }
}
