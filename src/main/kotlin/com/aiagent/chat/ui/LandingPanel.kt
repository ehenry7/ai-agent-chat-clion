package com.aiagent.chat.ui

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
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Landing/welcome panel shown when no conversation has started yet.
 * Displays welcome message, quick action suggestions, and setup link.
 * Inspired by ProxyAI's ChatToolWindowLandingPanel.
 */
class LandingPanel(
    private val onQuickAction: (String) -> Unit,
    private val onConfigure: () -> Unit
) : JBPanel<LandingPanel>(BorderLayout()) {

    init {
        border = JBUI.Borders.empty(40, 60)
        background = JBColor.PanelBackground

        val centerPanel = JPanel(BorderLayout())
        centerPanel.isOpaque = false

        // Welcome header
        val headerPanel = buildHeader()
        centerPanel.add(headerPanel, BorderLayout.NORTH)

        // Quick actions
        val actionsPanel = buildQuickActions()
        centerPanel.add(actionsPanel, BorderLayout.CENTER)

        // Footer with configure link
        val footerPanel = buildFooter()
        centerPanel.add(footerPanel, BorderLayout.SOUTH)

        add(centerPanel, BorderLayout.CENTER)
    }

    private fun buildHeader(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.isOpaque = false
        panel.border = JBUI.Borders.empty(0, 0, 20, 0)

        val titleLabel = JBLabel("AI Agent Chat").apply {
            font = font.deriveFont(java.awt.Font.BOLD, 24f)
        }

        val subtitleLabel = JBLabel("Your autonomous coding assistant for CLion").apply {
            font = font.deriveFont(java.awt.Font.PLAIN, 13f)
            foreground = JBColor(0x666666, 0x999999)
        }

        val textPanel = JPanel(GridLayout(2, 1, 0, 4)).apply {
            isOpaque = false
            add(titleLabel)
            add(subtitleLabel)
        }

        panel.add(textPanel, BorderLayout.CENTER)
        return panel
    }

    private fun buildQuickActions(): JComponent {
        val panel = JPanel(GridLayout(0, 1, 8, 8))
        panel.isOpaque = false
        panel.border = JBUI.Borders.empty(20, 0)

        val actions = listOf(
            QuickAction("Explain Code", "Ask me to explain any file, function, or code block", AllIcons.Actions.InlayRenameInNoCodeFiles),
            QuickAction("Write Tests", "I can generate unit tests for your Kotlin/Java code", AllIcons.RunConfigurations.Junit),
            QuickAction("Find Bugs", "I'll analyze your code for potential issues and bugs", AllIcons.General.InspectionsEye),
            QuickAction("Refactor", "Suggest improvements and apply code changes", AllIcons.Actions.RefactoringBulb),
            QuickAction("Explore Project", "Search files, grep content, and understand structure", AllIcons.Actions.Preview)
        )

        for (action in actions) {
            panel.add(createActionCard(action))
        }

        return panel
    }

    private fun createActionCard(action: QuickAction): JComponent {
        val card = JPanel(BorderLayout())
        card.isOpaque = false
        card.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border(), 1),
            JBUI.Borders.empty(10, 14)
        )
        card.background = JBColor(0xF8F8F8, 0x2A2A2A)
        card.cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)

        val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 10, 0)).apply {
            isOpaque = false
        }

        val iconLabel = JBLabel(action.icon)
        val textPanel = JPanel(GridLayout(2, 1, 0, 2)).apply {
            isOpaque = false
        }
        val titleLabel = JBLabel(action.title).apply {
            font = font.deriveFont(java.awt.Font.BOLD, 13f)
        }
        val descLabel = JBLabel(action.description).apply {
            font = font.deriveFont(java.awt.Font.PLAIN, 11f)
            foreground = JBColor(0x666666, 0x999999)
        }
        textPanel.add(titleLabel)
        textPanel.add(descLabel)

        leftPanel.add(iconLabel)
        leftPanel.add(textPanel)

        card.add(leftPanel, BorderLayout.CENTER)

        card.addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                card.background = JBColor(0xEAEAEA, 0x333333)
                card.isOpaque = true
            }

            override fun mouseExited(e: MouseEvent) {
                card.background = JBColor(0xF8F8F8, 0x2A2A2A)
                card.isOpaque = false
            }

            override fun mouseClicked(e: MouseEvent) {
                onQuickAction(action.title)
            }
        })

        return card
    }

    private fun buildFooter(): JComponent {
        val panel = JPanel(FlowLayout(FlowLayout.CENTER))
        panel.isOpaque = false
        panel.border = JBUI.Borders.empty(20, 0, 0, 0)

        val configureBtn = JButton("Configure API Connection", AllIcons.General.Settings).apply {
            isContentAreaFilled = false
            isBorderPainted = true
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            font = font.deriveFont(java.awt.Font.PLAIN, 12f)
            addActionListener { onConfigure() }
        }

        panel.add(configureBtn)
        return panel
    }

    private data class QuickAction(
        val title: String,
        val description: String,
        val icon: javax.swing.Icon
    )
}
