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
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.ScrollPaneConstants

/**
 * Collapsible card for displaying tool call results.
 * Shows tool icon, name, status, and expandable output content.
 * Inspired by ProxyAI's ToolCallCard / ToolCallView.
 */
class ToolCallCard(
    private val toolName: String,
    private val outputText: String,
    private val status: ToolStatus = ToolStatus.COMPLETED
) : JBPanel<ToolCallCard>(BorderLayout()) {

    enum class ToolStatus {
        RUNNING, COMPLETED, ERROR
    }

    private var isExpanded = false
    private val contentArea = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12)
        background = ThemeUtils.TOOL_CONTENT_BG
        border = JBUI.Borders.empty(6, 10)
        text = outputText
    }

    private val titleLabel = JBLabel("").apply {
        font = font.deriveFont(java.awt.Font.BOLD, 12f)
    }

    private val statusLabel = JBLabel("")

    init {
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(getBorderColor(), 1),
            JBUI.Borders.empty(6, 10)
        )
        background = getCardBackground()

        buildHeader()
        buildContent()

        contentArea.isVisible = false
        alignmentX = JComponent.LEFT_ALIGNMENT
    }

    private fun buildHeader() {
        val header = JPanel(BorderLayout())
        header.isOpaque = false

        val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
        }

        val iconLabel = JBLabel(getToolIcon())
        titleLabel.text = getDisplayTitle()
        statusLabel.text = getStatusText()
        statusLabel.foreground = getStatusColor()
        statusLabel.font = statusLabel.font.deriveFont(java.awt.Font.ITALIC, 11f)

        leftPanel.add(iconLabel)
        leftPanel.add(titleLabel)
        leftPanel.add(statusLabel)

        header.add(leftPanel, BorderLayout.WEST)

        val expandLabel = JBLabel(AllIcons.General.ChevronDown).apply {
            foreground = ThemeUtils.SECONDARY_TEXT
        }
        header.add(expandLabel, BorderLayout.EAST)

        header.cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        header.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                isExpanded = !isExpanded
                contentArea.isVisible = isExpanded
                expandLabel.icon = if (isExpanded) AllIcons.General.ChevronUp else AllIcons.General.ChevronDown
                revalidate()
                repaint()
                parent?.revalidate()
                parent?.repaint()
            }
        })

        add(header, BorderLayout.NORTH)
    }

    private fun buildContent() {
        add(contentArea, BorderLayout.CENTER)
    }

    private fun getToolIcon(): Icon {
        return when {
            toolName.contains("read") || toolName.contains("file") -> AllIcons.FileTypes.Any_type
            toolName.contains("write") || toolName.contains("edit") || toolName.contains("patch") || toolName.contains("diff") -> AllIcons.Actions.Edit
            toolName.contains("search") || toolName.contains("grep") || toolName.contains("glob") -> AllIcons.Actions.Search
            toolName.contains("run") || toolName.contains("command") || toolName.contains("bash") -> AllIcons.Actions.Run_anything
            toolName.contains("git") -> AllIcons.Actions.Refresh
            toolName.contains("todo") -> AllIcons.Actions.Checked
            toolName.contains("delete") -> AllIcons.Actions.Cancel
            toolName.contains("directory") || toolName.contains("create") -> AllIcons.Nodes.Folder
            toolName.contains("phase") -> AllIcons.Actions.ChangeView
            else -> AllIcons.General.Settings
        }
    }

    private fun getDisplayTitle(): String {
        // Extract file path from output if present
        val pathMatch = Regex("(?:Wrote|Read|Edited|Deleted|Created).*?(\\S+\\.(?:kt|java|py|js|ts|json|xml|md|txt|gradle|kts))").find(outputText)
        val fileHint = pathMatch?.groupValues?.get(1)
        return if (fileHint != null) {
            "$toolName: $fileHint"
        } else {
            toolName
        }
    }

    private fun getStatusText(): String {
        return when (status) {
            ToolStatus.RUNNING -> " (running...)"
            ToolStatus.COMPLETED -> " (done)"
            ToolStatus.ERROR -> " (error)"
        }
    }

    private fun getStatusColor(): JBColor {
        return when (status) {
            ToolStatus.RUNNING -> ThemeUtils.ACCENT
            ToolStatus.COMPLETED -> ThemeUtils.SUCCESS
            ToolStatus.ERROR -> ThemeUtils.ERROR_BORDER
        }
    }

    private fun getBorderColor(): java.awt.Color {
        return when (status) {
            ToolStatus.RUNNING -> ThemeUtils.ACCENT
            ToolStatus.COMPLETED -> ThemeUtils.SUBTLE_BORDER
            ToolStatus.ERROR -> ThemeUtils.ERROR_BORDER
        }
    }

    private fun getCardBackground(): JBColor {
        return ThemeUtils.TOOL_CARD_BG
    }
}
