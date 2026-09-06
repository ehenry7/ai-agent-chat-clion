package com.aiagent.chat.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.*

/**
 * Inline approval panel for tool execution requests.
 * Replaces the blocking Messages.showYesNoDialog with a non-blocking inline UI.
 *
 * Phase 7: Tool Approval Panels.
 * Inspired by ProxyAI's BashApprovalPanel, WriteApprovalPanel, EditApprovalPanel.
 *
 * Shows the tool name, a preview of what will happen, and
 * Accept / Always accept / Reject buttons.
 */
class ToolApprovalPanel(
    private val toolName: String,
    private val toolArgs: String,
    private val onApprove: (autoApproveSession: Boolean) -> Unit,
    private val onReject: () -> Unit
) : JBPanel<ToolApprovalPanel>(BorderLayout()) {

    enum class ApprovalType {
        BASH, WRITE, EDIT, GENERIC
    }

    init {
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(ThemeUtils.WARNING, 1),
            JBUI.Borders.empty(8, 12)
        )
        background = JBColor.namedColor("Notification.warningBackground", JBColor(0xFFF8E1, 0x3A3320))

        buildHeader()
        buildPreview()
        buildActions()
    }

    private fun buildHeader() {
        val header = JPanel(BorderLayout())
        header.isOpaque = false

        val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
        }

        val icon = JBLabel(getToolIcon())
        val title = JBLabel(getTitle()).apply {
            font = font.deriveFont(java.awt.Font.BOLD, 12f)
            foreground = ThemeUtils.WARNING
        }

        leftPanel.add(icon)
        leftPanel.add(title)
        header.add(leftPanel, BorderLayout.WEST)

        add(header, BorderLayout.NORTH)
    }

    private fun buildPreview() {
        val preview = JBTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            text = formatPreview()
            font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12)
            background = JBColor(0xFFF3E0, 0x2A2515)
            border = JBUI.Borders.empty(6, 10)
            rows = 4
        }

        val previewScroll = JScrollPane(preview).apply {
            border = JBUI.Borders.customLine(JBColor.border(), 1)
            preferredSize = Dimension(0, 80)
        }

        add(previewScroll, BorderLayout.CENTER)
    }

    private fun buildActions() {
        val actionsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 0, 0, 0)
        }

        val acceptBtn = JButton("Accept", AllIcons.Actions.Checked).apply {
            toolTipText = "Approve this operation"
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            addActionListener {
                onApprove(false)
                removeSelf()
            }
        }

        val alwaysBtn = JButton("Always for this session").apply {
            toolTipText = "Auto-approve this tool type for the rest of the session"
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            addActionListener {
                onApprove(true)
                removeSelf()
            }
        }

        val rejectBtn = JButton("Reject", AllIcons.Actions.Cancel).apply {
            toolTipText = "Reject this operation"
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            addActionListener {
                onReject()
                removeSelf()
            }
        }

        actionsPanel.add(acceptBtn)
        actionsPanel.add(alwaysBtn)
        actionsPanel.add(rejectBtn)

        add(actionsPanel, BorderLayout.SOUTH)
    }

    private fun getTitle(): String {
        return when (getApprovalType()) {
            ApprovalType.BASH -> "Run Command"
            ApprovalType.WRITE -> "Write File"
            ApprovalType.EDIT -> "Edit File"
            ApprovalType.GENERIC -> "Tool Approval"
        }
    }

    private fun getToolIcon(): javax.swing.Icon {
        return when (getApprovalType()) {
            ApprovalType.BASH -> AllIcons.Actions.Run_anything
            ApprovalType.WRITE -> AllIcons.Actions.Edit
            ApprovalType.EDIT -> AllIcons.Actions.Diff
            ApprovalType.GENERIC -> AllIcons.General.Warning
        }
    }

    private fun getApprovalType(): ApprovalType {
        return when {
            toolName.contains("run_command") || toolName.contains("run_python") ||
                toolName.contains("bash") || toolName.contains("git_commit") -> ApprovalType.BASH
            toolName.contains("write_file") || toolName.contains("create") -> ApprovalType.WRITE
            toolName.contains("edit_file") || toolName.contains("apply_diff") ||
                toolName.contains("apply_patch") || toolName.contains("search_replace") -> ApprovalType.EDIT
            else -> ApprovalType.GENERIC
        }
    }

    private fun formatPreview(): String {
        return when (getApprovalType()) {
            ApprovalType.BASH -> {
                // Extract command from args JSON
                val cmdMatch = Regex(""""(?:command|code|message)"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(toolArgs)
                val cmd = cmdMatch?.groupValues?.get(1)?.replace("\\n", "\n") ?: toolArgs
                "$ $cmd"
            }
            ApprovalType.WRITE -> {
                val pathMatch = Regex(""""path"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(toolArgs)
                val path = pathMatch?.groupValues?.get(1) ?: "(unknown)"
                val contentMatch = Regex(""""content"\s*:\s*"((?:[^"\\]|\\.)*)"""", RegexOption.DOT_MATCHES_ALL).find(toolArgs)
                val contentLen = contentMatch?.groupValues?.get(1)?.length ?: 0
                "File: $path\nSize: $contentLen bytes"
            }
            ApprovalType.EDIT -> {
                val pathMatch = Regex(""""path"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(toolArgs)
                val path = pathMatch?.groupValues?.get(1) ?: "(unknown)"
                val searchMatch = Regex(""""search"\s*:\s*"((?:[^"\\]|\\.)*)"""", RegexOption.DOT_MATCHES_ALL).find(toolArgs)
                val search = searchMatch?.groupValues?.get(1)?.replace("\\n", "\n")?.take(200) ?: ""
                "File: $path\nSearch:\n$search"
            }
            ApprovalType.GENERIC -> "Tool: $toolName\nArgs: $toolArgs"
        }
    }

    private fun removeSelf() {
        isVisible = false
        parent?.remove(this)
        parent?.revalidate()
        parent?.repaint()
    }
}
