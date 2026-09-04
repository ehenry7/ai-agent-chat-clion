package com.aiagent.chat.tools

import com.aiagent.chat.model.ToolCategory
import org.junit.Assert.*
import org.junit.Test

class ToolRegistryTest {

    @Test
    fun testAllToolsRegistered() {
        val all = ToolRegistry.all()
        // 22 original + 12 new = 34 tools
        assertEquals(34, all.size)
    }

    @Test
    fun testNewToolNamesPresent() {
        val names = ToolRegistry.all().map { it.name }.toSet()
        assertTrue("tree" in names)
        assertTrue("rm" in names)
        assertTrue("mv" in names)
        assertTrue("update_textdoc_by_lines" in names)
        assertTrue("undo_textdoc" in names)
        assertTrue("ask_questions" in names)
        assertTrue("sleep" in names)
        assertTrue("compress_chat_probe" in names)
        assertTrue("compress_chat_apply" in names)
        assertTrue("set_plan" in names)
        assertTrue("get_plan" in names)
        assertTrue("update_plan" in names)
    }

    @Test
    fun testNewToolCategories() {
        assertEquals(ToolCategory.READ_ONLY, ToolRegistry.getCategory("tree"))
        assertEquals(ToolCategory.READ_ONLY, ToolRegistry.getCategory("ask_questions"))
        assertEquals(ToolCategory.READ_ONLY, ToolRegistry.getCategory("sleep"))
        assertEquals(ToolCategory.READ_ONLY, ToolRegistry.getCategory("compress_chat_probe"))
        assertEquals(ToolCategory.READ_ONLY, ToolRegistry.getCategory("compress_chat_apply"))
        assertEquals(ToolCategory.READ_ONLY, ToolRegistry.getCategory("set_plan"))
        assertEquals(ToolCategory.READ_ONLY, ToolRegistry.getCategory("get_plan"))
        assertEquals(ToolCategory.READ_ONLY, ToolRegistry.getCategory("update_plan"))
        assertEquals(ToolCategory.MUTATING, ToolRegistry.getCategory("update_textdoc_by_lines"))
        assertEquals(ToolCategory.MUTATING, ToolRegistry.getCategory("undo_textdoc"))
        assertEquals(ToolCategory.MUTATING, ToolRegistry.getCategory("mv"))
        assertEquals(ToolCategory.DANGEROUS, ToolRegistry.getCategory("rm"))
    }

    @Test
    fun testDefinitionsForPhase() {
        val discoveryTools = ToolRegistry.definitionsForPhase("discovery")
        val executionTools = ToolRegistry.definitionsForPhase("execution")

        // Discovery should only have READ_ONLY tools
        val discoveryNames = discoveryTools.map { it.function.name }.toSet()
        assertFalse("rm" in discoveryNames)
        assertFalse("mv" in discoveryNames)
        assertFalse("write_file" in discoveryNames)

        // Execution should have all tools
        assertEquals(34, executionTools.size)

        // New read-only tools should be in discovery
        assertTrue("tree" in discoveryNames)
        assertTrue("ask_questions" in discoveryNames)
        assertTrue("sleep" in discoveryNames)
        assertTrue("compress_chat_probe" in discoveryNames)
        assertTrue("set_plan" in discoveryNames)
        assertTrue("get_plan" in discoveryNames)
        assertTrue("update_plan" in discoveryNames)
    }

    @Test
    fun testMutatingToolNames() {
        val mutating = ToolRegistry.mutatingToolNames()
        assertTrue("write_file" in mutating)
        assertTrue("edit_file" in mutating)
        assertTrue("update_textdoc_by_lines" in mutating)
        assertTrue("undo_textdoc" in mutating)
        assertTrue("mv" in mutating)
        assertTrue("run_command" in mutating)
        assertTrue("rm" in mutating)
        // Read-only tools should NOT be in mutating set
        assertFalse("tree" in mutating)
        assertFalse("sleep" in mutating)
        assertFalse("ask_questions" in mutating)
    }

    @Test
    fun testNamesByCategory() {
        val readOnly = ToolRegistry.namesByCategory(ToolCategory.READ_ONLY)
        val mutating = ToolRegistry.namesByCategory(ToolCategory.MUTATING)
        val dangerous = ToolRegistry.namesByCategory(ToolCategory.DANGEROUS)

        // READ_ONLY: 14 original + 8 new = 22
        assertEquals(22, readOnly.size)
        // MUTATING: 6 original + 3 new = 9
        assertEquals(9, mutating.size)
        // DANGEROUS: 2 original + 1 new = 3
        assertEquals(3, dangerous.size)
    }

    @Test
    fun testGetDeclaration() {
        val decl = ToolRegistry.getDeclaration("tree")
        assertNotNull(decl)
        assertEquals(ToolCategory.READ_ONLY, decl!!.category)
        assertEquals("tree", decl.name)
    }

    @Test
    fun testGetCategoryUnknownTool() {
        // Unknown tools default to MUTATING (safe default)
        assertEquals(ToolCategory.MUTATING, ToolRegistry.getCategory("unknown_tool_xyz"))
    }
}
