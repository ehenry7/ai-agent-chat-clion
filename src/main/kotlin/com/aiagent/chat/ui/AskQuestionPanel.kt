package com.aiagent.chat.ui

import com.aiagent.chat.tools.AskQuestionsHandler
import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane

/**
 * Inline panel for asking the user a structured question from the agent.
 * Replaces the blocking Messages.showYesNoDialog / JOptionPane calls with
 * a non-blocking inline UI rendered inside the chat message area.
 *
 * Supports all question types: yes_no, single_select, multi_select, free_text.
 */
class AskQuestionPanel(
    private val question: AskQuestionsHandler.Question,
    private val onAnswer: (String) -> Unit
) : JBPanel<AskQuestionPanel>(BorderLayout()) {

    init {
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor(0x0066CC, 0x4A9EFF), 1),
            JBUI.Borders.empty(8, 12)
        )
        background = JBColor(0xF0F6FF, 0x1A2332)

        val headerPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            val iconLabel = JBLabel(AllIcons.General.QuestionDialog).apply {
                font = font.deriveFont(java.awt.Font.BOLD, 12f)
            }
            val titleLabel = JBLabel("Agent Question").apply {
                font = font.deriveFont(java.awt.Font.BOLD, 12f)
                foreground = JBColor(0x0066CC, 0x4A9EFF)
            }
            val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                isOpaque = false
                add(iconLabel)
                add(titleLabel)
            }
            add(leftPanel, BorderLayout.WEST)
        }
        add(headerPanel, BorderLayout.NORTH)

        val bodyPanel = buildBody()
        add(bodyPanel, BorderLayout.CENTER)
    }

    private fun buildBody(): JComponent {
        val wrapper = JPanel(BorderLayout())
        wrapper.isOpaque = false
        wrapper.border = JBUI.Borders.emptyTop(6)

        val questionLabel = JBLabel("<html><body style='font-size: 12px;'>${question.question}</body></html>").apply {
            font = font.deriveFont(java.awt.Font.PLAIN, 12f)
        }
        wrapper.add(questionLabel, BorderLayout.NORTH)

        when (question.type) {
            "yes_no" -> wrapper.add(buildYesNoBody(), BorderLayout.CENTER)
            "single_select" -> wrapper.add(buildSingleSelectBody(), BorderLayout.CENTER)
            "multi_select" -> wrapper.add(buildMultiSelectBody(), BorderLayout.CENTER)
            else -> wrapper.add(buildFreeTextBody(), BorderLayout.CENTER)
        }

        return wrapper
    }

    private fun buildYesNoBody(): JComponent {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply { isOpaque = false }
        val yesBtn = JButton("Yes").apply {
            addActionListener {
                onAnswer("yes")
                isVisible = false
                parent?.revalidate()
                parent?.repaint()
            }
        }
        val noBtn = JButton("No").apply {
            addActionListener {
                onAnswer("no")
                isVisible = false
                parent?.revalidate()
                parent?.repaint()
            }
        }
        panel.add(yesBtn)
        panel.add(noBtn)
        return panel
    }

    private fun buildSingleSelectBody(): JComponent {
        if (question.options.isEmpty()) return buildFreeTextBody()

        val panel = JPanel(BorderLayout())
        panel.isOpaque = false

        val comboBox = JComboBox(question.options.toTypedArray()).apply {
            if (question.defaultAnswer != null && question.options.contains(question.defaultAnswer)) {
                selectedItem = question.defaultAnswer
            }
        }

        val submitBtn = JButton("Submit").apply {
            addActionListener {
                onAnswer(comboBox.selectedItem?.toString() ?: "(no answer)")
                isVisible = false
                parent?.revalidate()
                parent?.repaint()
            }
        }

        val btnPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply { isOpaque = false }
        btnPanel.add(submitBtn)

        panel.add(comboBox, BorderLayout.NORTH)
        panel.add(btnPanel, BorderLayout.SOUTH)
        return panel
    }

    private fun buildMultiSelectBody(): JComponent {
        if (question.options.isEmpty()) return buildFreeTextBody()

        val panel = JPanel(BorderLayout())
        panel.isOpaque = false

        val checkboxes = question.options.map { JCheckBox(it) }
        val checksPanel = JPanel(GridLayout(0, 1, 2, 2)).apply { isOpaque = false }
        checkboxes.forEach { checksPanel.add(it) }

        val submitBtn = JButton("Submit").apply {
            addActionListener {
                val selected = checkboxes.filter { it.isSelected }.map { it.text }.joinToString(", ")
                onAnswer(selected.ifEmpty { "(no answer)" })
                isVisible = false
                parent?.revalidate()
                parent?.repaint()
            }
        }

        val btnPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply { isOpaque = false }
        btnPanel.add(submitBtn)

        panel.add(JScrollPane(checksPanel).apply {
            border = JBUI.Borders.empty()
            preferredSize = java.awt.Dimension(0, 80)
        }, BorderLayout.CENTER)
        panel.add(btnPanel, BorderLayout.SOUTH)
        return panel
    }

    private fun buildFreeTextBody(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.isOpaque = false

        val textArea = JBTextArea(3, 40).apply {
            lineWrap = true
            wrapStyleWord = true
            border = JBUI.Borders.empty(4, 6)
            text = question.defaultAnswer ?: ""
        }

        val submitBtn = JButton("Submit").apply {
            addActionListener {
                onAnswer(textArea.text.ifBlank { "(no answer)" })
                isVisible = false
                parent?.revalidate()
                parent?.repaint()
            }
        }

        val btnPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply { isOpaque = false }
        btnPanel.add(submitBtn)

        panel.add(textArea, BorderLayout.CENTER)
        panel.add(btnPanel, BorderLayout.SOUTH)
        return panel
    }
}
