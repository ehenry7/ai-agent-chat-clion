package com.aiagent.chat.debug

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.dsl.builder.*
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.text.html.HTMLEditorKit

class DebugLogPanel : JPanel(BorderLayout()) {
    private val textPane = JTextPane().apply {
        contentType = "text/html"
        editorKit = HTMLEditorKit()
        isEditable = false
        background = JBColor(0x1E1E1E, 0x1E1E1E)
        foreground = JBColor(0xD4D4D4, 0xD4D4D4)
        putClientProperty(JTextPane.HONOR_DISPLAY_PROPERTIES, true)
    }

    private val scrollPane = JScrollPane(textPane).apply {
        border = JBUI.Borders.empty()
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
    }

    private val headerPanel = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.customLine(JBColor(0x3C3C3C, 0x3C3C3C), 0, 0, 1, 0)
        background = JBColor(0x2D2D2D, 0x2D2D2D)
    }

    private val headerLabel = JLabel("Debug Log").apply {
        font = font.deriveFont(java.awt.Font.BOLD, 11f)
        foreground = JBColor(0xD4D4D4, 0xD4D4D4)
        border = JBUI.Borders.empty(4, 8)
    }

    private val clearBtn = JButton(AllIcons.Actions.Refresh).apply {
        toolTipText = "Clear log"
        isContentAreaFilled = false
        isBorderPainted = false
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        addActionListener { DebugLog.clear(); rebuildText() }
    }

    private val collapseBtn = JButton(AllIcons.Actions.Preview).apply {
        toolTipText = "Collapse"
        isContentAreaFilled = false
        isBorderPainted = false
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        addActionListener { toggleCollapse() }
    }

    private var collapsed = false
    private val htmlBuilder = StringBuilder()

    init {
        val headerRight = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 2, 0)).apply {
            isOpaque = false
            add(clearBtn)
            add(collapseBtn)
        }
        headerPanel.add(headerLabel, BorderLayout.WEST)
        headerPanel.add(headerRight, BorderLayout.EAST)

        add(headerPanel, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)

        DebugLog.addListener { entry ->
            SwingUtilities.invokeLater {
                appendEntry(entry)
            }
        }
    }

    private fun appendEntry(entry: DebugLog.LogEntry) {
        val color = when (entry.level) {
            DebugLog.Level.DEBUG -> "#6A9955"
            DebugLog.Level.INFO -> "#D4D4D4"
            DebugLog.Level.WARN -> "#DCDCAA"
            DebugLog.Level.ERROR -> "#F14C4C"
        }
        val escaped = entry.message
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        val line = "<div style='color: $color; font-family: monospace; font-size: 11px; margin: 1px 0;'>${entry.format()}</div>"
        htmlBuilder.append(line)

        if (htmlBuilder.length > 50000) {
            rebuildText()
        } else {
            textPane.text = "<html><body style='background-color: #1E1E1E; font-family: monospace; font-size: 11px;'>${htmlBuilder}</body></html>"
            scrollPane.verticalScrollBar.value = scrollPane.verticalScrollBar.maximum
        }
    }

    private fun rebuildText() {
        htmlBuilder.clear()
        DebugLog.getEntries().forEach { entry ->
            val color = when (entry.level) {
                DebugLog.Level.DEBUG -> "#6A9955"
                DebugLog.Level.INFO -> "#D4D4D4"
                DebugLog.Level.WARN -> "#DCDCAA"
                DebugLog.Level.ERROR -> "#F14C4C"
            }
            val escaped = entry.message
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
            val line = "<div style='color: $color; font-family: monospace; font-size: 11px; margin: 1px 0;'>${entry.format()}</div>"
            htmlBuilder.append(line)
        }
        textPane.text = "<html><body style='background-color: #1E1E1E; font-family: monospace; font-size: 11px;'>${htmlBuilder}</body></html>"
        scrollPane.verticalScrollBar.value = scrollPane.verticalScrollBar.maximum
    }

    private fun toggleCollapse() {
        collapsed = !collapsed
        collapseBtn.icon = AllIcons.Actions.Preview
        collapseBtn.toolTipText = if (collapsed) "Expand" else "Collapse"
        scrollPane.isVisible = !collapsed
        revalidate()
        repaint()
    }
}
