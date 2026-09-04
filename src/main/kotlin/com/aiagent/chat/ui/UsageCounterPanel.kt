package com.aiagent.chat.ui

import com.aiagent.chat.agent.UsageTracker
import com.aiagent.chat.model.TokenMap
import com.aiagent.chat.model.TokenMapSegment
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import javax.swing.*

/**
 * Compact usage counter widget for the chat footer.
 * Inspired by refact-main's UsageCounter component.
 *
 * Shows:
 * - Circular progress ring (context window utilization)
 * - Current token count
 * - Warning colors (yellow >= 85%, red >= 97%)
 * - Click to open detailed breakdown popup
 */
class UsageCounterPanel(
    private val maxContextTokens: Int = 32768
) : JPanel(BorderLayout()) {

    private val tokenLabel = JLabel("0")
    private val ringPanel = CircularProgressRing(18, 2.5f)
    private var currentTokens = 0
    private var currentPercentage = 0.0
    private var isWarning = false
    private var isOverflown = false
    private var currentTokenMap: TokenMap? = null

    // Detailed breakdown popup
    private var breakdownPopup: JPopupMenu? = null

    init {
        isOpaque = false
        border = JBUI.Borders.empty(2, 4)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)).apply {
            isOpaque = false
            add(ringPanel)
            add(tokenLabel)
        }
        add(leftPanel, BorderLayout.CENTER)

        toolTipText = "Context usage: 0%"

        // Click to show breakdown
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                showBreakdownPopup(e)
            }
        })
    }

    /**
     * Update the usage display with new data.
     */
    fun updateUsage(summary: UsageTracker.UsageSummary) {
        currentTokens = summary.currentSessionTokens
        currentPercentage = summary.percentage
        isWarning = summary.isWarning
        isOverflown = summary.isOverflown
        currentTokenMap = summary.tokenMap

        SwingUtilities.invokeLater {
            tokenLabel.text = formatTokenCount(currentTokens)
            ringPanel.update(currentTokens, maxContextTokens, isWarning, isOverflown)
            val pct = currentPercentage.toInt()
            toolTipText = "Context usage: $pct% (${formatTokenCount(currentTokens)} / ${formatTokenCount(maxContextTokens)})"
            revalidate()
            repaint()
        }
    }

    private fun showBreakdownPopup(e: MouseEvent) {
        val popup = JPopupMenu()
        popup.isOpaque = true
        popup.background = JBColor.PanelBackground

        // --- Summary tab content ---
        val summaryPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8)
            background = JBColor.PanelBackground
            preferredSize = Dimension(280, 200)
        }

        // Title
        summaryPanel.add(JLabel("Context Usage").apply {
            font = font.deriveFont(Font.BOLD, 13f)
            alignmentX = LEFT_ALIGNMENT
        })
        summaryPanel.add(Box.createVerticalStrut(6))

        val pct = if (maxContextTokens > 0) (currentTokens * 100 / maxContextTokens) else 0
        summaryPanel.add(createRow("Usage", "$pct%"))
        summaryPanel.add(createRow("Current", formatTokenCount(currentTokens)))
        summaryPanel.add(createRow("Maximum", formatTokenCount(maxContextTokens)))

        // Separator
        summaryPanel.add(Box.createVerticalStrut(4))
        summaryPanel.add(JSeparator().apply { alignmentX = LEFT_ALIGNMENT })
        summaryPanel.add(Box.createVerticalStrut(4))

        // Token breakdown
        val tokenMap = currentTokenMap
        if (tokenMap != null) {
            summaryPanel.add(JLabel("Token Breakdown").apply {
                font = font.deriveFont(Font.BOLD, 12f)
                alignmentX = LEFT_ALIGNMENT
            })
            summaryPanel.add(Box.createVerticalStrut(4))

            // Stacked bar
            summaryPanel.add(TokenBreakdownBar(tokenMap).apply { alignmentX = LEFT_ALIGNMENT })
            summaryPanel.add(Box.createVerticalStrut(6))

            // Category rows
            for (segment in tokenMap.segments) {
                if (segment.tokens > 0) {
                    summaryPanel.add(createCategoryRow(segment))
                }
            }

            // Total
            summaryPanel.add(Box.createVerticalStrut(4))
            summaryPanel.add(JSeparator().apply { alignmentX = LEFT_ALIGNMENT })
            summaryPanel.add(Box.createVerticalStrut(4))
            summaryPanel.add(createRow("Total / Max",
                "${formatTokenCount(tokenMap.totalPromptTokens)} / ${formatTokenCount(tokenMap.maxContextTokens)}"))
        } else {
            summaryPanel.add(JLabel("Send a message to see breakdown").apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(Font.ITALIC, 11f)
            })
        }

        popup.add(summaryPanel)
        popup.show(this, 0, -popup.preferredSize.height - 2)
        breakdownPopup = popup
    }

    private fun createRow(label: String, value: String): JComponent {
        val panel = JPanel(BorderLayout(8, 0)).apply {
            isOpaque = false
            preferredSize = Dimension(260, 20)
            maximumSize = Dimension(260, 20)
        }
        panel.add(JLabel(label).apply { font = font.deriveFont(Font.PLAIN, 11f) }, BorderLayout.WEST)
        panel.add(JLabel(value).apply { font = font.deriveFont(Font.PLAIN, 11f) }, BorderLayout.EAST)
        return panel
    }

    private fun createCategoryRow(segment: TokenMapSegment): JComponent {
        val panel = JPanel(BorderLayout(4, 0)).apply {
            isOpaque = false
            preferredSize = Dimension(260, 18)
            maximumSize = Dimension(260, 18)
        }
        val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)).apply { isOpaque = false }
        leftPanel.add(ColorDot(getCategoryColor(segment.category)))
        leftPanel.add(JLabel(segment.label).apply { font = font.deriveFont(Font.PLAIN, 11f) })
        panel.add(leftPanel, BorderLayout.WEST)

        val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0)).apply { isOpaque = false }
        rightPanel.add(JLabel(formatTokenCount(segment.tokens)).apply {
            font = font.deriveFont(Font.PLAIN, 11f)
            foreground = JBColor.GRAY
        })
        rightPanel.add(JLabel("(${String.format("%.1f", segment.percentage)}%)").apply {
            font = font.deriveFont(Font.PLAIN, 11f)
            foreground = JBColor.GRAY
        })
        panel.add(rightPanel, BorderLayout.EAST)
        return panel
    }

    companion object {
        fun formatTokenCount(tokens: Int): String {
            return when {
                tokens >= 1_000_000 -> String.format("%.1fM", tokens / 1_000_000.0)
                tokens >= 1_000 -> String.format("%.1fK", tokens / 1_000.0)
                else -> tokens.toString()
            }
        }

        fun getCategoryColor(category: String): Color {
            return when (category) {
                "system" -> Color(59, 130, 246)      // blue
                "user_messages" -> Color(249, 115, 22) // orange
                "assistant_messages" -> Color(6, 182, 212) // cyan
                "tool_results" -> Color(236, 72, 153)  // pink
                "free" -> JBColor.GRAY
                else -> JBColor.GRAY
            }
        }
    }
}

/**
 * Circular progress ring showing context window utilization.
 * Inspired by refact-main's CircularProgress SVG component.
 */
class CircularProgressRing(
    private val size: Int = 18,
    private val strokeWidth: Float = 2.5f
) : JComponent() {

    private var value = 0
    private var max = 1
    private var isWarning = false
    private var isOverflown = false

    init {
        preferredSize = Dimension(size, size)
        minimumSize = Dimension(size, size)
    }

    fun update(value: Int, max: Int, isWarning: Boolean, isOverflown: Boolean) {
        this.value = value
        this.max = max
        this.isWarning = isWarning
        this.isOverflown = isOverflown
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        val g2d = g.create() as Graphics2D
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val percentage = if (max > 0) minOf((value.toDouble() / max) * 100, 100.0) else 0.0
        val radius = (size - strokeWidth) / 2.0
        val cx = size / 2.0
        val cy = size / 2.0

        // Background circle
        g2d.color = JBColor(0xDDDDDD, 0x444444)
        g2d.stroke = BasicStroke(strokeWidth)
        g2d.draw(Ellipse2D.Double(cx - radius, cy - radius, radius * 2, radius * 2))

        // Progress arc
        if (percentage > 0) {
            val arcExtent = (percentage / 100.0 * 360.0).toFloat()
            g2d.color = when {
                isOverflown -> Color(239, 68, 68)   // red
                isWarning -> Color(245, 158, 11)    // amber
                else -> Color(59, 130, 246)         // blue
            }
            g2d.draw(Arc2D.Double(
                cx - radius, cy - radius, radius * 2, radius * 2,
                90.0, -arcExtent.toDouble(), Arc2D.OPEN
            ))
        }

        g2d.dispose()
    }
}

/**
 * Stacked horizontal bar showing token distribution by category.
 * Inspired by refact-main's SegmentBar component.
 */
class TokenBreakdownBar(
    private val tokenMap: TokenMap
) : JComponent() {

    init {
        preferredSize = Dimension(260, 16)
        maximumSize = Dimension(260, 16)
    }

    override fun paintComponent(g: Graphics) {
        val g2d = g.create() as Graphics2D
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val width = width - 1
        val height = height
        val maxTokens = tokenMap.maxContextTokens

        if (maxTokens <= 0) {
            g2d.dispose()
            return
        }

        var x = 0
        for (segment in tokenMap.segments) {
            if (segment.tokens <= 0) continue
            val segWidth = (segment.tokens.toDouble() / maxTokens * width).toInt()
            if (segWidth < 1) continue
            g2d.color = UsageCounterPanel.getCategoryColor(segment.category)
            g2d.fillRect(x, 0, segWidth, height)
            x += segWidth
        }

        // Border
        g2d.color = JBColor(0xCCCCCC, 0x555555)
        g2d.drawRect(0, 0, width, height - 1)

        g2d.dispose()
    }
}

/**
 * Small colored dot for category legend.
 */
class ColorDot(private val color: Color) : JComponent() {
    init {
        preferredSize = Dimension(8, 8)
        minimumSize = Dimension(8, 8)
    }

    override fun paintComponent(g: Graphics) {
        g.color = color
        g.fillOval(0, 0, 8, 8)
    }
}
