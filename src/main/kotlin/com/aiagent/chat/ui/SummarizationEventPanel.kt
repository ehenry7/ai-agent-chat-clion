package com.aiagent.chat.ui

import com.aiagent.chat.agent.UsageTracker
import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

/**
 * Collapsible panel showing context compaction events.
 * Inspired by refact-main's SummarizationMessage component.
 *
 * Displays:
 * - Compaction tier badge (deterministic / LLM summary / reactive)
 * - Messages range compacted
 * - Estimated tokens saved
 * - Expandable details with full summary text
 */
class SummarizationEventPanel(
    private val event: UsageTracker.CompactionEvent
) : JPanel(BorderLayout()) {

    private var isExpanded = false
    private val contentPanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }

    init {
        isOpaque = false
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(ThemeUtils.SUBTLE_BORDER, 1),
            JBUI.Borders.empty(6, 10)
        )
        background = JBColor(0xF5F5F5, 0x2D2D2D)

        val header = JPanel(BorderLayout(6, 0)).apply {
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }

        val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply { isOpaque = false }
        leftPanel.add(JLabel(AllIcons.Actions.Preview).apply { preferredSize = Dimension(16, 16) })
        leftPanel.add(JLabel("Context Compacted").apply {
            font = font.deriveFont(Font.BOLD, 11f)
        })
        leftPanel.add(JLabel("${event.messagesBefore} -> ${event.messagesAfter} messages").apply {
            font = font.deriveFont(Font.PLAIN, 11f)
            foreground = JBColor.GRAY
        })
        leftPanel.add(JLabel("~${UsageCounterPanel.formatTokenCount(event.tokensSavedEstimate)} tokens saved").apply {
            font = font.deriveFont(Font.PLAIN, 11f)
            foreground = JBColor.GRAY
        })
        header.add(leftPanel, BorderLayout.WEST)

        val toggleBtn = JLabel("[+]").apply {
            font = font.deriveFont(Font.PLAIN, 10f)
            foreground = JBColor.GRAY
        }
        header.add(toggleBtn, BorderLayout.EAST)

        header.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                isExpanded = !isExpanded
                toggleBtn.text = if (isExpanded) "[-]" else "[+]"
                contentPanel.isVisible = isExpanded
                revalidate()
                repaint()
            }
        })

        add(header, BorderLayout.NORTH)

        // Details (hidden by default)
        contentPanel.isOpaque = false
        contentPanel.isVisible = false
        contentPanel.border = JBUI.Borders.emptyTop(6)

        contentPanel.add(JLabel("Compaction Details").apply {
            font = font.deriveFont(Font.BOLD, 11f)
            alignmentX = LEFT_ALIGNMENT
        })
        contentPanel.add(Box.createVerticalStrut(4))
        contentPanel.add(createDetailRow("Messages before", event.messagesBefore.toString()))
        contentPanel.add(createDetailRow("Messages after", event.messagesAfter.toString()))
        contentPanel.add(createDetailRow("Messages removed", (event.messagesBefore - event.messagesAfter).toString()))
        contentPanel.add(createDetailRow("Est. tokens saved", "~${UsageCounterPanel.formatTokenCount(event.tokensSavedEstimate)}"))

        add(contentPanel, BorderLayout.CENTER)
    }

    private fun createDetailRow(label: String, value: String): JComponent {
        val panel = JPanel(BorderLayout(8, 0)).apply {
            isOpaque = false
            preferredSize = Dimension(280, 18)
            maximumSize = Dimension(280, 18)
            alignmentX = LEFT_ALIGNMENT
        }
        panel.add(JLabel(label).apply {
            font = font.deriveFont(Font.PLAIN, 11f)
            foreground = JBColor.GRAY
        }, BorderLayout.WEST)
        panel.add(JLabel(value).apply {
            font = font.deriveFont(Font.PLAIN, 11f)
        }, BorderLayout.EAST)
        return panel
    }
}
