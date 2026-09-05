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
 * - While tools are running: auto-expands so the user can see each tool as it completes.
 * - When the next assistant message arrives: collapse() is called to minimize vertical space.
 * - User can click the header at any time to expand/collapse manually.
 */
class ToolCallsSection : JBPanel<ToolCallsSection>(BorderLayout()) {

    private val toolCallCards = mutableListOf<ToolCallCard>()
    private var isExpanded = false

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
     * @param autoExpand if true, expands the section so the user can see tools as they execute.
     *                   Set to false for session restore (historical tool calls stay collapsed).
     */
    fun addToolCall(name: String, output: String, status: ToolCallCard.ToolStatus, autoExpand: Boolean = true) {
        val card = ToolCallCard(name, output, status)
        card.alignmentX = JPanel.LEFT_ALIGNMENT
        toolCallCards.add(card)
        contentPanel.add(card)

        // Show the section (it starts hidden)
        isVisible = true

        // Auto-expand while tools are running
        if (autoExpand && !isExpanded) {
            setExpanded(true)
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
        chevronLabel.icon = if (expanded) AllIcons.General.ChevronDown else AllIcons.General.ChevronRight
    }

    private fun toggleExpanded() {
        setExpanded(!isExpanded)
        revalidate()
        repaint()
        parent?.revalidate()
        parent?.repaint()
    }

    private fun updateTitle() {
        titleLabel.text = "Tool Calls (${toolCallCards.size})"
    }
}
