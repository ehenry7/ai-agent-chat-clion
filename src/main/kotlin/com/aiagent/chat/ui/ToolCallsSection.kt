package com.aiagent.chat.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.JPanel

/**
 * Collapsible section that groups tool calls inside an assistant message bubble.
 * Shows a "Tool Calls (N)" header that expands/collapses to reveal individual ToolCallCards.
 *
 * Sliding-window behaviour while tools are running:
 * - Auto-expands but only shows the last [maxVisibleWhileRunning] tool call cards.
 * - Older cards are kept in the list but hidden, so vertical space stays bounded.
 * - The header title reads "Tool Calls (N) - showing last M" to hint there are more.
 * - Clicking the header switches to full-expand (all cards visible).
 * - Clicking again collapses to the header line.
 *
 * - When the next assistant message arrives: collapse() is called to minimize vertical space.
 * - User can click the header at any time to expand/collapse manually.
 */
class ToolCallsSection : JBPanel<ToolCallsSection>(BorderLayout()) {

    private val toolCallCards = mutableListOf<ToolCallCard>()
    private var isExpanded = false

    /** When true, only the last [maxVisibleWhileRunning] cards are visible (auto-expand during running). */
    private var isSlidingWindow = false

    /** Maximum number of tool call cards visible during sliding-window (running) mode. */
    private val maxVisibleWhileRunning = 4

    private val chevronLabel = JBLabel(AllIcons.General.ChevronRight).apply {
        foreground = JBColor(0x666666, 0x999999)
    }

    private val titleLabel = JBLabel("Tool Calls (0)").apply {
        font = font.deriveFont(java.awt.Font.BOLD, 12f)
        foreground = JBColor(0x555555, 0xAAAAAA)
    }

    private val headerPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
        isOpaque = false
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        border = JBUI.Borders.empty(2, 0)
        add(chevronLabel)
        add(titleLabel)
    }

    private val contentPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        isVisible = false
    }

    init {
        isOpaque = false
        isVisible = false // Hidden until first tool call is added
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0),
            JBUI.Borders.empty(2, 0, 0, 0)
        )

        headerPanel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                toggleExpanded()
            }
        })

        add(headerPanel, BorderLayout.NORTH)
        add(contentPanel, BorderLayout.CENTER)
    }

    /**
     * Add a tool call to the section.
     * @param autoExpand if true, expands the section in sliding-window mode so the user
     *                   can see the most recent tools as they execute. Only the last
     *                   [maxVisibleWhileRunning] cards are shown; older ones are hidden.
     *                   Set to false for session restore (historical tool calls stay collapsed).
     */
    fun addToolCall(name: String, output: String, status: ToolCallCard.ToolStatus, autoExpand: Boolean = true) {
        val card = ToolCallCard(name, output, status)
        card.alignmentX = JPanel.LEFT_ALIGNMENT
        toolCallCards.add(card)
        contentPanel.add(card)

        // Show the section (it starts hidden)
        isVisible = true

        if (autoExpand && !isExpanded) {
            // Auto-expand in sliding-window mode
            isSlidingWindow = true
            setExpanded(true)
        } else if (isExpanded && isSlidingWindow) {
            // Already in sliding-window mode — update which cards are visible
            updateSlidingWindowVisibility()
        }

        updateTitle()
        revalidate()
        repaint()
        parent?.revalidate()
        parent?.repaint()
    }

    /**
     * Collapse the section — hides all tool call cards, shows only the header line.
     * Should be called when all tools are done (e.g. when the next assistant message arrives).
     */
    fun collapse() {
        if (toolCallCards.isNotEmpty()) {
            isSlidingWindow = false
            setExpanded(false)
            revalidate()
            repaint()
            parent?.revalidate()
            parent?.repaint()
        }
    }

    val toolCallCount: Int get() = toolCallCards.size

    private fun setExpanded(expanded: Boolean) {
        isExpanded = expanded
        contentPanel.isVisible = expanded
        if (expanded) {
            if (isSlidingWindow) {
                updateSlidingWindowVisibility()
            } else {
                // Show all cards
                toolCallCards.forEach { it.isVisible = true }
            }
        }
        chevronLabel.icon = if (expanded) AllIcons.General.ChevronDown else AllIcons.General.ChevronRight
    }

    private fun toggleExpanded() {
        if (!isExpanded) {
            // Was collapsed → expand showing all
            isSlidingWindow = false
            setExpanded(true)
        } else if (isSlidingWindow) {
            // Was in sliding-window mode → switch to show all
            isSlidingWindow = false
            toolCallCards.forEach { it.isVisible = true }
            updateTitle()
        } else {
            // Was showing all → collapse
            setExpanded(false)
        }
        revalidate()
        repaint()
        parent?.revalidate()
        parent?.repaint()
    }

    /**
     * In sliding-window mode, show only the last [maxVisibleWhileRunning] cards
     * and hide the rest.
     */
    private fun updateSlidingWindowVisibility() {
        val total = toolCallCards.size
        val startIdx = maxOf(0, total - maxVisibleWhileRunning)
        for ((index, card) in toolCallCards.withIndex()) {
            card.isVisible = index >= startIdx
        }
    }

    private fun updateTitle() {
        val total = toolCallCards.size
        if (isExpanded && isSlidingWindow && total > maxVisibleWhileRunning) {
            titleLabel.text = "Tool Calls ($total) - showing last $maxVisibleWhileRunning"
        } else {
            titleLabel.text = "Tool Calls ($total)"
        }
    }
}
