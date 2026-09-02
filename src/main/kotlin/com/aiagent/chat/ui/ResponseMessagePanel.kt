package com.aiagent.chat.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.Icon
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.text.html.HTMLEditorKit

/**
 * Panel for rendering assistant responses with rich markdown-to-HTML rendering.
 * Supports code blocks (rendered with IntelliJ editor via CodeBlockPanel),
 * inline formatting, tables, and headings.
 *
 * Phase 2: Rich Response Rendering with code editors.
 */
class ResponseMessagePanel(
    private val messageText: String,
    private val project: Project? = null
) : BaseMessagePanel("Assistant", "assistant") {

    override fun getRoleIcon(): Icon = AllIcons.General.Balloon

    override fun getBubbleBackground(): JBColor = JBColor(0xFAFAFA, 0x232527)

    override fun buildBody() {
        val wrapper = JPanel()
        wrapper.isOpaque = false
        wrapper.layout = javax.swing.BoxLayout(wrapper, javax.swing.BoxLayout.Y_AXIS)

        if (project != null) {
            // Use segment-based rendering with CodeBlockPanel for code blocks
            val segments = CodeBlockPanel.parseSegments(messageText)
            for (segment in segments) {
                when (segment) {
                    is CodeBlockPanel.ResponseSegment.Text -> {
                        val textPane = createTextPane(renderMarkdown(segment.content))
                        wrapper.add(textPane)
                    }
                    is CodeBlockPanel.ResponseSegment.Code -> {
                        val codePanel = CodeBlockPanel(project, segment.content, segment.language)
                        wrapper.add(codePanel)
                    }
                }
            }
        } else {
            // Fallback: pure HTML rendering (no editor)
            val editorPane = JEditorPane().apply {
                contentType = "text/html"
                editorKit = HTMLEditorKit()
                isEditable = false
                background = background
                putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
                text = "<html><body style='font-family: sans-serif; font-size: 12px; word-wrap: break-word;'>" +
                        renderMarkdown(messageText) + "</body></html>"
                border = JBUI.Borders.empty(2, 0)
            }
            wrapper.add(editorPane)
        }

        setBodyContent(wrapper)
    }

    private fun createTextPane(htmlContent: String): JTextPane {
        return JTextPane().apply {
            contentType = "text/html"
            editorKit = HTMLEditorKit()
            isEditable = false
            background = background
            putClientProperty(JTextPane.HONOR_DISPLAY_PROPERTIES, true)
            text = "<html><body style='font-family: sans-serif; font-size: 12px; word-wrap: break-word;'>" +
                    htmlContent + "</body></html>"
            border = JBUI.Borders.empty(2, 0)
            alignmentX = JPanel.LEFT_ALIGNMENT
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        }
    }

    override fun getPlainText(): String = messageText

    /**
     * Renders markdown to HTML with support for:
     * - Fenced code blocks (```) - rendered as placeholder, actual code in CodeBlockPanel
     * - Bold (**text**), italic (*text*), inline code (`text`)
     * - Headings (#, ##, ###)
     * - Bullet lists (- item)
     * - Tables (| col | col |)
     */
    private fun renderMarkdown(text: String): String {
        val escaped = text.replace("<", "&lt;").replace(">", "&gt;")
        val sb = StringBuilder()

        val lines = escaped.split("\n")
        var inTable = false

        for (line in lines) {
            val tLine = line.trim()

            if (tLine.startsWith("|") && tLine.endsWith("|")) {
                if (!inTable) {
                    sb.append("<table border='1' style='border-collapse: collapse; margin: 8px 0; width: 100%; font-size: 11px;'>")
                    inTable = true
                }
                // Skip separator rows
                if (tLine.replace(Regex("[|\\- ]"), "").isEmpty()) continue

                sb.append("<tr>")
                val cells = tLine.removePrefix("|").removeSuffix("|").split("|")
                for (cell in cells) {
                    sb.append("<td style='padding: 4px 8px; border: 1px solid #777777;'>${cell.trim()}</td>")
                }
                sb.append("</tr>")
            } else {
                if (inTable) {
                    sb.append("</table>")
                    inTable = false
                }

                when {
                    tLine.startsWith("### ") -> sb.append("<h3 style='margin: 8px 0 4px 0; font-size: 13px;'>${tLine.substring(4)}</h3>")
                    tLine.startsWith("## ") -> sb.append("<h2 style='margin: 8px 0 4px 0; font-size: 14px;'>${tLine.substring(3)}</h2>")
                    tLine.startsWith("# ") -> sb.append("<h1 style='margin: 8px 0 4px 0; font-size: 16px;'>${tLine.substring(2)}</h1>")
                    tLine.startsWith("- ") -> sb.append("<div style='margin: 2px 0;'>&#8226; ${tLine.substring(2)}</div>")
                    tLine.startsWith("  - ") -> sb.append("<div style='margin: 2px 0 16px;'>&#8226; ${tLine.trim().substring(2)}</div>")
                    tLine.isEmpty() -> { /* skip empty lines to reduce spacing */ }
                    else -> {
                        var content = line
                        // Inline formatting
                        content = content.replace(Regex("\\*\\*([^*]+)\\*\\*"), "<b>$1</b>")
                        content = content.replace(Regex("\\*([^*]+)\\*"), "<i>$1</i>")
                        content = content.replace(Regex("`([^`]+)`"), "<code style='background-color: #e8e8e8; padding: 1px 3px; border-radius: 2px;'>$1</code>")
                        sb.append("<div style='margin: 2px 0;'>$content</div>")
                    }
                }
            }
        }
        if (inTable) sb.append("</table>")
        return sb.toString()
    }
}
