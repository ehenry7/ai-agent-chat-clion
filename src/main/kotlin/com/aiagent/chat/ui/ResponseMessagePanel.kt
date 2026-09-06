package com.aiagent.chat.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.ScrollPaneConstants
import javax.swing.JScrollPane
import javax.swing.SwingUtilities

/**
 * Panel for rendering assistant responses with rich markdown-to-HTML rendering.
 * Supports code blocks (rendered with IntelliJ editor via CodeBlockPanel),
 * inline formatting, tables, and headings.
 *
 * Phase 2: Rich Response Rendering with code editors.
 */
class ResponseMessagePanel(
    private val initialMessageText: String,
    private val project: Project? = null,
    private val thinkingText: String = ""
) : BaseMessagePanel("Assistant", "assistant") {

    /** Accumulated message text (for copy-to-clipboard). */
    private val allMessageText = StringBuilder(initialMessageText)

    /** Collapsible tool calls section embedded in this assistant bubble. */
    private var toolCallsSection: ToolCallsSection? = null
    private var bodyWrapper: JPanel? = null

    override fun getRoleIcon(): Icon = AllIcons.General.Balloon

    override fun getBubbleBackground(): JBColor = JBColor(0xFAFAFA, 0x232527)

    init {
        buildBody()
    }

    /**
     * Add a tool call result to this assistant bubble's tool calls section.
     * The section auto-expands while tools are running.
     */
    fun addToolCall(name: String, output: String, status: ToolCallCard.ToolStatus, autoExpand: Boolean = true) {
        SwingUtilities.invokeLater {
            if (toolCallsSection == null) {
                toolCallsSection = ToolCallsSection()
                toolCallsSection!!.alignmentX = JPanel.LEFT_ALIGNMENT
                // Insert at the top of the wrapper (after thinking pane if present,
                // but before all text/code content) so tool calls appear at the
                // very top of the assistant bubble.
                val insertIndex = if (thinkingText.isNotBlank()) 1 else 0
                bodyWrapper?.add(toolCallsSection, insertIndex)
            }
            toolCallsSection!!.addToolCall(name, output, status, autoExpand = autoExpand)
            bodyWrapper?.revalidate()
            bodyWrapper?.repaint()
        }
    }

    /**
     * Append a new assistant message to this bubble, separated by a bullet divider.
     * Consecutive assistant messages are combined into one bubble.
     */
    fun appendMessage(text: String) {
        SwingUtilities.invokeLater {
            allMessageText.append("\n\n").append(text)

            // No visual separator between consecutive assistant messages — just append content directly

            // Add the new message content
            if (project != null) {
                val segments = CodeBlockPanel.parseSegments(text)
                for (segment in segments) {
                    when (segment) {
                        is CodeBlockPanel.ResponseSegment.Text -> {
                            val html = renderMarkdown(segment.content)
                            val textPane = createTextPane(html)
                            textPane.alignmentX = JPanel.LEFT_ALIGNMENT
                            bodyWrapper?.add(textPane)
                        }
                        is CodeBlockPanel.ResponseSegment.Code -> {
                            val codePanel = CodeBlockPanel(project, segment.content, segment.language)
                            codePanel.alignmentX = JPanel.LEFT_ALIGNMENT
                            bodyWrapper?.add(codePanel)
                        }
                    }
                }
            } else {
                val editorPane = HtmlPaneFactory.createHtmlPane(
                    htmlBody = renderMarkdown(text),
                    bgColor = background,
                    fgColor = JBColor(0x333333, 0xDDDDDD)
                )
                editorPane.alignmentX = JPanel.LEFT_ALIGNMENT
                bodyWrapper?.add(editorPane)
            }

            bodyWrapper?.revalidate()
            bodyWrapper?.repaint()
        }
    }

    /**
     * Collapse the tool calls section to a single "Tool Calls (N)" line.
     * Call when all tools are done and the final assistant response is displayed.
     */
    fun collapseToolCalls() {
        SwingUtilities.invokeLater {
            toolCallsSection?.collapse()
        }
    }

    override fun buildBody() {
        try {
            val wrapper = JPanel()
            wrapper.isOpaque = false
            wrapper.layout = BoxLayout(wrapper, BoxLayout.Y_AXIS)
            bodyWrapper = wrapper

            // Thinking/reasoning section (muted, smaller font, distinct color)
            if (thinkingText.isNotBlank()) {
                val escaped = thinkingText
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\n", "<br>")
                val thinkingHtml = "<html><body style='font-family: monospace; font-size: 11px; " +
                        "color: #888888; font-style: italic; word-wrap: break-word;'>" +
                        "<b style='font-size: 10px; color: #999999;'>Thinking</b><br>" +
                        escaped + "</body></html>"
                val thinkingPane = HtmlPaneFactory.createHtmlPane(
                    htmlBody = thinkingHtml,
                    bgColor = background,
                    fgColor = JBColor(0x888888, 0x777777)
                )
                thinkingPane.alignmentX = JPanel.LEFT_ALIGNMENT
                wrapper.add(thinkingPane)
            }

            if (project != null) {
                // Use segment-based rendering with CodeBlockPanel for code blocks
                val segments = CodeBlockPanel.parseSegments(initialMessageText)
                for ((index, segment) in segments.withIndex()) {
                    when (segment) {
                        is CodeBlockPanel.ResponseSegment.Text -> {
                            val html = renderMarkdown(segment.content)
                            val textPane = createTextPane(html)
                            // Let the DynamicHeightTextPane compute its own height.
                            // Don't set maximumSize here — the stale preferredSize.height
                            // captured before layout causes clipping. The text pane's
                            // own getMaximumSize() already returns the correct height.
                            textPane.alignmentX = JPanel.LEFT_ALIGNMENT
                            wrapper.add(textPane)
                        }
                        is CodeBlockPanel.ResponseSegment.Code -> {
                            val codePanel = CodeBlockPanel(project, segment.content, segment.language)
                            codePanel.alignmentX = JPanel.LEFT_ALIGNMENT
                            wrapper.add(codePanel)
                        }
                    }
                }
            } else {
                // Fallback: pure HTML rendering (no editor)
                val editorPane = HtmlPaneFactory.createHtmlPane(
                    htmlBody = renderMarkdown(initialMessageText),
                    bgColor = background,
                    fgColor = JBColor(0x333333, 0xDDDDDD)
                )
                editorPane.alignmentX = JPanel.LEFT_ALIGNMENT
                wrapper.add(editorPane)
            }

            setBodyContent(wrapper)
        } catch (e: Exception) {
            com.aiagent.chat.debug.DebugLog.error("ResponseMessagePanel", "buildBody failed: ${e.message}", e)
            val fallbackWrapper = JPanel()
            fallbackWrapper.isOpaque = false
            val errorLabel = javax.swing.JLabel("Error rendering message: ${e.message}")
            fallbackWrapper.add(errorLabel)
            setBodyContent(fallbackWrapper)
        }
    }

    private fun createTextPane(htmlContent: String): JTextPane {
        val pane = HtmlPaneFactory.createHtmlPane(
            htmlBody = htmlContent,
            bgColor = background,
            fgColor = JBColor(0x333333, 0xDDDDDD)
        )
        return pane
    }

    override fun getPlainText(): String = allMessageText.toString()

    /**
     * Renders markdown to HTML with support for:
     * - Fenced code blocks (```) - rendered as placeholder, actual code in CodeBlockPanel
     * - Bold (**text**), italic (*text*), inline code (`text`)
     * - Headings (#, ##, ###)
     * - Bullet lists (- item)
     * - Tables (| col | col |)
     */
    private fun renderMarkdown(text: String?): String {
        if (text.isNullOrBlank()) return ""
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
                    sb.append("<td style='padding: 4px 8px; border: 1px solid #777777;'>${HtmlPaneFactory.insertWbr(cell.trim())}</td>")
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
                        var content = HtmlPaneFactory.insertWbr(line)
                        // Inline formatting
                        content = content.replace(Regex("\\*\\*([^*]+)\\*\\*"), "<b>$1</b>")
                        content = content.replace(Regex("\\*([^*]+)\\*"), "<i>$1</i>")
                        val codeColor = if (JBColor.isBright()) "#0066CC" else "#6CB6FF"
                        content = content.replace(Regex("`([^`]+)`"), "<code style='color: $codeColor; font-family: monospace;'>$1</code>")
                        sb.append("<div style='margin: 2px 0;'>$content</div>")
                    }
                }
            }
        }
        if (inTable) sb.append("</table>")
        return sb.toString()
    }
}
