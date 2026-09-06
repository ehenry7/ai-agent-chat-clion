package com.aiagent.chat.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Inline panel for renaming a session tab.
 * Replaces the blocking Messages.showInputDialog with an inline UI
 * rendered inside the chat message area.
 */
class InlineRenamePanel(
    private val currentName: String,
    private val onRename: (String) -> Unit,
    private val onCancel: () -> Unit = {}
) : JBPanel<InlineRenamePanel>(BorderLayout()) {

    init {
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(ThemeUtils.ACCENT, 1),
            JBUI.Borders.empty(8, 12)
        )
        background = JBColor.namedColor("Editor.background", JBColor(0xF0F6FF, 0x1A2332))

        val headerPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            val titleLabel = JBLabel("Rename Session").apply {
                font = font.deriveFont(java.awt.Font.BOLD, 12f)
                foreground = ThemeUtils.ACCENT
                icon = AllIcons.Actions.Edit
            }
            add(titleLabel, BorderLayout.WEST)
        }
        add(headerPanel, BorderLayout.NORTH)

        val bodyPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(6)

            val textField = JBTextField(currentName).apply {
                font = font.deriveFont(java.awt.Font.PLAIN, 12f)
            }

            val btnPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 4)).apply {
                isOpaque = false

                val okBtn = JButton("OK").apply {
                    addActionListener {
                        val newName = textField.text.trim()
                        if (newName.isNotBlank()) {
                            onRename(newName)
                        } else {
                            onCancel()
                        }
                        hideSelf()
                    }
                }

                val cancelBtn = JButton("Cancel").apply {
                    addActionListener {
                        onCancel()
                        hideSelf()
                    }
                }

                add(okBtn)
                add(cancelBtn)
            }

            add(textField, BorderLayout.CENTER)
            add(btnPanel, BorderLayout.SOUTH)

            // Focus the text field and select all for easy editing
            SwingUtilities.invokeLater {
                textField.requestFocusInWindow()
                textField.selectAll()
            }
        }
        add(bodyPanel, BorderLayout.CENTER)
    }

    private fun hideSelf() {
        isVisible = false
        parent?.revalidate()
        parent?.repaint()
    }
}
