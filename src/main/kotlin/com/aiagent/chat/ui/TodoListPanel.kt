package com.aiagent.chat.ui

import com.aiagent.chat.model.TodoItem
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
 * Collapsible panel for displaying the agent's todo list.
 * When collapsed: shows current task + next task.
 * When expanded: shows all tasks with status indicators.
 * Inspired by ProxyAI's TodoListPanel.
 */
class TodoListPanel(
    private var items: List<TodoItem> = emptyList()
) : JBPanel<TodoListPanel>(BorderLayout()) {

    private var isExpanded = false
    private val titleLabel = JBLabel("")
    private val itemsContainer = JPanel(java.awt.GridLayout(0, 1, 2, 2))
    private val headerPanel = JPanel(BorderLayout())

    init {
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border(), 1),
            JBUI.Borders.empty(6, 10)
        )
        background = JBColor(0xF5F5F5, 0x1E1E1E)

        buildHeader()
        buildItems()

        add(headerPanel, BorderLayout.NORTH)
        add(itemsContainer, BorderLayout.CENTER)

        updateVisibility()
    }

    fun updateItems(newItems: List<TodoItem>) {
        items = newItems
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

        val iconLabel = JBLabel(AllIcons.Actions.Checked)
        titleLabel.font = titleLabel.font.deriveFont(java.awt.Font.BOLD, 12f)
        leftPanel.add(iconLabel)
        leftPanel.add(titleLabel)

        val expandLabel = JBLabel("[+]").apply {
            font = font.deriveFont(11f)
            foreground = JBColor(0x666666, 0x999999)
        }

        headerPanel.add(leftPanel, BorderLayout.WEST)
        headerPanel.add(expandLabel, BorderLayout.EAST)

        headerPanel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                isExpanded = !isExpanded
                expandLabel.text = if (isExpanded) "[-]" else "[+]"
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

        val displayItems = if (isExpanded) items else getCollapsedItems()

        for (item in displayItems) {
            val row = createItemRow(item)
            itemsContainer.add(row)
        }
    }

    private fun getCollapsedItems(): List<TodoItem> {
        if (items.isEmpty()) return emptyList()
        val inProgress = items.find { it.status == "in_progress" }
        val nextPending = items.find { it.status == "pending" }
        val result = mutableListOf<TodoItem>()
        if (inProgress != null) result.add(inProgress)
        if (nextPending != null && nextPending != inProgress) result.add(nextPending)
        if (result.isEmpty()) result.add(items.last())
        return result
    }

    private fun createItemRow(item: TodoItem): JComponent {
        val row = JPanel(BorderLayout())
        row.isOpaque = false
        row.border = JBUI.Borders.empty(2, 16, 2, 4)

        val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
        }

        // Status checkbox/indicator
        val checkBox = JCheckBox().apply {
            isSelected = item.status == "completed"
            isEnabled = false
            isOpaque = false
        }

        // Status icon for in_progress
        val statusIcon = when (item.status) {
            "completed" -> AllIcons.Actions.Checked
            "in_progress" -> AllIcons.Actions.Refresh
            else -> AllIcons.Actions.Forward
        }

        val iconLabel = JBLabel(statusIcon)

        // Content label
        val contentLabel = JBLabel(item.content).apply {
            font = font.deriveFont(
                if (item.status == "completed") java.awt.Font.ITALIC else java.awt.Font.PLAIN,
                12f
            )
            foreground = when (item.status) {
                "completed" -> JBColor(0x888888, 0x777777)
                "in_progress" -> JBColor(0x0066CC, 0x4A9EFF)
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
        itemsContainer.isVisible = isExpanded || items.isNotEmpty()

        val completed = items.count { it.status == "completed" }
        val total = items.size
        titleLabel.text = if (total == 0) {
            "Todo List"
        } else {
            "Todo List ($completed/$total)"
        }
    }
}
