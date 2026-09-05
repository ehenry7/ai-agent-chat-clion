package com.aiagent.chat.tools

import org.junit.Assert.*
import org.junit.Test

class PatchEngineTest {

    // --- parsePatch tests ---

    @Test
    fun `parsePatch parses AddFile hunk`() {
        val patch = """
            *** Add File: src/NewFile.kt
            +package com.example
            +
            +fun main() {}
        """.trimIndent()

        val hunks = PatchEngine.parsePatch(patch)
        assertEquals(1, hunks.size)
        val add = hunks[0] as PatchEngine.Hunk.AddFile
        assertEquals("src/NewFile.kt", add.path)
        assertTrue(add.contents.contains("fun main()"))
        assertTrue(add.contents.contains("package com.example"))
    }

    @Test
    fun `parsePatch parses DeleteFile hunk`() {
        val patch = "*** Delete File: src/OldFile.kt"
        val hunks = PatchEngine.parsePatch(patch)
        assertEquals(1, hunks.size)
        val del = hunks[0] as PatchEngine.Hunk.DeleteFile
        assertEquals("src/OldFile.kt", del.path)
    }

    @Test
    fun `parsePatch parses UpdateFile hunk with chunks`() {
        val patch = """
            *** Update File: src/Main.kt
            @@ context
             unchanged line
            -old line
            +new line
        """.trimIndent()

        val hunks = PatchEngine.parsePatch(patch)
        assertEquals(1, hunks.size)
        val upd = hunks[0] as PatchEngine.Hunk.UpdateFile
        assertEquals("src/Main.kt", upd.path)
        assertNull(upd.movePath)
        assertEquals(1, upd.chunks.size)
        val chunk = upd.chunks[0]
        assertEquals("context", chunk.changeContext)
        assertEquals(listOf("unchanged line", "old line"), chunk.oldLines)
        assertEquals(listOf("unchanged line", "new line"), chunk.newLines)
        assertFalse(chunk.isEndOfFile)
    }

    @Test
    fun `parsePatch parses UpdateFile with Move to`() {
        val patch = """
            *** Update File: src/OldPath.kt
            *** Move to: src/NewPath.kt
            @@
            -old
            +new
        """.trimIndent()

        val hunks = PatchEngine.parsePatch(patch)
        assertEquals(1, hunks.size)
        val upd = hunks[0] as PatchEngine.Hunk.UpdateFile
        assertEquals("src/OldPath.kt", upd.path)
        assertEquals("src/NewPath.kt", upd.movePath)
    }

    @Test
    fun `parsePatch parses End of File marker`() {
        val patch = """
            *** Update File: src/Main.kt
            @@
            *** End of File
            +appended line
        """.trimIndent()

        val hunks = PatchEngine.parsePatch(patch)
        val upd = hunks[0] as PatchEngine.Hunk.UpdateFile
        assertTrue(upd.chunks[0].isEndOfFile)
    }

    @Test
    fun `parsePatch parses multiple hunks`() {
        val patch = """
            *** Add File: a.kt
            +fun a() {}
            *** Delete File: b.kt
            *** Update File: c.kt
            @@
            -old
            +new
        """.trimIndent()

        val hunks = PatchEngine.parsePatch(patch)
        assertEquals(3, hunks.size)
        assertTrue(hunks[0] is PatchEngine.Hunk.AddFile)
        assertTrue(hunks[1] is PatchEngine.Hunk.DeleteFile)
        assertTrue(hunks[2] is PatchEngine.Hunk.UpdateFile)
    }

    @Test
    fun `parsePatch handles empty patch`() {
        val hunks = PatchEngine.parsePatch("")
        assertTrue(hunks.isEmpty())
    }

    @Test
    fun `parsePatch handles chunk with no context`() {
        val patch = """
            *** Update File: src/Main.kt
            @@
            -old
            +new
        """.trimIndent()

        val hunks = PatchEngine.parsePatch(patch)
        val upd = hunks[0] as PatchEngine.Hunk.UpdateFile
        assertNull(upd.chunks[0].changeContext)
    }

    // --- applyChunksToContent tests ---

    @Test
    fun `applyChunksToContent replaces matching text`() {
        val original = "line1\nold line\nline3"
        val chunk = PatchEngine.UpdateFileChunk(
            changeContext = null,
            oldLines = listOf("old line"),
            newLines = listOf("new line"),
            isEndOfFile = false
        )
        val result = PatchEngine.applyChunksToContent(original, listOf(chunk))
        assertEquals("line1\nnew line\nline3", result)
    }

    @Test
    fun `applyChunksToContent appends at end of file`() {
        val original = "line1\nline2"
        val chunk = PatchEngine.UpdateFileChunk(
            changeContext = null,
            oldLines = emptyList(),
            newLines = listOf("line3", "line4"),
            isEndOfFile = true
        )
        val result = PatchEngine.applyChunksToContent(original, listOf(chunk))
        assertEquals("line1\nline2\nline3\nline4", result)
    }

    @Test
    fun `applyChunksToContent handles multi-line search block`() {
        val original = "fun a() {\n  println(1)\n  println(2)\n}"
        val chunk = PatchEngine.UpdateFileChunk(
            changeContext = null,
            oldLines = listOf("  println(1)", "  println(2)"),
            newLines = listOf("  println(3)"),
            isEndOfFile = false
        )
        val result = PatchEngine.applyChunksToContent(original, listOf(chunk))
        assertTrue(result.contains("println(3)"))
        assertFalse(result.contains("println(1)"))
        assertFalse(result.contains("println(2)"))
    }

    @Test
    fun `applyChunksToContent preserves CRLF line endings`() {
        val original = "line1\r\nold\r\nline3"
        val chunk = PatchEngine.UpdateFileChunk(
            changeContext = null,
            oldLines = listOf("old"),
            newLines = listOf("new"),
            isEndOfFile = false
        )
        val result = PatchEngine.applyChunksToContent(original, listOf(chunk))
        assertTrue(result.contains("\r\n"))
        assertTrue(result.contains("new"))
    }

    @Test
    fun `applyChunksToContent applies multiple chunks sequentially`() {
        val original = "a\nb\nc\nd"
        val chunk1 = PatchEngine.UpdateFileChunk(null, listOf("a"), listOf("A"), false)
        val chunk2 = PatchEngine.UpdateFileChunk(null, listOf("c"), listOf("C"), false)
        val result = PatchEngine.applyChunksToContent(original, listOf(chunk1, chunk2))
        assertEquals("A\nb\nC\nd", result)
    }

    @Test
    fun `applyChunksToContent with empty oldLines appends newLines`() {
        val original = "line1\nline2"
        val chunk = PatchEngine.UpdateFileChunk(
            changeContext = null,
            oldLines = emptyList(),
            newLines = listOf("line3"),
            isEndOfFile = false
        )
        val result = PatchEngine.applyChunksToContent(original, listOf(chunk))
        assertEquals("line1\nline2\nline3", result)
    }

    @Test
    fun `applyChunksToContent does not modify when search block not found`() {
        val original = "line1\nline2"
        val chunk = PatchEngine.UpdateFileChunk(
            changeContext = null,
            oldLines = listOf("nonexistent"),
            newLines = listOf("replacement"),
            isEndOfFile = false
        )
        val result = PatchEngine.applyChunksToContent(original, listOf(chunk))
        assertEquals(original, result)
    }
}
