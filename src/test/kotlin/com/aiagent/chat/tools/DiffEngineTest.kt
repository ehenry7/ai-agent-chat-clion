package com.aiagent.chat.tools

import org.junit.Assert.*
import org.junit.Test

class DiffEngineTest {

    // --- levenshteinDistance ---

    @Test
    fun testLevenshteinDistance() {
        val dist = DiffEngine.levenshteinDistance("kitten", "sitting")
        assertEquals(3, dist)
    }

    @Test
    fun `levenshteinDistance returns 0 for identical strings`() {
        assertEquals(0, DiffEngine.levenshteinDistance("hello", "hello"))
    }

    @Test
    fun `levenshteinDistance returns length for empty string vs non-empty`() {
        assertEquals(5, DiffEngine.levenshteinDistance("", "hello"))
        assertEquals(5, DiffEngine.levenshteinDistance("hello", ""))
    }

    @Test
    fun `levenshteinDistance returns 0 for both empty strings`() {
        assertEquals(0, DiffEngine.levenshteinDistance("", ""))
    }

    @Test
    fun `levenshteinDistance handles single character difference`() {
        assertEquals(1, DiffEngine.levenshteinDistance("cat", "bat"))
    }

    // --- normalizeString ---

    @Test
    fun `normalizeString replaces smart quotes with straight quotes`() {
        val result = DiffEngine.normalizeString("\u201Chello\u201D")
        assertEquals("\"hello\"", result)
    }

    @Test
    fun `normalizeString replaces smart single quotes`() {
        val result = DiffEngine.normalizeString("\u2018hello\u2019")
        assertEquals("'hello'", result)
    }

    @Test
    fun `normalizeString replaces ellipsis`() {
        val result = DiffEngine.normalizeString("wait\u2026")
        assertEquals("wait...", result)
    }

    @Test
    fun `normalizeString replaces em-dash and en-dash`() {
        assertEquals("a-b", DiffEngine.normalizeString("a\u2014b"))
        assertEquals("a-b", DiffEngine.normalizeString("a\u2013b"))
    }

    @Test
    fun `normalizeString replaces non-breaking space with regular space`() {
        assertEquals("a b", DiffEngine.normalizeString("a\u00A0b"))
    }

    @Test
    fun `normalizeString collapses multiple whitespace into single space`() {
        assertEquals("a b c", DiffEngine.normalizeString("a   b\t\tc\n\n"))
    }

    @Test
    fun `normalizeString trims leading and trailing whitespace`() {
        assertEquals("hello", DiffEngine.normalizeString("  hello  "))
    }

    // --- getSimilarity ---

    @Test
    fun `getSimilarity returns 1_0 for identical strings`() {
        assertEquals(1.0, DiffEngine.getSimilarity("hello", "hello"), 0.001)
    }

    @Test
    fun `getSimilarity returns 0_0 for empty search`() {
        assertEquals(0.0, DiffEngine.getSimilarity("hello", ""), 0.001)
    }

    @Test
    fun `getSimilarity returns high value for similar strings`() {
        val sim = DiffEngine.getSimilarity("hello world", "hello worl")
        assertTrue("Similarity should be > 0.8", sim > 0.8)
    }

    @Test
    fun `getSimilarity returns low value for different strings`() {
        val sim = DiffEngine.getSimilarity("hello world", "xyz abcde")
        assertTrue("Similarity should be < 0.5", sim < 0.5)
    }

    @Test
    fun `getSimilarity normalizes before comparing`() {
        val sim = DiffEngine.getSimilarity("hello\u00A0world", "hello world")
        assertEquals(1.0, sim, 0.001)
    }

    // --- applyDiff ---

    @Test
    fun testApplyDiffSuccess() {
        val original = "fun hello() {\n  println(\"World\")\n}"
        val diff = """
<<<<<<< SEARCH
  println("World")
=======
  println("CLion")
>>>>>>> REPLACE
        """.trimIndent()

        val result = DiffEngine.applyDiff(original, diff)
        assertTrue(result.success)
        assertTrue(result.content!!.contains("println(\"CLion\")"))
    }

    @Test
    fun `applyDiff returns failure for invalid diff format`() {
        val result = DiffEngine.applyDiff("original content", "not a valid diff")
        assertFalse(result.success)
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("Invalid diff format"))
    }

    @Test
    fun `applyDiff returns failure when search block not found`() {
        val original = "line1\nline2\nline3"
        val diff = """
<<<<<<< SEARCH
nonexistent line
=======
replacement
>>>>>>> REPLACE
        """.trimIndent()

        val result = DiffEngine.applyDiff(original, diff)
        assertFalse(result.success)
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("No sufficiently similar match"))
    }

    @Test
    fun `applyDiff handles multiple search-replace blocks`() {
        val original = "fun a() {\n  println(1)\n}\n\nfun b() {\n  println(2)\n}"
        val diff = """
<<<<<<< SEARCH
  println(1)
=======
  println(10)
>>>>>>> REPLACE
<<<<<<< SEARCH
  println(2)
=======
  println(20)
>>>>>>> REPLACE
        """.trimIndent()

        val result = DiffEngine.applyDiff(original, diff)
        assertTrue(result.success)
        assertTrue(result.content!!.contains("println(10)"))
        assertTrue(result.content!!.contains("println(20)"))
        assertFalse(result.content!!.contains("println(1)"))
        assertFalse(result.content!!.contains("println(2)"))
    }

    @Test
    fun `applyDiff preserves CRLF line endings`() {
        val original = "line1\r\nold\r\nline3"
        val diff = """
<<<<<<< SEARCH
old
=======
new
>>>>>>> REPLACE
        """.trimIndent()

        val result = DiffEngine.applyDiff(original, diff)
        assertTrue(result.success)
        assertTrue(result.content!!.contains("\r\n"))
        assertTrue(result.content!!.contains("new"))
    }

    @Test
    fun `applyDiff handles empty replace block as deletion`() {
        val original = "line1\nto delete\nline3"
        val diff = """
<<<<<<< SEARCH
to delete
=======
>>>>>>> REPLACE
        """.trimIndent()

        val result = DiffEngine.applyDiff(original, diff)
        assertTrue(result.success)
        assertFalse(result.content!!.contains("to delete"))
    }

    @Test
    fun `applyDiff handles start_line hint`() {
        val original = "line1\nold\nline3\nold"
        val diff = """
<<<<<<< SEARCH
:start_line: 2
old
=======
new
>>>>>>> REPLACE
        """.trimIndent()

        val result = DiffEngine.applyDiff(original, diff)
        assertTrue(result.success)
    }

    // --- fuzzySearch ---

    @Test
    fun `fuzzySearch finds exact match`() {
        val lines = listOf("alpha", "beta", "gamma", "delta")
        val (score, idx, _) = DiffEngine.fuzzySearch(lines, "beta", 0, lines.size)
        assertTrue(score >= 1.0)
        assertEquals(1, idx)
    }

    @Test
    fun `fuzzySearch returns low score when no good match`() {
        val lines = listOf("alpha", "beta")
        val (score, idx, _) = DiffEngine.fuzzySearch(lines, "completely different text", 0, lines.size)
        // fuzzySearch always returns the best match found, even if similarity is very low
        assertTrue("Score should be low for no good match, got $score", score < 0.3)
    }

    @Test
    fun `fuzzySearch finds best match among similar lines`() {
        val lines = listOf("fun test() {}", "fun test2() {}", "fun test3() {}")
        val (score, idx, _) = DiffEngine.fuzzySearch(lines, "fun test2() {}", 0, lines.size)
        assertTrue(score >= 1.0)
        assertEquals(1, idx)
    }
}
