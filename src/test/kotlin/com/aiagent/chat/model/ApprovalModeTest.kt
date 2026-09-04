package com.aiagent.chat.model

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for the ApprovalMode enum.
 * Verifies that each mode correctly determines which tool categories require approval.
 */
class ApprovalModeTest {

    // --- requiresApproval() matrix ---

    @Test
    fun `STRICT requires approval for READ_ONLY`() {
        assertTrue(ApprovalMode.STRICT.requiresApproval(ToolCategory.READ_ONLY))
    }

    @Test
    fun `STRICT requires approval for MUTATING`() {
        assertTrue(ApprovalMode.STRICT.requiresApproval(ToolCategory.MUTATING))
    }

    @Test
    fun `STRICT requires approval for DANGEROUS`() {
        assertTrue(ApprovalMode.STRICT.requiresApproval(ToolCategory.DANGEROUS))
    }

    @Test
    fun `BALANCED does not require approval for READ_ONLY`() {
        assertFalse(ApprovalMode.BALANCED.requiresApproval(ToolCategory.READ_ONLY))
    }

    @Test
    fun `BALANCED requires approval for MUTATING`() {
        assertTrue(ApprovalMode.BALANCED.requiresApproval(ToolCategory.MUTATING))
    }

    @Test
    fun `BALANCED requires approval for DANGEROUS`() {
        assertTrue(ApprovalMode.BALANCED.requiresApproval(ToolCategory.DANGEROUS))
    }

    @Test
    fun `PERMISSIVE does not require approval for READ_ONLY`() {
        assertFalse(ApprovalMode.PERMISSIVE.requiresApproval(ToolCategory.READ_ONLY))
    }

    @Test
    fun `PERMISSIVE does not require approval for MUTATING`() {
        assertFalse(ApprovalMode.PERMISSIVE.requiresApproval(ToolCategory.MUTATING))
    }

    @Test
    fun `PERMISSIVE requires approval for DANGEROUS`() {
        assertTrue(ApprovalMode.PERMISSIVE.requiresApproval(ToolCategory.DANGEROUS))
    }

    @Test
    fun `AUTOPILOT does not require approval for READ_ONLY`() {
        assertFalse(ApprovalMode.AUTOPILOT.requiresApproval(ToolCategory.READ_ONLY))
    }

    @Test
    fun `AUTOPILOT does not require approval for MUTATING`() {
        assertFalse(ApprovalMode.AUTOPILOT.requiresApproval(ToolCategory.MUTATING))
    }

    @Test
    fun `AUTOPILOT does not require approval for DANGEROUS`() {
        assertFalse(ApprovalMode.AUTOPILOT.requiresApproval(ToolCategory.DANGEROUS))
    }

    // --- displayName and description ---

    @Test
    fun `all modes have non-blank display names`() {
        for (mode in ApprovalMode.entries) {
            assertTrue("Display name for $mode should not be blank", mode.displayName.isNotBlank())
        }
    }

    @Test
    fun `all modes have non-blank descriptions`() {
        for (mode in ApprovalMode.entries) {
            assertTrue("Description for $mode should not be blank", mode.description.isNotBlank())
        }
    }

    @Test
    fun `display names are unique`() {
        val names = ApprovalMode.entries.map { it.displayName }
        assertEquals("Display names should be unique", names.size, names.toSet().size)
    }

    @Test
    fun `STRICT display name is Strict`() {
        assertEquals("Strict", ApprovalMode.STRICT.displayName)
    }

    @Test
    fun `BALANCED display name is Balanced`() {
        assertEquals("Balanced", ApprovalMode.BALANCED.displayName)
    }

    @Test
    fun `PERMISSIVE display name is Permissive`() {
        assertEquals("Permissive", ApprovalMode.PERMISSIVE.displayName)
    }

    @Test
    fun `AUTOPILOT display name is Autopilot`() {
        assertEquals("Autopilot", ApprovalMode.AUTOPILOT.displayName)
    }

    // --- Enum basics ---

    @Test
    fun `ApprovalMode has exactly 4 entries`() {
        assertEquals(4, ApprovalMode.entries.size)
    }

    @Test
    fun `valueOf parses valid mode names`() {
        assertEquals(ApprovalMode.STRICT, ApprovalMode.valueOf("STRICT"))
        assertEquals(ApprovalMode.BALANCED, ApprovalMode.valueOf("BALANCED"))
        assertEquals(ApprovalMode.PERMISSIVE, ApprovalMode.valueOf("PERMISSIVE"))
        assertEquals(ApprovalMode.AUTOPILOT, ApprovalMode.valueOf("AUTOPILOT"))
    }
}
