package com.aiagent.chat.tools

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TreeBuilderTest {

    private fun createTempProject(): File {
        val tempDir = Files.createTempDirectory("treebuilder_test").toFile()
        tempDir.deleteOnExit()

        // Create structure:
        // tempDir/
        //   src/
        //     main.kt
        //     utils.kt
        //   build/        (should be excluded)
        //     output.class
        //   .git/          (should be excluded)
        //     config
        //   README.md
        //   .hidden        (should be excluded if includeHidden=false)

        val srcDir = File(tempDir, "src").apply { mkdirs() }
        File(srcDir, "main.kt").writeText("fun main() {}\n")
        File(srcDir, "utils.kt").writeText("fun util() {}\nfun util2() {}\n")

        val buildDir = File(tempDir, "build").apply { mkdirs() }
        File(buildDir, "output.class").writeText("binary")

        val gitDir = File(tempDir, ".git").apply { mkdirs() }
        File(gitDir, "config").writeText("git config")

        File(tempDir, "README.md").writeText("# Project\n")
        File(tempDir, ".hidden").writeText("secret")

        return tempDir
    }

    @Test
    fun testBasicTree() {
        val root = createTempProject()
        val tree = TreeBuilder.buildTree(root, maxDepth = 3, includeHidden = false)

        assertNotNull(tree)
        assertTrue(tree.contains("src/"))
        assertTrue(tree.contains("main.kt"))
        assertTrue(tree.contains("utils.kt"))
        assertTrue(tree.contains("README.md"))
    }

    @Test
    fun testExcludesBuildAndGit() {
        val root = createTempProject()
        val tree = TreeBuilder.buildTree(root, maxDepth = 3, includeHidden = false)

        assertFalse("build/ should be excluded", tree.contains("build/"))
        assertFalse(".git/ should be excluded", tree.contains(".git/"))
        assertFalse("output.class should be excluded", tree.contains("output.class"))
    }

    @Test
    fun testExcludesHiddenByDefault() {
        val root = createTempProject()
        val tree = TreeBuilder.buildTree(root, maxDepth = 3, includeHidden = false)

        assertFalse(".hidden should be excluded", tree.contains(".hidden"))
    }

    @Test
    fun testIncludesHiddenWhenRequested() {
        val root = createTempProject()
        val tree = TreeBuilder.buildTree(root, maxDepth = 3, includeHidden = true)

        assertTrue(".hidden should be included", tree.contains(".hidden"))
    }

    @Test
    fun testLineCountForTextFiles() {
        val root = createTempProject()
        val tree = TreeBuilder.buildTree(root, maxDepth = 3, includeHidden = false)

        // utils.kt has 2 lines
        assertTrue("utils.kt should show line count", tree.contains("utils.kt"))
        assertTrue("utils.kt should show 2 lines", tree.contains("2 lines"))
    }

    @Test
    fun testMaxDepth() {
        val root = createTempProject()
        val tree = TreeBuilder.buildTree(root, maxDepth = 1, includeHidden = false)

        // With maxDepth=1, we should see src/ but not its contents
        assertTrue(tree.contains("src/"))
        assertFalse("main.kt should not appear at depth 1", tree.contains("main.kt"))
    }

    @Test
    fun testNonExistentDirectory() {
        val tree = TreeBuilder.buildTree(File("/nonexistent/path"), maxDepth = 3)
        assertTrue(tree.startsWith("Error:"))
    }

    @Test
    fun testFileNotDirectory() {
        val tempFile = Files.createTempFile("treebuilder_test", ".txt").toFile()
        tempFile.writeText("hello")
        tempFile.deleteOnExit()
        val tree = TreeBuilder.buildTree(tempFile, maxDepth = 3)
        assertTrue(tree.startsWith("Error:"))
    }

    @Test
    fun testMaxEntriesTruncation() {
        val root = Files.createTempDirectory("treebuilder_max").toFile()
        root.deleteOnExit()
        for (i in 1..20) {
            File(root, "file$i.txt").writeText("content $i")
        }
        val tree = TreeBuilder.buildTree(root, maxDepth = 3, maxEntries = 5)
        assertTrue(tree.contains("truncated"))
    }
}
