package com.aiagent.chat.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.Icon
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.SwingUtilities
import javax.swing.text.html.HTMLEditorKit

/**
 * Panel that renders streaming assistant text token-by-token as it arrives via SSE.
 * When streaming completes, it can be swapped out for a full ResponseMessagePanel
 * (with code block rendering) by calling [finalize].
 *
 * Phase 9: SSE Streaming Support.
 */
class StreamingResponsePanel(
    private val project: Project? = null
) : BaseMessagePanel("Assistant", "assistant") {

    private val textBuilder = StringBuilder()
    private var isStreaming = true

    private lateinit var textPane: DynamicHeightTextPane
    private lateinit var cursorLabel: JLabel
    private var cursorBlinkTimer: javax.swing.Timer? = null

    init {
        // buildBody() must be called from the subclass init{} block, not from
        // BaseMessagePanel.init{}, so that all subclass properties are initialized.
        buildBody()
    }

    override fun getRoleIcon(): Icon = AllIcons.General.Balloon

    override fun getBubbleBackground(): JBColor = JBColor(0xFAFAFA, 0x232527)

    override fun buildBody() {
        textPane = DynamicHeightTextPane().apply {
            contentType = "text/html"
            editorKit = HTMLEditorKit()
            isEditable = false
            background = this@StreamingResponsePanel.background
            putClientProperty(JTextPane.HONOR_DISPLAY_PROPERTIES, true)
            border = JBUI.Borders.empty(2, 0)
        }

        cursorLabel = JLabel("\u25AE").apply {
            font = font.deriveFont(java.awt.Font.BOLD, 13f)
            foreground = JBColor(0x0066CC, 0x4A9EFF)
        }

        startCursorBlink()

        val wrapper = JPanel(BorderLayout())
        wrapper.isOpaque = false

        // Text pane fills the full width (no JScrollPane wrapper -- the parent
        // conversation already has a scroll pane, and wrapping in another one
        // clips content and breaks width tracking).
        wrapper.add(textPane, BorderLayout.CENTER)

        // Cursor label below the text (not to the right, which steals horizontal
        // space from the streaming content).
        val cursorPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(cursorLabel)
        }
        wrapper.add(cursorPanel, BorderLayout.SOUTH)

        setBodyContent(wrapper)
    }

    /**
     * Append a streaming text chunk and re-render the HTML.
     * Must be called on the EDT.
     */
    fun appendText(text: String) {
        SwingUtilities.invokeLater {
            if (!isStreaming) return@invokeLater
            textBuilder.append(text)
            renderHtml()
            scrollParentToBottom()
        }
    }

    /**
     * Finalize streaming: stop the cursor blink and replace this panel
     * with a full ResponseMessagePanel in the parent container.
     * Returns the replacement panel (or null if parent is not available).
     */
    fun finalize(): ResponseMessagePanel? {
        SwingUtilities.invokeLater {
            isStreaming = false
            stopCursorBlink()
        }

        val fullText = textBuilder.toString()
        val replacement = ResponseMessagePanel(fullText, project)

        SwingUtilities.invokeLater {
            val parent = parent
            if (parent is JPanel) {
                // Find the GridBagConstraints or layout info
                val layout = parent.layout
                var constraints: Any? = null
                if (layout is java.awt.GridBagLayout) {
                    constraints = layout.getConstraints(this@StreamingResponsePanel)
                }

                parent.remove(this@StreamingResponsePanel)
                if (constraints != null) {
                    parent.add(replacement, constraints)
                } else {
                    parent.add(replacement)
                }
                parent.revalidate()
                parent.repaint()
            }
        }

        return replacement
    }

    private fun renderHtml() {
        val html = renderStreamingMarkdown(textBuilder.toString())
        textPane.text = "<html><body style='font-family: sans-serif; font-size: 12px; word-wrap: break-word;'>" +
                html + "</body></html>"
        // Trigger revalidate so DynamicHeightTextPane recalculates its height
        // after the content changes. Without this, the panel doesn't grow
        // as new tokens arrive during streaming.
        textPane.revalidate()
    }

    private fun scrollParentToBottom() {
        var parent = parent
        while (parent != null) {
            if (parent is javax.swing.JScrollPane) {
                val bar = parent.verticalScrollBar
                bar.value = bar.maximum
                break
            }
            parent = parent.parent
        }
    }

    private fun startCursorBlink() {
        cursorBlinkTimer = javax.swing.Timer(500) {
            cursorLabel.isVisible = !cursorLabel.isVisible
        }
        cursorBlinkTimer?.start()
    }

    private fun stopCursorBlink() {
        cursorBlinkTimer?.stop()
        cursorBlinkTimer = null
        cursorLabel.isVisible = false
    }

    override fun getPlainText(): String = textBuilder.toString()

    /**
     * Simplified markdown rendering for streaming text.
     * Does not render code blocks (those are rendered after finalization).
     */
    private fun renderStreamingMarkdown(text: String): String {
        val escaped = text.replace("<", "&lt;").replace(">", "&gt;")
        val sb = StringBuilder()

        val lines = escaped.split("\n")
        for (line in lines) {
            val tLine = line.trim()
            when {
                tLine.startsWith("### ") -> sb.append("<h3 style='margin: 8px 0 4px 0; font-size: 13px;'>${tLine.substring(4)}</h3>")
                tLine.startsWith("## ") -> sb.append("<h2 style='margin: 8px 0 4px 0; font-size: 14px;'>${tLine.substring(3)}</h2>")
                tLine.startsWith("# ") -> sb.append("<h1 style='margin: 8px 0 4px 0; font-size: 16px;'>${tLine.substring(2)}</h1>")
                tLine.startsWith("- ") -> sb.append("<div style='margin: 2px 0;'>&#8226; ${tLine.substring(2)}</div>")
                tLine.isEmpty() -> sb.append("<br>")
                else -> {
                    var content = line
                    content = content.replace(Regex("\\*\\*([^*]+)\\*\\*"), "<b>$1</b>")
                    content = content.replace(Regex("\\*([^*]+)\\*"), "<i>$1</i>")
                    content = content.replace(Regex("`([^`]+)`"), "<code style='background-color: #e8e8e8; padding: 1px 3px; border-radius: 2px;'>$1</code>")
                    sb.append("<div style='margin: 2px 0;'>$content</div>")
                }
            }
        }
        return sb.toString()
    }
}
