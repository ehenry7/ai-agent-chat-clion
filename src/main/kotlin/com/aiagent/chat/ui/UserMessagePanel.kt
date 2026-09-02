package com.aiagent.chat.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.text.html.HTMLEditorKit

/**
 * Panel for rendering user messages with an avatar icon and optional referenced files.
 */
class UserMessagePanel(
    private val messageText: String,
    private val referencedFiles: List<String> = emptyList()
) : BaseMessagePanel("You", "user") {

    override fun getRoleIcon(): Icon = AllIcons.General.User

    override fun getBubbleBackground(): JBColor = JBColor(0xEEEEEE, 0x2D2F31)

    override fun buildBody() {
        val wrapper = JPanel(BorderLayout())
        wrapper.isOpaque = false

        // Message text
        val textPane = JTextPane().apply {
            contentType = "text/html"
            editorKit = HTMLEditorKit()
            isEditable = false
            background = background
            putClientProperty(JTextPane.HONOR_DISPLAY_PROPERTIES, true)
            text = "<html><body style='font-family: sans-serif; font-size: 12px; word-wrap: break-word;'>" +
                    escapeHtml(messageText) + "</body></html>"
            border = JBUI.Borders.empty(2, 0)
        }
        wrapper.add(textPane, BorderLayout.CENTER)

        // Referenced files accordion (if any)
        if (referencedFiles.isNotEmpty()) {
            val filesPanel = buildReferencedFilesPanel()
            wrapper.add(filesPanel, BorderLayout.SOUTH)
        }

        setBodyContent(wrapper)
    }

    private fun buildReferencedFilesPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.isOpaque = false
        panel.border = JBUI.Borders.empty(4, 0, 0, 0)

        val header = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
        }
        val headerLabel = JBLabel("${referencedFiles.size} referenced file(s)").apply {
            font = font.deriveFont(java.awt.Font.ITALIC, 11f)
            foreground = JBColor(0x666666, 0x999999)
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        }
        header.add(headerLabel)

        val filesList = JPanel(java.awt.GridLayout(0, 1, 2, 2)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 16, 0, 0)
            isVisible = false
        }
        referencedFiles.forEach { filePath ->
            val fileLabel = JBLabel("  $filePath").apply {
                icon = AllIcons.FileTypes.Any_type
                font = font.deriveFont(11f)
                foreground = JBColor(0x555555, 0xAAAAAA)
            }
            filesList.add(fileLabel)
        }

        headerLabel.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                filesList.isVisible = !filesList.isVisible
                headerLabel.text = if (filesList.isVisible) "Hide files" else "${referencedFiles.size} referenced file(s)"
                panel.revalidate()
                panel.repaint()
            }
        })

        panel.add(header, BorderLayout.NORTH)
        panel.add(filesList, BorderLayout.CENTER)
        return panel
    }

    override fun getPlainText(): String = messageText

    private fun escapeHtml(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\n", "<br>")
    }
}
