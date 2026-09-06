package com.aiagent.chat.ui

import com.aiagent.chat.model.ChatMeta
import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.text.SimpleDateFormat
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants

/**
 * Landing/welcome panel shown on startup or when the last session tab is closed.
 * Displays a list of historical sessions with metadata, quick action suggestions,
 * and buttons to start a new session or configure providers.
 */
class LandingPanel(
    private val onQuickAction: (String) -> Unit,
    private val onConfigure: () -> Unit,
    private val onNewSession: () -> Unit,
    private val onSessionSelected: (ChatMeta) -> Unit,
    private val onDeleteSession: (ChatMeta) -> Unit = {}
) : JBPanel<LandingPanel>(BorderLayout()) {

    private val sessionsListPanel = JPanel().apply {
        layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
        isOpaque = false
    }

    init {
        border = JBUI.Borders.empty(20, 40)
        background = JBColor.PanelBackground

        val centerPanel = JPanel(BorderLayout())
        centerPanel.isOpaque = false

        centerPanel.add(buildHeader(), BorderLayout.NORTH)
        centerPanel.add(buildContent(), BorderLayout.CENTER)
        centerPanel.add(buildFooter(), BorderLayout.SOUTH)

        add(centerPanel, BorderLayout.CENTER)
    }

    private fun buildHeader(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.isOpaque = false
        panel.border = JBUI.Borders.empty(0, 0, 16, 0)

        val titleLabel = JBLabel("AI Agent Chat").apply {
            font = font.deriveFont(java.awt.Font.BOLD, 22f)
        }

        val subtitleLabel = JBLabel("Your autonomous coding assistant for CLion").apply {
            font = font.deriveFont(java.awt.Font.PLAIN, 12f)
            foreground = JBColor(0x666666, 0x999999)
        }

        val textPanel = JPanel(GridLayout(2, 1, 0, 4)).apply {
            isOpaque = false
            add(titleLabel)
            add(subtitleLabel)
        }

        panel.add(textPanel, BorderLayout.CENTER)

        // New Session button in header right side
        val newSessionBtn = JButton("New Session", AllIcons.General.Add).apply {
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            font = font.deriveFont(java.awt.Font.PLAIN, 12f)
            addActionListener { onNewSession() }
        }
        panel.add(newSessionBtn, BorderLayout.EAST)

        return panel
    }

    private fun buildContent(): JComponent {
        val outerPanel = JPanel(BorderLayout())
        outerPanel.isOpaque = false

        // --- Sessions section ---
        val sessionsHeader = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 0, 8, 0)
            add(JBLabel("Recent Sessions").apply {
                font = font.deriveFont(java.awt.Font.BOLD, 14f)
            }, BorderLayout.WEST)
        }
        outerPanel.add(sessionsHeader, BorderLayout.NORTH)

        val sessionsScroll = JBScrollPane(sessionsListPanel).apply {
            isOpaque = false
            viewport.isOpaque = false
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            preferredSize = java.awt.Dimension(0, 200)
        }
        outerPanel.add(sessionsScroll, BorderLayout.CENTER)

        // --- Quick actions section (below sessions) ---
        val actionsHeader = JBLabel("Quick Actions").apply {
            font = font.deriveFont(java.awt.Font.BOLD, 14f)
            border = JBUI.Borders.empty(16, 0, 8, 0)
        }
        val actionsPanel = buildQuickActions()

        val southPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(actionsHeader, BorderLayout.NORTH)
            add(actionsPanel, BorderLayout.CENTER)
        }
        outerPanel.add(southPanel, BorderLayout.SOUTH)

        return outerPanel
    }

    /**
     * Update the sessions list display. Called by the parent panel when sessions change.
     */
    fun updateSessions(sessions: List<ChatMeta>) {
        sessionsListPanel.removeAll()

        if (sessions.isEmpty()) {
            val emptyLabel = JBLabel("No saved sessions yet. Start a new conversation to begin.").apply {
                font = font.deriveFont(java.awt.Font.ITALIC, 12f)
                foreground = ThemeUtils.MUTED_TEXT
                border = JBUI.Borders.empty(12, 0)
            }
            val wrapper = JPanel(FlowLayout(FlowLayout.CENTER)).apply { isOpaque = false }
            wrapper.add(emptyLabel)
            sessionsListPanel.add(wrapper)
        } else {
            val dateFormat = SimpleDateFormat("MMM d, yyyy HH:mm")
            val sorted = sessions.sortedByDescending { it.updatedAt }
            for (meta in sorted) {
                val card = createSessionCard(meta, dateFormat)
                card.maximumSize = java.awt.Dimension(java.lang.Integer.MAX_VALUE, card.preferredSize.height)
                sessionsListPanel.add(card)
                sessionsListPanel.add(javax.swing.Box.createVerticalStrut(6))
            }
        }

        // Vertical glue pushes all session cards to the top
        sessionsListPanel.add(javax.swing.Box.createVerticalGlue())

        sessionsListPanel.revalidate()
        sessionsListPanel.repaint()
    }

    private fun createSessionCard(meta: ChatMeta, dateFormat: SimpleDateFormat): JComponent {
        val card = JPanel(BorderLayout())
        card.isOpaque = false
        card.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border(), 1),
            JBUI.Borders.empty(10, 14)
        )
        card.background = ThemeUtils.TOOL_CARD_BG
        card.cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)

        val leftPanel = JPanel(BorderLayout()).apply { isOpaque = false }

        // Session name
        val nameLabel = JBLabel(meta.name).apply {
            font = font.deriveFont(java.awt.Font.BOLD, 13f)
        }
        leftPanel.add(nameLabel, BorderLayout.NORTH)

        // Metadata line: model + dates
        val metaText = buildString {
            append("Model: ${meta.model.ifBlank { "N/A" }}")
            append("    |    ")
            append("Updated: ${dateFormat.format(java.util.Date(meta.updatedAt))}")
        }
        val metaLabel = JBLabel(metaText).apply {
            font = font.deriveFont(java.awt.Font.PLAIN, 11f)
            foreground = JBColor(0x666666, 0x999999)
        }
        leftPanel.add(metaLabel, BorderLayout.SOUTH)

        card.add(leftPanel, BorderLayout.CENTER)

        // Right side: delete button + open icon hint
        val iconLabel = JBLabel(AllIcons.Actions.Forward).apply {
            foreground = JBColor(0x999999, 0x666666)
        }
        val deleteBtn = JButton(AllIcons.Actions.Close).apply {
            toolTipText = "Delete session"
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusPainted = false
            margin = JBUI.insets(2)
            preferredSize = java.awt.Dimension(20, 20)
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            addActionListener { onDeleteSession(meta) }
        }
        val iconPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply { isOpaque = false }
        iconPanel.add(deleteBtn)
        iconPanel.add(iconLabel)
        card.add(iconPanel, BorderLayout.EAST)

        card.addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                card.background = JBColor(0xEAEAEA, 0x333333)
                card.isOpaque = true
            }

            override fun mouseExited(e: MouseEvent) {
                card.background = ThemeUtils.TOOL_CARD_BG
                card.isOpaque = false
            }

            override fun mouseClicked(e: MouseEvent) {
                onSessionSelected(meta)
            }
        })

        return card
    }

    private fun buildQuickActions(): JComponent {
        val panel = JPanel(GridLayout(0, 1, 6, 6))
        panel.isOpaque = false

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
            JBUI.Borders.empty(8, 12)
        )
        card.background = ThemeUtils.TOOL_CARD_BG
        card.cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)

        val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 10, 0)).apply {
            isOpaque = false
        }

        val iconLabel = JBLabel(action.icon)
        val textPanel = JPanel(GridLayout(2, 1, 0, 2)).apply {
            isOpaque = false
        }
        val titleLabel = JBLabel(action.title).apply {
            font = font.deriveFont(java.awt.Font.BOLD, 12f)
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
                card.background = ThemeUtils.TOOL_CARD_BG
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
        panel.border = JBUI.Borders.empty(12, 0, 0, 0)

        val configureBtn = JButton("Configure Providers", AllIcons.General.Settings).apply {
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
