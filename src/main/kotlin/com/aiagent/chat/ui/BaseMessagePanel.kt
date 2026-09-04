package com.aiagent.chat.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Abstract base panel for chat message bubbles.
 * Provides a header (role label + action icons) and a body content area.
 * Inspired by ProxyAI's BaseMessagePanel architecture.
 */
abstract class BaseMessagePanel(
    protected val displayName: String,
    protected val role: String
) : JBPanel<BaseMessagePanel>(BorderLayout()) {

    protected val headerPanel: JPanel = JPanel(BorderLayout())
    protected val bodyContainer: JPanel = JPanel(BorderLayout())
    protected val actionsPanel: JPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0))

    private var bodyComponent: JComponent? = null

    init {
        com.aiagent.chat.debug.DebugLog.info("BaseMessagePanel", "init START: displayName='$displayName', role='$role', bubbleBackground=${getBubbleBackground()}, isEDT=${SwingUtilities.isEventDispatchThread()}")
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border(), 1),
            JBUI.Borders.empty(8, 12)
        )
        background = getBubbleBackground()
        com.aiagent.chat.debug.DebugLog.info("BaseMessagePanel", "init: border=$border, background=$background")

        buildHeader()

        add(headerPanel, BorderLayout.NORTH)
        add(bodyContainer, BorderLayout.CENTER)
        com.aiagent.chat.debug.DebugLog.info("BaseMessagePanel", "init: headerPanel (NORTH) + bodyContainer (CENTER) added, headerPanel size=${headerPanel.width}x${headerPanel.height}")

        // buildBody() is NOT called here — subclasses must call it from their
        // own init{} block so that subclass constructor properties (messageText,
        // project, etc.) are initialized before buildBody() runs.
        com.aiagent.chat.debug.DebugLog.info("BaseMessagePanel", "init END: preferredSize=${preferredSize}, componentCount=${components.size}")
    }

    private fun buildHeader() {
        val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
        }

        val iconLabel = JLabel(getRoleIcon())
        val nameLabel = JBLabel(displayName).apply {
            font = font.deriveFont(java.awt.Font.BOLD, 12f)
        }

        leftPanel.add(iconLabel)
        leftPanel.add(nameLabel)

        headerPanel.add(leftPanel, BorderLayout.WEST)
        headerPanel.add(actionsPanel, BorderLayout.EAST)
        headerPanel.isOpaque = false
        headerPanel.border = JBUI.Borders.emptyBottom(4)

        buildActions()
    }

    private fun buildActions() {
        val copyBtn = createActionButton(AllIcons.Actions.Copy, "Copy") {
            copyToClipboard()
        }
        val deleteBtn = createActionButton(AllIcons.Actions.Close, "Remove") {
            isVisible = false
            parent?.revalidate()
            parent?.repaint()
        }
        actionsPanel.add(copyBtn)
        actionsPanel.add(deleteBtn)
        actionsPanel.isOpaque = false
    }

    private fun createActionButton(icon: javax.swing.Icon, tooltip: String, onClick: () -> Unit): JButton {
        return JButton(icon).apply {
            toolTipText = tooltip
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusPainted = false
            margin = JBUI.insets(2)
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    onClick()
                }
            })
        }
    }

    protected abstract fun buildBody()

    protected fun setBodyContent(component: JComponent) {
        try {
            bodyComponent?.let { bodyContainer.remove(it) }
            bodyComponent = component
            bodyContainer.add(component, BorderLayout.CENTER)
            bodyContainer.isOpaque = false
            revalidate()
            repaint()
            com.aiagent.chat.debug.DebugLog.info("BaseMessagePanel", "setBodyContent: component class=${component.javaClass.name}, preferredSize=${component.preferredSize}, bodyContainer size=${bodyContainer.width}x${bodyContainer.height}")
        } catch (e: Exception) {
            com.aiagent.chat.debug.DebugLog.error("BaseMessagePanel", "setBodyContent failed: ${e.message}", e)
        }
    }

    protected abstract fun getRoleIcon(): javax.swing.Icon

    protected abstract fun getBubbleBackground(): JBColor

    protected abstract fun getPlainText(): String

    private fun copyToClipboard() {
        val selection = StringSelection(getPlainText())
        Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, null)
    }
}
