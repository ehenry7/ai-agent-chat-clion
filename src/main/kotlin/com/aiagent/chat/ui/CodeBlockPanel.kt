package com.aiagent.chat.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileTypes.FileTypes
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.EditorTextField
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
import javax.swing.JPanel

/**
 * Panel for rendering a syntax-highlighted code block using IntelliJ's EditorTextField.
 * Replaces the plain HTML <pre> rendering in ResponseMessagePanel.
 *
 * Phase 2: Rich Response Rendering with code editors.
 * Inspired by ProxyAI's ChatMessageResponseBody code segment rendering.
 */
class CodeBlockPanel(
    private val project: Project,
    private val code: String,
    private val language: String = "text",
    private val filePath: String? = null
) : JBPanel<CodeBlockPanel>(BorderLayout()) {

    init {
        border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border(), 1),
            JBUI.Borders.empty(0)
        )
        background = JBColor(0x2B2B2B, 0x1E1E1E)

        buildHeader()
        buildEditor()
    }

    private fun buildHeader() {
        val header = JPanel(BorderLayout())
        header.isOpaque = true
        header.background = JBColor(0x3C3F41, 0x2D2D2D)
        header.border = JBUI.Borders.empty(4, 8)

        // Left: language label / file path
        val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
        }

        val labelText = filePath ?: language
        val langLabel = JBLabel(labelText).apply {
            font = font.deriveFont(java.awt.Font.BOLD, 11f)
            foreground = JBColor(0xCCCCCC, 0xAAAAAA)
            icon = AllIcons.FileTypes.Any_type
        }
        leftPanel.add(langLabel)

        header.add(leftPanel, BorderLayout.WEST)

        // Right: copy button
        val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0)).apply {
            isOpaque = false
        }

        val copyBtn = JButton(AllIcons.Actions.Copy).apply {
            toolTipText = "Copy code"
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusPainted = false
            margin = JBUI.insets(2)
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    val selection = StringSelection(code)
                    Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, null)
                }
            })
        }
        rightPanel.add(copyBtn)
        header.add(rightPanel, BorderLayout.EAST)

        add(header, BorderLayout.NORTH)
    }

    private fun buildEditor() {
        val fileType = when (language.lowercase()) {
            "kotlin", "kt", "kts" -> try {
                com.intellij.openapi.fileTypes.FileTypeManager.getInstance().getFileTypeByExtension("kt")
            } catch (_: Exception) { FileTypes.PLAIN_TEXT }
            "java" -> try {
                com.intellij.openapi.fileTypes.FileTypeManager.getInstance().getFileTypeByExtension("java")
            } catch (_: Exception) { FileTypes.PLAIN_TEXT }
            "python", "py" -> try {
                com.intellij.openapi.fileTypes.FileTypeManager.getInstance().getFileTypeByExtension("py")
            } catch (_: Exception) { FileTypes.PLAIN_TEXT }
            "javascript", "js" -> try {
                com.intellij.openapi.fileTypes.FileTypeManager.getInstance().getFileTypeByExtension("js")
            } catch (_: Exception) { FileTypes.PLAIN_TEXT }
            "typescript", "ts" -> try {
                com.intellij.openapi.fileTypes.FileTypeManager.getInstance().getFileTypeByExtension("ts")
            } catch (_: Exception) { FileTypes.PLAIN_TEXT }
            "json" -> try {
                com.intellij.openapi.fileTypes.FileTypeManager.getInstance().getFileTypeByExtension("json")
            } catch (_: Exception) { FileTypes.PLAIN_TEXT }
            "xml" -> try {
                com.intellij.openapi.fileTypes.FileTypeManager.getInstance().getFileTypeByExtension("xml")
            } catch (_: Exception) { FileTypes.PLAIN_TEXT }
            "bash", "sh", "shell" -> try {
                com.intellij.openapi.fileTypes.FileTypeManager.getInstance().getFileTypeByExtension("sh")
            } catch (_: Exception) { FileTypes.PLAIN_TEXT }
            "sql" -> try {
                com.intellij.openapi.fileTypes.FileTypeManager.getInstance().getFileTypeByExtension("sql")
            } catch (_: Exception) { FileTypes.PLAIN_TEXT }
            "html" -> try {
                com.intellij.openapi.fileTypes.FileTypeManager.getInstance().getFileTypeByExtension("html")
            } catch (_: Exception) { FileTypes.PLAIN_TEXT }
            "css" -> try {
                com.intellij.openapi.fileTypes.FileTypeManager.getInstance().getFileTypeByExtension("css")
            } catch (_: Exception) { FileTypes.PLAIN_TEXT }
            "c", "cpp", "h", "hpp" -> try {
                com.intellij.openapi.fileTypes.FileTypeManager.getInstance().getFileTypeByExtension("cpp")
            } catch (_: Exception) { FileTypes.PLAIN_TEXT }
            "cmake" -> try {
                com.intellij.openapi.fileTypes.FileTypeManager.getInstance().getFileTypeByExtension("cmake")
            } catch (_: Exception) { FileTypes.PLAIN_TEXT }
            "gradle" -> try {
                com.intellij.openapi.fileTypes.FileTypeManager.getInstance().getFileTypeByExtension("gradle")
            } catch (_: Exception) { FileTypes.PLAIN_TEXT }
            else -> FileTypes.PLAIN_TEXT
        }

        val document = EditorFactory.getInstance().createDocument(code)
        val editorField = EditorTextField(document, project, fileType, false, false).apply {
            isViewer = true
            setOneLineMode(false)
            border = JBUI.Borders.empty(4, 8)
            background = JBColor(0x2B2B2B, 0x1E1E1E)
        }

        add(editorField, BorderLayout.CENTER)
    }

    companion object {
        /**
         * Parses markdown text and returns a list of segments: either plain text
         * (rendered as HTML) or code blocks (rendered as CodeBlockPanel).
         */
        fun parseSegments(text: String): List<ResponseSegment> {
            val segments = mutableListOf<ResponseSegment>()
            val blocks = text.split("```")

            for (i in blocks.indices) {
                if (i % 2 == 1) {
                    // Code block
                    val raw = blocks[i]
                    val firstNewline = raw.indexOf("\n")
                    val lang = if (firstNewline > 0) raw.substring(0, firstNewline).trim() else ""
                    val codeContent = if (firstNewline > 0) raw.substring(firstNewline + 1) else raw
                    segments.add(ResponseSegment.Code(codeContent, lang.ifEmpty { "text" }))
                } else {
                    if (blocks[i].isNotBlank()) {
                        segments.add(ResponseSegment.Text(blocks[i]))
                    }
                }
            }

            return segments
        }
    }

    sealed class ResponseSegment {
        data class Text(val content: String) : ResponseSegment()
        data class Code(val content: String, val language: String) : ResponseSegment()
    }
}
