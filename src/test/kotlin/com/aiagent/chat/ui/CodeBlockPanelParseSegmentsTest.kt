package com.aiagent.chat.ui

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for CodeBlockPanel.parseSegments() — the markdown segment parser
 * that splits assistant responses into text and code segments.
 *
 * This tests the pure parsing logic without requiring an IntelliJ Platform instance.
 */
class CodeBlockPanelParseSegmentsTest {

    @Test
    fun `parseSegments with plain text returns single text segment`() {
        val input = "Hello, this is a plain text response with no code blocks."
        val segments = CodeBlockPanel.parseSegments(input)

        assertEquals(1, segments.size)
        assertTrue(segments[0] is CodeBlockPanel.ResponseSegment.Text)
        assertEquals(input, (segments[0] as CodeBlockPanel.ResponseSegment.Text).content)
    }

    @Test
    fun `parseSegments with single code block returns text and code segments`() {
        val input = "Here is some code:\n```kotlin\nval x = 42\n```"
        val segments = CodeBlockPanel.parseSegments(input)

        assertEquals(2, segments.size)
        assertTrue(segments[0] is CodeBlockPanel.ResponseSegment.Text)
        assertTrue(segments[1] is CodeBlockPanel.ResponseSegment.Code)

        val codeSegment = segments[1] as CodeBlockPanel.ResponseSegment.Code
        assertEquals("kotlin", codeSegment.language)
        assertEquals("val x = 42\n", codeSegment.content)
    }

    @Test
    fun `parseSegments with code block without language defaults to text`() {
        val input = "```\nprint('hello')\n```"
        val segments = CodeBlockPanel.parseSegments(input)

        assertEquals(1, segments.size)
        val codeSegment = segments[0] as CodeBlockPanel.ResponseSegment.Code
        assertEquals("text", codeSegment.language)
        // When no language is specified, firstNewline=0 so the whole raw string is content
        assertEquals("\nprint('hello')\n", codeSegment.content)
    }

    @Test
    fun `parseSegments with multiple code blocks returns alternating segments`() {
        val input = "First block:\n```python\nprint(1)\n```\nSecond block:\n```java\nSystem.out.println(2);\n```"
        val segments = CodeBlockPanel.parseSegments(input)

        assertEquals(4, segments.size)
        assertTrue(segments[0] is CodeBlockPanel.ResponseSegment.Text)
        assertTrue(segments[1] is CodeBlockPanel.ResponseSegment.Code)
        assertTrue(segments[2] is CodeBlockPanel.ResponseSegment.Text)
        assertTrue(segments[3] is CodeBlockPanel.ResponseSegment.Code)

        assertEquals("python", (segments[1] as CodeBlockPanel.ResponseSegment.Code).language)
        assertEquals("print(1)\n", (segments[1] as CodeBlockPanel.ResponseSegment.Code).content)
        assertEquals("java", (segments[3] as CodeBlockPanel.ResponseSegment.Code).language)
        assertEquals("System.out.println(2);\n", (segments[3] as CodeBlockPanel.ResponseSegment.Code).content)
    }

    @Test
    fun `parseSegments with empty input returns empty list`() {
        val segments = CodeBlockPanel.parseSegments("")
        assertEquals(0, segments.size)
    }

    @Test
    fun `parseSegments with only whitespace text returns empty list`() {
        val segments = CodeBlockPanel.parseSegments("   \n  \n  ")
        assertEquals(0, segments.size)
    }

    @Test
    fun `parseSegments with code block at start has no leading text segment`() {
        val input = "```bash\necho hello\n```"
        val segments = CodeBlockPanel.parseSegments(input)

        assertEquals(1, segments.size)
        assertTrue(segments[0] is CodeBlockPanel.ResponseSegment.Code)
        assertEquals("bash", (segments[0] as CodeBlockPanel.ResponseSegment.Code).language)
    }

    @Test
    fun `parseSegments with multiline code preserves newlines`() {
        val input = "```kotlin\nfun main() {\n    println(\"hello\")\n}\n```"
        val segments = CodeBlockPanel.parseSegments(input)

        assertEquals(1, segments.size)
        val code = (segments[0] as CodeBlockPanel.ResponseSegment.Code).content
        assertEquals("fun main() {\n    println(\"hello\")\n}\n", code)
    }

    @Test
    fun `parseSegments with text after code block returns trailing text`() {
        val input = "```python\nx = 1\n```\nThat was the code."
        val segments = CodeBlockPanel.parseSegments(input)

        assertEquals(2, segments.size)
        assertTrue(segments[0] is CodeBlockPanel.ResponseSegment.Code)
        assertTrue(segments[1] is CodeBlockPanel.ResponseSegment.Text)
        assertEquals("\nThat was the code.", (segments[1] as CodeBlockPanel.ResponseSegment.Text).content)
    }

    @Test
    fun `parseSegments with unclosed code block treats rest as code`() {
        val input = "```kotlin\nval x = 42"
        val segments = CodeBlockPanel.parseSegments(input)

        assertEquals(1, segments.size)
        assertTrue(segments[0] is CodeBlockPanel.ResponseSegment.Code)
        assertEquals("kotlin", (segments[0] as CodeBlockPanel.ResponseSegment.Code).language)
        assertEquals("val x = 42", (segments[0] as CodeBlockPanel.ResponseSegment.Code).content)
    }
}
