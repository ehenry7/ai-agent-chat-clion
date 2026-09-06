package com.aiagent.chat.agent

/**
 * Structured plan management for the agent.
 *
 * Inspired by refact-main's plan management tools (set_plan, get_plan, update_plan).
 * The plan is a markdown checklist that gets injected into the system prompt each turn,
 * keeping the agent focused on its current task and progress.
 *
 * The plan is stored as a structured object but can be parsed from and serialized to
 * markdown for easy LLM interaction.
 */
data class PlanStep(
    val id: String,
    val description: String,
    val status: String // "pending", "in_progress", "completed", "skipped"
)

data class Plan(
    val title: String,
    val steps: List<PlanStep>
) {
    fun toMarkdown(): String {
        val lines = mutableListOf("## Plan: $title", "")
        for (step in steps) {
            val box = when (step.status) {
                "completed" -> "[x]"
                "in_progress" -> "[-]"
                "skipped" -> "[~]"
                else -> "[ ]"
            }
            lines.add("$box ${step.description}")
        }
        return lines.joinToString("\n")
    }

    /**
     * Render the plan as a section suitable for injection into the system prompt.
     * Returns empty string if there are no steps.
     */
    fun toSystemPromptSection(): String {
        if (steps.isEmpty()) return ""
        val progress = steps.count { it.status == "completed" }
        val total = steps.size
        return "\n<current_plan>\n${toMarkdown()}\nProgress: $progress/$total steps completed.\n</current_plan>"
    }
}

class PlanManager {
    private var currentPlan: Plan? = null

    fun setPlan(plan: Plan) {
        currentPlan = plan
    }

    fun setPlanFromMarkdown(md: String) {
        currentPlan = parsePlanMarkdown(md)
    }

    fun getPlan(): Plan? = currentPlan

    fun updateStep(stepId: String, status: String): Boolean {
        val plan = currentPlan ?: return false
        // Check if the step exists
        if (plan.steps.none { it.id == stepId }) return false
        val updatedSteps = plan.steps.map {
            if (it.id == stepId) it.copy(status = status) else it
        }
        currentPlan = plan.copy(steps = updatedSteps)
        return true
    }

    fun clearPlan() {
        currentPlan = null
    }

    /**
     * Returns true if there is an active plan with at least one step
     * that is not "completed" or "skipped".
     */
    fun hasIncompleteSteps(): Boolean {
        val plan = currentPlan ?: return false
        return plan.steps.any { it.status != "completed" && it.status != "skipped" }
    }

    /**
     * Returns a summary of incomplete steps for nudge messages.
     */
    fun incompleteStepsSummary(): String {
        val plan = currentPlan ?: return ""
        val incomplete = plan.steps.filter { it.status != "completed" && it.status != "skipped" }
        if (incomplete.isEmpty()) return ""
        return incomplete.joinToString("\n") { "  - [${it.status}] ${it.id}: ${it.description}" }
    }

    fun toSystemPromptSection(): String = currentPlan?.toSystemPromptSection() ?: ""

    companion object {
        /**
         * Parse a markdown checklist into a Plan.
         * Supports lines like:
         *   ## Plan: Title
         *   - [ ] Pending step
         *   - [x] Completed step
         *   - [-] In-progress step
         *   - [~] Skipped step
         */
        fun parsePlanMarkdown(md: String): Plan {
            val lines = md.split(Regex("\\r?\\n")).map { it.trim() }.filter { it.isNotEmpty() }
            var title = "Untitled Plan"
            val steps = mutableListOf<PlanStep>()

            for (line in lines) {
                // Extract title from heading
                if (line.startsWith("##")) {
                    title = line.removePrefix("##").removePrefix("#").trim()
                    if (title.startsWith("Plan:")) {
                        title = title.removePrefix("Plan:").trim()
                    }
                    continue
                }
                // Parse checklist items
                val match = Regex("^(?:-\\s*)?\\[\\s*([ xX\\-~])\\s*\\]\\s+(.+)$").find(line)
                if (match != null) {
                    val mark = match.groupValues[1]
                    val desc = match.groupValues[2]
                    val status = when (mark) {
                        "x", "X" -> "completed"
                        "-", "~" -> "in_progress"
                        else -> "pending"
                    }
                    val id = "step_${steps.size + 1}"
                    steps.add(PlanStep(id, desc, status))
                }
            }
            return Plan(title, steps)
        }
    }
}
