package com.aiagent.chat.ui

import com.aiagent.chat.model.ChatMessage
import com.aiagent.chat.model.UiLogEntry
import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.CardLayout
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

    var onTabChanged: ((String?) -> Unit)? = null
    var onNewTab: (() -> Unit)? = null

    init {
        border = JBUI.Borders.empty()
        background = JBColor.PanelBackground

        // Tab bar with scroll
        val tabScroll = JScrollPane(tabBar).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_NEVER
            preferredSize = Dimension(0, 32)
        }

        add(tabScroll, BorderLayout.NORTH)
        add(contentPanel, BorderLayout.CENTER)
    }

    /**
     * Creates a new conversation tab and returns its ID.
     */
    fun newConversation(title: String = "New Chat"): String {
        val id = "chat_${nextId++}"
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

        val scrollPane = JScrollPane(messageContainer).apply {
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

    fun closeConversation(tabId: String) {
        if (conversations.size <= 1) return // Keep at least one tab

        conversations.remove(tabId)
        contentPanel.remove(tabId)

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
            font = font.deriveFont(java.awt.Font.PLAIN, 12f)
            border = JBUI.Borders.empty(0, 4)
        }

        private val closeBtn = JButton(AllIcons.Actions.Close).apply {
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusPainted = false
            margin = JBUI.insets(0)
            preferredSize = java.awt.Dimension(16, 16)
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
                JBUI.Borders.empty(4, 8)
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
                        val newName = Messages.showInputDialog(
                            null,
                            "Enter new tab name:",
                            "Rename Conversation",
                            null,
                            titleText,
                            null
                        )
                        if (newName != null && newName.isNotBlank()) {
                            var parent = parent
                            while (parent != null && parent !is ConversationTabPanel) {
                                parent = parent.parent
                            }
                            (parent as? ConversationTabPanel)?.renameConversation(tabId, newName.trim())
                        }
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
                12f
            )
            titleLabel.foreground = if (active) {
                JBColor(0x0066CC, 0x4A9EFF)
            } else {
                JBColor(0x666666, 0x999999)
            }
            isOpaque = active
            if (active) {
                background = JBColor(0xE8EAF0, 0x2A2D30)
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
