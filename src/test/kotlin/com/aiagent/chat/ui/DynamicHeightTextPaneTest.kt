package com.aiagent.chat.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import javax.swing.JTextPane
import javax.swing.text.html.HTMLEditorKit

/**
 * Regression tests for DynamicHeightTextPane height measurement.
 *
 * The pane previously collapsed multi-line HTML to a single ~24px line in the
 * IDE, which made message text invisible inside chat bubbles. These tests
 * verify the measured height actually reflects wrapped content flow.
 */
class DynamicHeightTextPaneTest {

    private fun htmlPane(body: String, fgColor: String = "#333333"): DynamicHeightTextPane {
        return DynamicHeightTextPane().apply {
            contentType = "text/html"
            editorKit = HTMLEditorKit()
            isEditable = false
            text = "<html><body style='font-family: sans-serif; font-size: 12px; color: $fgColor;'>" +
                    body + "</body></html>"
        }
    }

    @Test
    fun `long wrapped prose measures much taller than one line`() {
        val short = htmlPane("<div>one line of text</div>")
        val prose = (1..40).joinToString(" ") { "word number $it which is fairly long" }
        val longPane = htmlPane("<div>$prose</div>")

        val shortH = short.preferredSize.height
        val longH = longPane.preferredSize.height
        // A 40-word sentence at ~600px fallback width wraps to many lines.
        assertTrue("expected long prose ($longH) to be taller than one line ($shortH)", longH > shortH * 2)
        assertTrue("long prose should be at least 80px, was $longH", longH > 80)
    }

    @Test
    fun `wbr-divided unbroken token measures tall instead of collapsing`() {
        // A long unbroken token cannot be wrapped by Swing's HTML renderer on
        // its own; once <wbr> opportunities are inserted it must flow to a
        // real height (the exact case that produced invisible text).
        val token = "a".repeat(400)
        val body = "<div>${HtmlPaneFactory.insertWbr(token)}</div>"
        val pane = htmlPane(body)
        val h = pane.preferredSize.height
        assertTrue("unbroken token should wrap to several lines, was $h", h > 100)
    }

    @Test
    fun `short content stays at one-line height`() {
        val pane = htmlPane("<div>hi</div>")
        val h = pane.preferredSize.height
        assertTrue("short content measured $h", h < 60)
    }
}
