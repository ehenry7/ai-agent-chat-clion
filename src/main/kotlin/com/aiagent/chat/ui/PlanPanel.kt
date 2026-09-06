package com.aiagent.chat.ui

import com.aiagent.chat.agent.Plan
import com.aiagent.chat.agent.PlanStep
import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Collapsible panel for displaying the agent's current plan.
 * Mirrors the look-and-feel of TodoListPanel.
 * When collapsed: shows current step + next step.
 * When expanded: shows all steps with status indicators.
 */
class PlanPanel(
    private var plan: Plan? = null
) : JBPanel<PlanPanel>(BorderLayout()) {

    private var isExpanded = false
    private val titleLabel = JBLabel("")
    private val itemsContainer = JPanel(GridLayout(0, 1, 2, 2))
    private val headerPanel = JPanel(BorderLayout())

    init {
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border(), 1),
            JBUI.Borders.empty(6, 10)
        )
        background = ThemeUtils.TOOL_CONTENT_BG

        buildHeader()
        buildItems()

        add(headerPanel, BorderLayout.NORTH)
        add(itemsContainer, BorderLayout.CENTER)

        updateVisibility()
    }

    fun updatePlan(newPlan: Plan?) {
        plan = newPlan
        buildItems()
        updateVisibility()
        revalidate()
        repaint()
    }

    private fun buildHeader() {
        headerPanel.isOpaque = false
        headerPanel.cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)

        val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
        }

        val iconLabel = JBLabel(AllIcons.Actions.ChangeView)
        titleLabel.font = titleLabel.font.deriveFont(java.awt.Font.BOLD, 12f)
        leftPanel.add(iconLabel)
        leftPanel.add(titleLabel)

        val expandLabel = JBLabel(AllIcons.General.ChevronDown).apply {
            foreground = JBColor(0x666666, 0x999999)
        }

        headerPanel.add(leftPanel, BorderLayout.WEST)
        headerPanel.add(expandLabel, BorderLayout.EAST)

        headerPanel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                isExpanded = !isExpanded
                expandLabel.icon = if (isExpanded) AllIcons.General.ChevronUp else AllIcons.General.ChevronDown
                updateVisibility()
                revalidate()
                repaint()
            }
        })
    }

    private fun buildItems() {
        itemsContainer.removeAll()
        itemsContainer.isOpaque = false
        itemsContainer.layout = GridLayout(0, 1, 2, 2)

        val steps = plan?.steps ?: emptyList()
        val displaySteps = if (isExpanded) steps else getCollapsedSteps(steps)

        for (step in displaySteps) {
            val row = createStepRow(step)
            itemsContainer.add(row)
        }
    }

    private fun getCollapsedSteps(steps: List<PlanStep>): List<PlanStep> {
        if (steps.isEmpty()) return emptyList()
        val inProgress = steps.find { it.status == "in_progress" }
        val nextPending = steps.find { it.status == "pending" }
        val result = mutableListOf<PlanStep>()
        if (inProgress != null) result.add(inProgress)
        if (nextPending != null && nextPending != inProgress) result.add(nextPending)
        if (result.isEmpty()) result.add(steps.last())
        return result
    }

    private fun createStepRow(step: PlanStep): JComponent {
        val row = JPanel(BorderLayout())
        row.isOpaque = false
        row.border = JBUI.Borders.empty(2, 16, 2, 4)

        val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
        }

        val checkBox = JCheckBox().apply {
            isSelected = step.status == "completed"
            isEnabled = false
            isOpaque = false
        }

        val statusIcon = when (step.status) {
            "completed" -> AllIcons.Actions.Checked
            "in_progress" -> AllIcons.Actions.Refresh
            "skipped" -> AllIcons.Actions.Cancel
            else -> AllIcons.Actions.Forward
        }

        val iconLabel = JBLabel(statusIcon)

        val contentLabel = JBLabel(step.description).apply {
            font = font.deriveFont(
                if (step.status == "completed") java.awt.Font.ITALIC else java.awt.Font.PLAIN,
                12f
            )
            foreground = when (step.status) {
                "completed" -> JBColor(0x888888, 0x777777)
                "in_progress" -> JBColor(0x0066CC, 0x4A9EFF)
                "skipped" -> JBColor(0x999999, 0x666666)
                else -> JBColor(0x333333, 0xCCCCCC)
            }
        }

        leftPanel.add(checkBox)
        leftPanel.add(iconLabel)
        leftPanel.add(contentLabel)

        row.add(leftPanel, BorderLayout.WEST)

        return row
    }

    private fun updateVisibility() {
        buildItems()
        val steps = plan?.steps ?: emptyList()
        itemsContainer.isVisible = isExpanded || steps.isNotEmpty()

        val completed = steps.count { it.status == "completed" }
        val total = steps.size
        titleLabel.text = if (total == 0) {
            "Plan"
        } else {
            "Plan ($completed/$total)"
        }
    }
}
