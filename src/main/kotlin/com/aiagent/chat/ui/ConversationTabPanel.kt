package com.aiagent.chat.ui

import com.aiagent.chat.model.ChatMessage
import com.aiagent.chat.model.UiLogEntry
import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

/**
 * Manages multiple conversation tabs with a tabbed card layout.
 * Each tab has its own message container and conversation history.
 *
 * Phase 8: Conversation Tabs.
 * Inspired by ProxyAI's AgentToolWindowTabPanel.
 */
class ConversationTabPanel : JBPanel<ConversationTabPanel>(BorderLayout()) {

    private val tabBar = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
        isOpaque = false
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
            JBUI.Borders.empty(2, 4)
        )
    }

    private val contentPanel = JPanel(CardLayout())
    private val cardLayout get() = contentPanel.layout as CardLayout

    private val conversations = mutableMapOf<String, Conversation>()
    private var activeTabId: String? = null

    private var nextId = 1

    private var modelStatusButton: JButton? = null

    var onTabChanged: ((String?) -> Unit)? = null
    var onNewTab: (() -> Unit)? = null
    var onMenuClick: ((java.awt.Component) -> Unit)? = null
    var onModelStatusClick: ((java.awt.Component) -> Unit)? = null
    var onRenameRequest: ((java.awt.Component) -> Unit)? = null
    /** Called when the last remaining tab is closed — parent should show the landing screen. */
    var onLastTabClosed: (() -> Unit)? = null
    /** Called when user wants to select a model from the More dropdown. */
    var onSelectModel: (() -> Unit)? = null
    /** Called when user wants to disable the current model from the More dropdown. */
    var onDisableModel: (() -> Unit)? = null
    /** Called when user wants to go to the sessions/landing view. */
    var onSessionsView: (() -> Unit)? = null
    /** Called when user wants to compress context. */
    var onCompressContext: (() -> Unit)? = null
    /** Called when user wants to toggle phase. */
    var onPhaseToggle: (() -> Unit)? = null
    /** Provides current model name for display in More dropdown. */
    var getModelName: (() -> String)? = null
    /** Provides current endpoint URL for display in More dropdown. */
    var getEndpoint: (() -> String)? = null
    /** Provides current phase for display in More dropdown. */
    var getPhase: (() -> String)? = null

    init {
        border = JBUI.Borders.empty()
        background = JBColor.PanelBackground

        // Row 1 (TOP): Button bar — above the tabs now
        val buttonBar = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 2)).apply {
            isOpaque = false
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
                JBUI.Borders.empty(2, 6)
            )
        }

        // Combined New Session + More in one unified box
        val newBtn = createTextIconButton(AllIcons.General.Add, "New", "New Session") {
            onNewTab?.invoke()
        }
        val moreBtn = createTextIconButton(createDropdownArrowIcon(), "More", "More Actions") { source ->
            showMoreActionsPopup(source)
        }
        // Strip individual borders so they look like one control
        listOf(newBtn, moreBtn).forEach { btn ->
            btn.isBorderPainted = false
            btn.isContentAreaFilled = false
            btn.isFocusPainted = false
            btn.margin = JBUI.insets(4, 10)
        }
        val combinedPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = true
            background = JBColor(0xFFFFFF, 0x2B2B2B)
            border = JBUI.Borders.customLine(JBColor.border(), 1)
            add(newBtn)
            // Thin internal divider (part of the box, not a separate component)
            add(JPanel().apply {
                isOpaque = true
                background = JBColor.border()
                preferredSize = Dimension(1, 22)
            })
            add(moreBtn)
        }
        buttonBar.add(combinedPanel)

        // Menu button (icon + text)
        buttonBar.add(createTextIconButton(createHamburgerIcon(), "Menu", "Settings & Menu") { source ->
            onMenuClick?.invoke(source)
        })

        // Row 2 (BELOW buttons): Tab bar (conversation tabs only, scrollable, narrower)
        val tabScroll = JScrollPane(tabBar).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_NEVER
            preferredSize = Dimension(0, 26)
        }

        // Stack button row above tab row
        val topPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(buttonBar, BorderLayout.NORTH)
            add(tabScroll, BorderLayout.CENTER)
        }
        add(topPanel, BorderLayout.NORTH)
        add(contentPanel, BorderLayout.CENTER)
    }

    /**
     * Creates a new conversation tab and returns its ID.
     */
    fun newConversation(title: String = "Session"): String {
        val id = "session_${nextId++}"
        val messageContainer = JPanel().apply {
            layout = java.awt.GridBagLayout()
            background = JBColor.PanelBackground
        }
        val fillerComponent = JPanel().apply { background = JBColor.PanelBackground }
        val fillerGbc = java.awt.GridBagConstraints().apply {
            gridx = 0
            gridy = 9999
            weightx = 1.0
            weighty = 1.0
            fill = java.awt.GridBagConstraints.BOTH
        }
        messageContainer.add(fillerComponent, fillerGbc)

        val scrollPane = JBScrollPane(messageContainer).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }

        val conversation = Conversation(
            id = id,
            title = title,
            messageContainer = messageContainer,
            fillerComponent = fillerComponent,
            fillerGbc = fillerGbc,
            scrollPane = scrollPane,
            currentRow = 0
        )
        conversations[id] = conversation

        contentPanel.add(scrollPane, id)
        addTabButton(id, title)

        switchTo(id)
        return id
    }

    private fun addTabButton(tabId: String, title: String) {
        val tabBtn = TabButton(title, tabId) {
            switchTo(tabId)
        }
        tabBar.add(tabBtn)
        tabBar.revalidate()
        tabBar.repaint()
    }

    fun switchTo(tabId: String) {
        if (!conversations.containsKey(tabId)) return
        activeTabId = tabId
        cardLayout.show(contentPanel, tabId)

        // Update tab button styles
        for (component in tabBar.components) {
            if (component is TabButton) {
                component.setActive(component.tabId == tabId)
            }
        }

        onTabChanged?.invoke(tabId)
    }

    fun getActiveTabId(): String? = activeTabId

    fun getActiveConversation(): Conversation? {
        return activeTabId?.let { conversations[it] }
    }

    fun getConversation(tabId: String): Conversation? = conversations[tabId]

    fun closeConversation(tabId: String) {
        val conv = conversations.remove(tabId)
        if (conv != null) {
            contentPanel.remove(conv.scrollPane)
        }

        // Remove tab button
        for (component in tabBar.components.toList()) {
            if (component is TabButton && component.tabId == tabId) {
                tabBar.remove(component)
            }
        }

        if (activeTabId == tabId) {
            val firstRemaining = conversations.keys.firstOrNull()
            if (firstRemaining != null) {
                switchTo(firstRemaining)
            } else {
                activeTabId = null
                onTabChanged?.invoke(null)
                onLastTabClosed?.invoke()
            }
        }

        tabBar.revalidate()
        tabBar.repaint()
    }

    fun renameConversation(tabId: String, newTitle: String) {
        val conv = conversations[tabId] ?: return
        conv.title = newTitle
        for (component in tabBar.components) {
            if (component is TabButton && component.tabId == tabId) {
                component.setTitle(newTitle)
            }
        }
    }

    fun getAllConversations(): List<Conversation> = conversations.values.toList()

    /**
     * Creates a small icon-only button with no border or content fill.
     */
    private fun createIconButton(icon: Icon, tooltip: String, onClick: (java.awt.Component) -> Unit): JButton {
        return JButton(icon).apply {
            toolTipText = tooltip
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusPainted = false
            margin = JBUI.insets(2)
            preferredSize = Dimension(24, 24)
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            addActionListener { e -> onClick(e.source as java.awt.Component) }
        }
    }

    /**
     * Creates a prominent button with both icon and text label.
     * Larger and more visible than the icon-only buttons.
     */
    private fun createTextIconButton(icon: Icon, text: String, tooltip: String, onClick: (java.awt.Component) -> Unit): JButton {
        return JButton(text, icon).apply {
            toolTipText = tooltip
            isContentAreaFilled = true
            isBorderPainted = true
            isFocusPainted = false
            margin = JBUI.insets(4, 8)
            font = font.deriveFont(java.awt.Font.PLAIN, 12f)
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            addActionListener { e -> onClick(e.source as java.awt.Component) }
        }
    }

    /**
     * Creates a model status button that shows the current model name.
     */
    private fun createModelStatusButton(): JButton {
        return JButton("Model").apply {
            toolTipText = "Model status — click for details"
            isContentAreaFilled = true
            isBorderPainted = true
            isFocusPainted = false
            margin = JBUI.insets(4, 8)
            font = font.deriveFont(java.awt.Font.PLAIN, 12f)
            foreground = ThemeUtils.PRIMARY_TEXT
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            addActionListener { e -> onModelStatusClick?.invoke(e.source as java.awt.Component) }
            modelStatusButton = this
        }
    }

    /**
     * Updates the model status button text.
     */
    fun updateModelStatus(modelName: String) {
        SwingUtilities.invokeLater {
            modelStatusButton?.text = modelName.take(20)
            modelStatusButton?.toolTipText = "Model: $modelName — click for details"
        }
    }

    /**
     * Creates a dropdown arrow icon (small triangle pointing down).
     */
    private fun createDropdownArrowIcon(): Icon {
        return object : Icon {
            override fun getIconWidth(): Int = 16
            override fun getIconHeight(): Int = 16
            override fun paintIcon(c: java.awt.Component?, g: java.awt.Graphics, x: Int, y: Int) {
                val g2 = g.create() as java.awt.Graphics2D
                try {
                    g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = ThemeUtils.SECONDARY_TEXT
                    // Draw a downward triangle (dropdown arrow)
                    val midX = x + 8
                    val topY = y + 4
                    val botY = y + 12
                    g2.fillPolygon(intArrayOf(midX - 4, midX + 4, midX), intArrayOf(topY, topY, botY), 3)
                } finally {
                    g2.dispose()
                }
            }
        }
    }

    /**
     * Creates a hamburger menu icon (4 horizontal lines).
     */
    private fun createHamburgerIcon(): Icon {
        return object : Icon {
            override fun getIconWidth(): Int = 16
            override fun getIconHeight(): Int = 16
            override fun paintIcon(c: java.awt.Component?, g: java.awt.Graphics, x: Int, y: Int) {
                val g2 = g.create() as java.awt.Graphics2D
                try {
                    g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = ThemeUtils.SECONDARY_TEXT
                    g2.stroke = java.awt.BasicStroke(1.5f)
                    val leftX = x + 2
                    val rightX = x + 14
                    // 4 horizontal lines evenly spaced
                    for (i in 0..3) {
                        val lineY = y + 3 + i * 3.5f
                        g2.drawLine(leftX, lineY.toInt(), rightX, lineY.toInt())
                    }
                } finally {
                    g2.dispose()
                }
            }
        }
    }

    /**
     * Shows a popup with "more actions": Model selection, Session Info, Rename, Sessions View, Compress, Phase.
     */
    private fun showMoreActionsPopup(source: java.awt.Component) {
        val popup = javax.swing.JPopupMenu()

        // Model selection
        val modelItem = javax.swing.JMenuItem("Select Model...", AllIcons.General.Balloon)
        modelItem.addActionListener {
            onSelectModel?.invoke()
        }
        popup.add(modelItem)

        // Model info (read-only display)
        val modelName = getModelName?.invoke() ?: "N/A"
        val modelInfoItem = javax.swing.JMenuItem("Model: $modelName").apply {
            icon = AllIcons.General.Information
            isEnabled = false
        }
        popup.add(modelInfoItem)

        val endpoint = getEndpoint?.invoke() ?: "N/A"
        val endpointItem = javax.swing.JMenuItem("Endpoint: ${endpoint.takeLast(40)}").apply {
            isEnabled = false
        }
        popup.add(endpointItem)

        // Disable current model
        val disableModelItem = javax.swing.JMenuItem("Disable Current Model", AllIcons.Actions.Forward)
        disableModelItem.toolTipText = "Disable the current model and switch to another from the same provider"
        disableModelItem.addActionListener {
            onDisableModel?.invoke()
        }
        popup.add(disableModelItem)

        popup.addSeparator()

        // Sessions View — go to landing/welcome screen
        val sessionsItem = javax.swing.JMenuItem("Sessions View", AllIcons.Nodes.Folder)
        sessionsItem.addActionListener {
            onSessionsView?.invoke()
        }
        popup.add(sessionsItem)

        // Session Info
        val infoItem = javax.swing.JMenuItem("Session Info", AllIcons.General.Information)
        infoItem.addActionListener {
            showSessionInfo(source)
        }
        popup.add(infoItem)

        // Rename Session
        val renameItem = javax.swing.JMenuItem("Rename Session", AllIcons.Actions.Edit)
        renameItem.addActionListener {
            onRenameRequest?.invoke(source)
        }
        popup.add(renameItem)

        popup.addSeparator()

        // Compress Context
        val compressItem = javax.swing.JMenuItem("Compress Context", AllIcons.Actions.GC)
        compressItem.addActionListener {
            onCompressContext?.invoke()
        }
        popup.add(compressItem)

        // Phase toggle
        val phaseLabel = getPhase?.invoke() ?: "execution"
        val phaseDisplay = if (phaseLabel == "execution") "Phase: Execution" else "Phase: Discovery"
        val phaseItem = javax.swing.JMenuItem(phaseDisplay, AllIcons.Actions.ChangeView)
        phaseItem.addActionListener {
            onPhaseToggle?.invoke()
        }
        popup.add(phaseItem)

        popup.show(source, 0, source.height)
    }

    /**
     * Shows a popup with details about the active session, positioned near the source button.
     */
    private fun showSessionInfo(source: java.awt.Component) {
        val conv = getActiveConversation()
        val popup = JPopupMenu()
        if (conv == null) {
            popup.add(JLabel("No active session"))
        } else {
            popup.add(JLabel("Title: ${conv.title}"))
            popup.add(JLabel("ID: ${conv.id}"))
            popup.add(JLabel("History: ${conv.history.size} messages"))
            popup.add(JLabel("UI Log: ${conv.uiLog.size} entries"))
        }
        popup.show(source, 0, source.height)
    }

    /**
     * Represents a single conversation with its own message container,
     * history, and UI log for per-tab isolation.
     */
    class Conversation(
        val id: String,
        var title: String,
        val messageContainer: JPanel,
        val fillerComponent: JPanel,
        val fillerGbc: java.awt.GridBagConstraints,
        val scrollPane: JScrollPane,
        var currentRow: Int,
        val history: MutableList<ChatMessage> = mutableListOf(),
        val uiLog: MutableList<UiLogEntry> = mutableListOf()
    )

    /**
     * A clickable tab button with active/inactive styling and close button.
     */
    private class TabButton(
        private var titleText: String,
        val tabId: String,
        val onClick: () -> Unit
    ) : JPanel(BorderLayout()) {

        private val titleLabel = JBLabel(titleText).apply {
            font = font.deriveFont(java.awt.Font.PLAIN, 11f)
            border = JBUI.Borders.empty(0, 4)
        }

        private val closeBtn = JButton(AllIcons.Actions.Close).apply {
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusPainted = false
            preferredSize = Dimension(16, 16)
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            toolTipText = "Close tab"
            addActionListener {
                // Find parent ConversationTabPanel and close this tab
                var parent = parent
                while (parent != null && parent !is ConversationTabPanel) {
                    parent = parent.parent
                }
                (parent as? ConversationTabPanel)?.closeConversation(tabId)
            }
        }

        init {
            isOpaque = false
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 0, 0, 0, 1),
                JBUI.Borders.empty(2, 6)
            )
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)

            val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
                add(titleLabel)
                add(closeBtn)
            }
            add(leftPanel, BorderLayout.CENTER)

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    onClick()
                }

                override fun mousePressed(e: MouseEvent) {
                    handlePopup(e)
                }

                override fun mouseReleased(e: MouseEvent) {
                    handlePopup(e)
                }

                private fun handlePopup(e: MouseEvent) {
                    if (e.isPopupTrigger) {
                        showContextMenu(e)
                    }
                }

                private fun showContextMenu(e: MouseEvent) {
                    val popup = JPopupMenu()
                    val renameItem = JMenuItem("Rename Tab")
                    renameItem.addActionListener {
                        var parent = parent
                        while (parent != null && parent !is ConversationTabPanel) {
                            parent = parent.parent
                        }
                        (parent as? ConversationTabPanel)?.onRenameRequest?.invoke(this@TabButton)
                    }
                    val closeItem = JMenuItem("Close Tab")
                    closeItem.addActionListener {
                        var parent = parent
                        while (parent != null && parent !is ConversationTabPanel) {
                            parent = parent.parent
                        }
                        (parent as? ConversationTabPanel)?.closeConversation(tabId)
                    }
                    popup.add(renameItem)
                    popup.add(closeItem)
                    popup.show(this@TabButton, e.x, e.y)
                }
            })
        }

        fun setActive(active: Boolean) {
            titleLabel.font = titleLabel.font.deriveFont(
                if (active) java.awt.Font.BOLD else java.awt.Font.PLAIN,
                11f
            )
            titleLabel.foreground = if (active) {
                JBColor(0x4A9EFF, 0x6BB6FF)
            } else {
                JBColor(0x666666, 0x999999)
            }
            isOpaque = active
            if (active) {
                // Light blue in dark mode, dark blue in light theme
                background = JBColor(0xD0E4FF, 0x1A3A5C)
            }
        }

        fun setTitle(newTitle: String) {
            titleText = newTitle
            titleLabel.text = newTitle
            revalidate()
            repaint()
        }

        override fun paintComponent(g: java.awt.Graphics) {
            if (isOpaque) {
                val g2 = g.create() as java.awt.Graphics2D
                try {
                    g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = background
                    g2.fillRect(0, 0, width, height)
                } finally {
                    g2.dispose()
                }
            }
            super.paintComponent(g)
        }
    }
}
