package com.aiagent.chat.agent

import org.junit.Assert.*
import org.junit.Test

class PlanManagerTest {

    @Test
    fun testParsePlanMarkdown() {
        val md = """
            ## Plan: Implement Feature X
            
            - [ ] Read the codebase
            - [ ] Write the implementation
            - [x] Set up the project
            - [-] Write tests
        """.trimIndent()

        val plan = PlanManager.parsePlanMarkdown(md)
        assertEquals("Implement Feature X", plan.title)
        assertEquals(4, plan.steps.size)
        assertEquals("Read the codebase", plan.steps[0].description)
        assertEquals("pending", plan.steps[0].status)
        assertEquals("completed", plan.steps[2].status)
        assertEquals("in_progress", plan.steps[3].status)
    }

    @Test
    fun testParsePlanMarkdownNoTitle() {
        val md = """
            - [ ] Step 1
            - [ ] Step 2
        """.trimIndent()

        val plan = PlanManager.parsePlanMarkdown(md)
        assertEquals("Untitled Plan", plan.title)
        assertEquals(2, plan.steps.size)
    }

    @Test
    fun testPlanToMarkdown() {
        val plan = Plan(
            title = "Test Plan",
            steps = listOf(
                PlanStep("step_1", "First step", "completed"),
                PlanStep("step_2", "Second step", "in_progress"),
                PlanStep("step_3", "Third step", "pending")
            )
        )
        val md = plan.toMarkdown()
        assertTrue(md.contains("## Plan: Test Plan"))
        assertTrue(md.contains("[x] step_1: First step"))
        assertTrue(md.contains("[-] step_2: Second step"))
        assertTrue(md.contains("[ ] step_3: Third step"))
    }

    @Test
    fun testPlanToSystemPromptSection() {
        val plan = Plan(
            title = "Test Plan",
            steps = listOf(
                PlanStep("step_1", "First step", "completed"),
                PlanStep("step_2", "Second step", "pending")
            )
        )
        val section = plan.toSystemPromptSection()
        assertTrue(section.contains("<current_plan>"))
        assertTrue(section.contains("</current_plan>"))
        assertTrue(section.contains("Progress: 1/2 steps completed"))
    }

    @Test
    fun testEmptyPlanToSystemPromptSection() {
        val plan = Plan("Empty", emptyList())
        assertEquals("", plan.toSystemPromptSection())
    }

    @Test
    fun testPlanManagerSetAndGet() {
        val pm = PlanManager()
        assertNull(pm.getPlan())

        pm.setPlanFromMarkdown("## Plan: My Plan\n- [ ] Step 1\n- [x] Step 2")
        val plan = pm.getPlan()
        assertNotNull(plan)
        assertEquals("My Plan", plan!!.title)
        assertEquals(2, plan.steps.size)
    }

    @Test
    fun testPlanManagerUpdateStep() {
        val pm = PlanManager()
        pm.setPlanFromMarkdown("## Plan: My Plan\n- [ ] Step 1\n- [ ] Step 2")

        assertTrue(pm.updateStep("step_1", "completed"))
        val plan = pm.getPlan()
        assertEquals("completed", plan!!.steps[0].status)

        assertFalse(pm.updateStep("nonexistent", "completed"))
    }

    @Test
    fun testPlanManagerClear() {
        val pm = PlanManager()
        pm.setPlanFromMarkdown("## Plan: My Plan\n- [ ] Step 1")
        assertNotNull(pm.getPlan())

        pm.clearPlan()
        assertNull(pm.getPlan())
    }

    @Test
    fun testPlanManagerToSystemPromptSection() {
        val pm = PlanManager()
        assertEquals("", pm.toSystemPromptSection())

        pm.setPlanFromMarkdown("## Plan: My Plan\n- [ ] Step 1\n- [x] Step 2")
        val section = pm.toSystemPromptSection()
        assertTrue(section.contains("<current_plan>"))
        assertTrue(section.contains("Progress: 1/2"))
    }

    @Test
    fun testSkippedStatus() {
        val md = "- [~] Skipped step"
        val plan = PlanManager.parsePlanMarkdown(md)
        assertEquals(1, plan.steps.size)
        assertEquals("in_progress", plan.steps[0].status)
    }

    @Test
    fun testUpdateStepByNumericIndex() {
        val pm = PlanManager()
        pm.setPlanFromMarkdown("## Plan: My Plan\n- [ ] Step 1\n- [ ] Step 2")
        // LLM often passes just "1" instead of "step_1"
        assertTrue(pm.updateStep("1", "in_progress"))
        assertEquals("in_progress", pm.getPlan()!!.steps[0].status)
        // Also test "2"
        assertTrue(pm.updateStep("2", "completed"))
        assertEquals("completed", pm.getPlan()!!.steps[1].status)
    }

    @Test
    fun testUpdateStepByExactId() {
        val pm = PlanManager()
        pm.setPlanFromMarkdown("## Plan: My Plan\n- [ ] Step 1\n- [ ] Step 2")
        assertTrue(pm.updateStep("step_1", "completed"))
        assertEquals("completed", pm.getPlan()!!.steps[0].status)
    }

    @Test
    fun testRoundTripWithStepIds() {
        val pm = PlanManager()
        pm.setPlanFromMarkdown("## Plan: Test\n- [ ] Do A\n- [x] Done B")
        val md = pm.getPlan()!!.toMarkdown()
        // Should contain step IDs
        assertTrue(md.contains("step_1: Do A"))
        assertTrue(md.contains("step_2: Done B"))
        // Re-parse should preserve IDs and descriptions
        val reparsed = PlanManager.parsePlanMarkdown(md)
        assertEquals("Test", reparsed.title)
        assertEquals(2, reparsed.steps.size)
        assertEquals("Do A", reparsed.steps[0].description)
        assertEquals("Done B", reparsed.steps[1].description)
        assertEquals("step_1", reparsed.steps[0].id)
        assertEquals("step_2", reparsed.steps[1].id)
        assertEquals("pending", reparsed.steps[0].status)
        assertEquals("completed", reparsed.steps[1].status)
    }

    @Test
    fun testUpdateStepNonexistent() {
        val pm = PlanManager()
        pm.setPlanFromMarkdown("## Plan: My Plan\n- [ ] Step 1")
        assertFalse(pm.updateStep("99", "completed"))
        assertFalse(pm.updateStep("abc", "completed"))
    }
}
