package com.aiagent.chat.tools

import com.aiagent.chat.model.ToolCategory
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for ToolRegistry.
 * Verifies tool declarations, category lookups, phase filtering, and name-based queries.
 */
class ToolRegistryTest {

    // --- all() / definitions() ---

    @Test
    fun `all returns non-empty list`() {
        assertTrue(ToolRegistry.all().isNotEmpty())
    }

    @Test
    fun `definitions returns same count as all`() {
        assertEquals(ToolRegistry.all().size, ToolRegistry.definitions().size)
    }

    @Test
    fun `every declaration has a non-blank name`() {
        for (decl in ToolRegistry.all()) {
            assertTrue("Tool name should not be blank: $decl", decl.name.isNotBlank())
        }
    }

    @Test
    fun `every declaration has a valid category`() {
        for (decl in ToolRegistry.all()) {
            assertNotNull("Category should not be null for ${decl.name}", decl.category)
        }
    }

    @Test
    fun `all tool names are unique`() {
        val names = ToolRegistry.all().map { it.name }
        assertEquals("Tool names should be unique", names.size, names.toSet().size)
    }

    // --- getCategory() ---

    @Test
    fun `getCategory returns READ_ONLY for read_file`() {
        assertEquals(ToolCategory.READ_ONLY, ToolRegistry.getCategory("read_file"))
    }

    @Test
    fun `getCategory returns READ_ONLY for list_directory`() {
        assertEquals(ToolCategory.READ_ONLY, ToolRegistry.getCategory("list_directory"))
    }

    @Test
    fun `getCategory returns READ_ONLY for search_in_files`() {
        assertEquals(ToolCategory.READ_ONLY, ToolRegistry.getCategory("search_in_files"))
    }

    @Test
    fun `getCategory returns READ_ONLY for git_status`() {
        assertEquals(ToolCategory.READ_ONLY, ToolRegistry.getCategory("git_status"))
    }

    @Test
    fun `getCategory returns MUTATING for write_file`() {
        assertEquals(ToolCategory.MUTATING, ToolRegistry.getCategory("write_file"))
    }

    @Test
    fun `getCategory returns MUTATING for edit_file`() {
        assertEquals(ToolCategory.MUTATING, ToolRegistry.getCategory("edit_file"))
    }

    @Test
    fun `getCategory returns MUTATING for apply_patch`() {
        assertEquals(ToolCategory.MUTATING, ToolRegistry.getCategory("apply_patch"))
    }

    @Test
    fun `getCategory returns MUTATING for git_commit`() {
        assertEquals(ToolCategory.MUTATING, ToolRegistry.getCategory("git_commit"))
    }

    @Test
    fun `getCategory returns DANGEROUS for run_command`() {
        assertEquals(ToolCategory.DANGEROUS, ToolRegistry.getCategory("run_command"))
    }

    @Test
    fun `getCategory returns DANGEROUS for run_python`() {
        assertEquals(ToolCategory.DANGEROUS, ToolRegistry.getCategory("run_python"))
    }

    @Test
    fun `getCategory defaults to MUTATING for unknown tool`() {
        assertEquals(ToolCategory.MUTATING, ToolRegistry.getCategory("nonexistent_tool_xyz"))
    }

    // --- getDeclaration() ---

    @Test
    fun `getDeclaration returns declaration for known tool`() {
        val decl = ToolRegistry.getDeclaration("write_file")
        assertNotNull(decl)
        assertEquals("write_file", decl!!.name)
        assertEquals(ToolCategory.MUTATING, decl.category)
    }

    @Test
    fun `getDeclaration returns null for unknown tool`() {
        assertNull(ToolRegistry.getDeclaration("nonexistent_tool_xyz"))
    }

    // --- namesByCategory() ---

    @Test
    fun `namesByCategory READ_ONLY includes read_file and list_directory`() {
        val readOnly = ToolRegistry.namesByCategory(ToolCategory.READ_ONLY)
        assertTrue("read_file should be READ_ONLY", readOnly.contains("read_file"))
        assertTrue("list_directory should be READ_ONLY", readOnly.contains("list_directory"))
    }

    @Test
    fun `namesByCategory MUTATING includes write_file and edit_file`() {
        val mutating = ToolRegistry.namesByCategory(ToolCategory.MUTATING)
        assertTrue("write_file should be MUTATING", mutating.contains("write_file"))
        assertTrue("edit_file should be MUTATING", mutating.contains("edit_file"))
    }

    @Test
    fun `namesByCategory DANGEROUS includes run_command and run_python`() {
        val dangerous = ToolRegistry.namesByCategory(ToolCategory.DANGEROUS)
        assertTrue("run_command should be DANGEROUS", dangerous.contains("run_command"))
        assertTrue("run_python should be DANGEROUS", dangerous.contains("run_python"))
    }

    @Test
    fun `namesByCategory READ_ONLY does not include write_file`() {
        val readOnly = ToolRegistry.namesByCategory(ToolCategory.READ_ONLY)
        assertFalse("write_file should not be READ_ONLY", readOnly.contains("write_file"))
    }

    // --- mutatingToolNames() ---

    @Test
    fun `mutatingToolNames includes both MUTATING and DANGEROUS`() {
        val mutating = ToolRegistry.mutatingToolNames()
        assertTrue("write_file should be in mutatingToolNames", mutating.contains("write_file"))
        assertTrue("run_command should be in mutatingToolNames", mutating.contains("run_command"))
    }

    @Test
    fun `mutatingToolNames does not include READ_ONLY tools`() {
        val mutating = ToolRegistry.mutatingToolNames()
        assertFalse("read_file should not be in mutatingToolNames", mutating.contains("read_file"))
        assertFalse("list_directory should not be in mutatingToolNames", mutating.contains("list_directory"))
    }

    // --- definitionsForPhase() ---

    @Test
    fun `definitionsForPhase discovery returns only READ_ONLY tools`() {
        val discoveryTools = ToolRegistry.definitionsForPhase("discovery")
        val discoveryNames = discoveryTools.map { it.function.name }.toSet()
        val readOnlyNames = ToolRegistry.namesByCategory(ToolCategory.READ_ONLY)
        assertEquals(readOnlyNames, discoveryNames)
    }

    @Test
    fun `definitionsForPhase execution returns all tools`() {
        val executionTools = ToolRegistry.definitionsForPhase("execution")
        assertEquals(ToolRegistry.definitions().size, executionTools.size)
    }

    @Test
    fun `definitionsForPhase discovery excludes write_file`() {
        val discoveryTools = ToolRegistry.definitionsForPhase("discovery")
        val names = discoveryTools.map { it.function.name }
        assertFalse("write_file should not be available in discovery phase", names.contains("write_file"))
    }

    @Test
    fun `definitionsForPhase execution includes write_file`() {
        val executionTools = ToolRegistry.definitionsForPhase("execution")
        val names = executionTools.map { it.function.name }
        assertTrue("write_file should be available in execution phase", names.contains("write_file"))
    }

    // --- ToolDeclaration.name shortcut ---

    @Test
    fun `ToolDeclaration name matches function name`() {
        for (decl in ToolRegistry.all()) {
            assertEquals(decl.definition.function.name, decl.name)
        }
    }

    // --- Known tool count ---

    @Test
    fun `registry has at least 20 tools`() {
        assertTrue("Should have at least 20 tools, got ${ToolRegistry.all().size}",
            ToolRegistry.all().size >= 20)
    }

    @Test
    fun `registry has at least one tool in each category`() {
        assertNotNull(ToolRegistry.namesByCategory(ToolCategory.READ_ONLY))
        assertTrue(ToolRegistry.namesByCategory(ToolCategory.READ_ONLY).isNotEmpty())
        assertTrue(ToolRegistry.namesByCategory(ToolCategory.MUTATING).isNotEmpty())
        assertTrue(ToolRegistry.namesByCategory(ToolCategory.DANGEROUS).isNotEmpty())
    }
}
