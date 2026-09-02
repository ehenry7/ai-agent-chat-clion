package com.aiagent.chat.tools

import org.junit.Assert.*
import org.junit.Test

class DiffEngineTest {

    @Test
    fun testLevenshteinDistance() {
        val dist = DiffEngine.levenshteinDistance("kitten", "sitting")
        assertEquals(3, dist)
    }

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
}
