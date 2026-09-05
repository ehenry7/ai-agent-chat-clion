package com.aiagent.chat.ui

import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Dimension
import javax.swing.JTextPane
import javax.swing.SwingUtilities
import javax.swing.text.View
import javax.swing.text.html.HTMLEditorKit

/**
 * Creates HTML-rendering JTextPanes with dynamic height sizing.
 *
 * The core problem: a bare JTextPane with contentType "text/html" computes its
 * preferred size based on its current width, which is 0 before the component is
 * added to a realized hierarchy. This causes the preferred size to collapse to
 * roughly 0x0, making content invisible inside chat bubbles.
 *
 * DynamicHeightTextPane solves this by:
 * 1. Measuring the HTML content at the actual available width (or a fallback)
 * 2. Returning a preferred size with the correct height for that width
 * 3. Re-measuring when the width changes (via setBounds invalidation)
 * 4. Invalidating the cache when text changes (for streaming)
 */
object HtmlPaneFactory {
    const val FALLBACK_WIDTH = 600

    /**
     * Inserts `<wbr>` (soft wrap opportunity) into long runs of non-space
     * characters so Swing's HTML renderer can wrap them. The built-in Swing
     * HTML engine cannot wrap an unbroken token (a long JSON blob, URL, or
     * base64 string) even with `word-wrap: break-word`; such content stays on
     * a single line, overflows the pane, and ends up invisible. Inserting
     * `<wbr>` every [every] characters gives the flow layout wrap points.
     *
     * HTML entities (`&amp;`, `&lt;`, ...) are treated as atomic units so a
     * split point never lands inside an entity.
     */
    internal fun insertWbr(text: String, every: Int = 40): String {
        if (text.length <= every) return text
        val sb = StringBuilder(text.length + text.length / every * 5)
        var runChars = 0
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '&') {
                val semi = text.indexOf(';', i + 1)
                if (semi in (i + 1)..(i + 12)) {
                    // Append the entity atomically, counting its raw length
                    // toward the break threshold so a break is placed at the
                    // next entity boundary once the limit is reached — but a
                    // break can never land inside an entity.
                    sb.append(text, i, semi + 1)
                    runChars += semi + 1 - i
                    val moreAhead = semi + 1 < text.length && !text[semi + 1].isWhitespace()
                    if (runChars >= every && moreAhead) {
                        sb.append("<wbr>")
                        runChars = 0
                    }
                    i = semi + 1
                    continue
                }
            }
            when {
                ch == ' ' || ch.isWhitespace() -> {
                    sb.append(ch)
                    runChars = 0
                }
                else -> {
                    sb.append(ch)
                    runChars++
                    // Only place a break when the run continues with more
                    // non-space content, so no stray trailing <wbr> is emitted.
                    val moreAhead = i + 1 < text.length && !text[i + 1].isWhitespace()
                    if (runChars >= every && moreAhead) {
                        sb.append("<wbr>")
                        runChars = 0
                    }
                }
            }
            i++
        }
        return sb.toString()
    }

    fun createHtmlPane(
        htmlBody: String,
        bgColor: Color,
        fgColor: Color? = null,
        border: javax.swing.border.Border = JBUI.Borders.empty(2, 0)
    ): JTextPane {
        com.aiagent.chat.debug.DebugLog.info("HtmlPane", "createHtmlPane: htmlBody.length=${htmlBody.length}, bgColor=$bgColor, fgColor=$fgColor, border=$border")
        return DynamicHeightTextPane().apply {
            contentType = "text/html"
            editorKit = HTMLEditorKit()
            isEditable = false
            background = bgColor
            fgColor?.let { foreground = it }
            putClientProperty(JTextPane.HONOR_DISPLAY_PROPERTIES, true)
            val colorStyle = if (fgColor != null) {
                "color: #%06X; ".format(fgColor.rgb and 0xFFFFFF)
            } else {
                ""
            }
            text = "<html><body style='font-family: sans-serif; font-size: 12px; $colorStyle word-wrap: break-word;'>" +
                    htmlBody + "</body></html>"
            this.border = border
            com.aiagent.chat.debug.DebugLog.info(
                "HtmlPane",
                "createHtmlPane: pane created class=${this.javaClass.simpleName}, font=${font}, foreground=$foreground, background=$background, document.length=${document.length}, isDisplayable=${isDisplayable}, isEDT=${SwingUtilities.isEventDispatchThread()}"
            )
        }
    }
}

/**
 * A JTextPane that dynamically computes its preferred height based on the
 * available width. Solves the Swing HTML sizing problem where JTextPane
 * returns 0x0 preferred size before being added to a realized hierarchy,
 * and where a fixed preferred width prevents proper layout in containers
 * that are narrower or wider than the hardcoded measurement width.
 */
class DynamicHeightTextPane : JTextPane() {
    private var measuredWidth = -1
    private var measuredHeight = 24
    private var measuring = false
    private var lastMeasureLog = 0L

    override fun getPreferredSize(): Dimension {
        val targetWidth = determineWidth()
        val now = System.currentTimeMillis()
        if (targetWidth != measuredWidth) {
            com.aiagent.chat.debug.DebugLog.info("DynamicHeightTextPane", "getPreferredSize: re-measuring targetWidth=$targetWidth (cached was $measuredWidth, height=$measuredHeight), isEDT=${SwingUtilities.isEventDispatchThread()}")
            reMeasure(targetWidth)
        } else if (now - lastMeasureLog > 1000) {
            com.aiagent.chat.debug.DebugLog.info("DynamicHeightTextPane", "getPreferredSize: cached width=$measuredWidth height=$measuredHeight, docLen=${document.length}, w=$width h=$height")
            lastMeasureLog = now
        }
        // Return width=0 so that layout managers (BoxLayout, BorderLayout) don't
        // use our preferred width for horizontal sizing.  The maximum size width
        // is Integer.MAX_VALUE, so the component can still stretch to fill.
        // This prevents the text pane from forcing the container to be as wide
        // as the measured width, which causes clipping when insets reduce the
        // actual allocated width.
        return Dimension(0, measuredHeight)
    }

    override fun getMaximumSize(): Dimension {
        return Dimension(Integer.MAX_VALUE, measuredHeight)
    }

    override fun getMinimumSize(): Dimension {
        return Dimension(0, measuredHeight)
    }

    override fun setText(t: String) {
        com.aiagent.chat.debug.DebugLog.info("DynamicHeightTextPane", "setText: t.length=${t?.length}, prev measuredWidth=$measuredWidth, isEDT=${SwingUtilities.isEventDispatchThread()}")
        super.setText(t)
        measuredWidth = -1 // invalidate cache -- new content may have different height
    }

    override fun setBounds(x: Int, y: Int, width: Int, height: Int) {
        super.setBounds(x, y, width, height)
        if (measuring) return
        // Re-measure synchronously when the allocated width changes so that
        // getPreferredSize() returns the correct height on the very next call.
        // Only trigger a revalidate if the height actually changed, to avoid
        // infinite layout cycles.
        if (width > 0 && width != measuredWidth) {
            val oldHeight = measuredHeight
            com.aiagent.chat.debug.DebugLog.info("DynamicHeightTextPane", "setBounds($x,$y,$width,$height): width changed from $measuredWidth, oldHeight=$oldHeight, isEDT=${SwingUtilities.isEventDispatchThread()}")
            reMeasure(width)
            if (measuredHeight != oldHeight) {
                SwingUtilities.invokeLater { revalidate() }
            }
        }
    }

    private fun determineWidth(): Int {
        val w = when {
            width > 0 -> width
            parent != null && parent.width > 0 -> {
                // Account for parent insets so we measure at the actual
                // content width, not the full parent width.  Without this,
                // the measured height is too small for the narrower actual
                // allocation, causing text to be clipped at the bottom.
                val insets = parent.insets
                (parent.width - insets.left - insets.right).coerceAtLeast(1)
            }
            else -> HtmlPaneFactory.FALLBACK_WIDTH
        }
        com.aiagent.chat.debug.DebugLog.info("DynamicHeightTextPane", "determineWidth -> $w (this.width=$width, parent=${parent?.javaClass?.simpleName} parent.width=${parent?.width})")
        return w
    }

    private fun reMeasure(targetWidth: Int) {
        measuring = true
        try {
            // Lay out the HTML view at exactly targetWidth and read back the true
            // flow height. This is deterministic and independent of the component's
            // transient size state. The previous approach (super.setSize +
            // super.getPreferredSize) collapsed long multi-line content to a single
            // ~24px line in the IDE, which made message text invisible inside bubbles.
            val textUi = ui as? javax.swing.plaf.basic.BasicTextUI
                ?: return
            val rootView = textUi.getRootView(this)
            rootView.setSize(targetWidth.toFloat(), Short.MAX_VALUE.toFloat())
            val span = rootView.getPreferredSpan(View.Y_AXIS)
            // Add 6px breathing room so the last line is never clipped.
            measuredHeight = (span + 6f).toInt().coerceAtLeast(24)
            measuredWidth = targetWidth
            com.aiagent.chat.debug.DebugLog.info("DynamicHeightTextPane", "reMeasure(targetWidth=$targetWidth): rootView=${rootView.javaClass.simpleName}, span(Y)=$span -> measuredHeight=$measuredHeight, docLen=${document.length}")
        } finally {
            measuring = false
        }
    }
}
