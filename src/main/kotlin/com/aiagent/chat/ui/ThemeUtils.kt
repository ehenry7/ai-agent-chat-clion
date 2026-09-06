package com.aiagent.chat.ui

import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.swing.JComponent
import javax.swing.UIManager

/**
 * Theme utilities for consistent styling across all UI components.
 * Provides color constants, helper methods for rounded corners, and
 * automatic theme-change listener registration.
 *
 * Colors are resolved from the active IntelliJ theme via JBColor.namedColor()
 * and EditorColorsManager, with hardcoded light/dark fallbacks. This ensures
 * the plugin adapts to custom themes (Darcula, high-contrast, etc.) instead
 * of using a fixed color scheme.
 *
 * Phase 10: Polish & Theme.
 */
object ThemeUtils {

    // --- Text colors (theme-aware) ---

    /** Primary text color for message content. */
    val PRIMARY_TEXT: JBColor = JBColor.namedColor("Label.foreground", JBColor(0x333333, 0xDDDDDD))

    /** Secondary/muted text for metadata, timestamps, hints. */
    val SECONDARY_TEXT: JBColor = JBColor.namedColor("Label.infoForeground", JBColor(0x666666, 0x999999))

    /** Error text color. */
    val ERROR_TEXT: JBColor = JBColor.namedColor("Label.errorForeground", JBColor(0xCC0000, 0xFF6666))

    /** Muted text for thinking/reasoning sections. */
    val MUTED_TEXT: JBColor = JBColor.namedColor("Component.infoForeground", JBColor(0x888888, 0x777777))

    // --- Background colors (theme-aware) ---

    /** Tool window / panel background. */
    val PANEL_BG: JBColor = JBColor.namedColor("ToolWindow.background", JBColor.PanelBackground)

    /** User message bubble background. */
    val USER_BUBBLE_BG: JBColor = JBColor.namedColor("Editor.background", JBColor(0xEEEEEE, 0x2D2F31))

    /** Assistant message bubble background. */
    val ASSISTANT_BUBBLE_BG: JBColor = JBColor.namedColor("Panel.background", JBColor(0xFAFAFA, 0x232527))

    /** Tool call card background. */
    val TOOL_CARD_BG: JBColor = JBColor.namedColor("Component.background", JBColor(0xF5F5F5, 0x2A2D30))

    /** Tool call content area background (slightly different from card). */
    val TOOL_CONTENT_BG: JBColor = JBColor.namedColor("TextArea.background", JBColor(0xF5F5F5, 0x1E1E1E))

    /** Error panel background. */
    val ERROR_BG: JBColor = JBColor.namedColor("Notification.error.background", JBColor(0xFFEEEE, 0x3A2222))

    /** Error panel border. */
    val ERROR_BORDER: JBColor = JBColor.namedColor("Notification.error.borderColor", JBColor(0xCC0000, 0xE06C6C))

    // --- Borders and lines (theme-aware) ---

    /** Subtle border color for cards and panels. */
    val SUBTLE_BORDER: JBColor = JBColor.namedColor("Component.borderColor", JBColor(0xE0E0E0, 0x3C3F41))

    /** Separator color. */
    val SEPARATOR: JBColor = JBColor.namedColor("Separator.separatorColor", JBColor(0xE0E0E0, 0x3C3F41))

    // --- Accent / status colors ---

    /** Primary accent color for active elements, buttons, links. */
    val ACCENT: JBColor = JBColor.namedColor("Link.activeForeground", JBColor(0x0066CC, 0x4A9EFF))

    /** Secondary accent for hover states. */
    val ACCENT_HOVER: JBColor = JBColor.namedColor("Link.hoverForeground", JBColor(0x0052A3, 0x6BB5FF))

    /** Success indicator. */
    val SUCCESS: JBColor = JBColor.namedColor("Notification.successBackground", JBColor(0x2E7D32, 0x66BB6A))

    /** Warning indicator. */
    val WARNING: JBColor = JBColor.namedColor("Notification.warningBackground", JBColor(0xF57F17, 0xFFB74D))

    // --- Code block colors (from editor scheme) ---

    /** Code block header background. */
    val CODE_HEADER_BG: JBColor = JBColor.namedColor("EditorPane.background", JBColor(0x3C3F41, 0x2D2D2D))

    /** Code block body background. */
    val CODE_BODY_BG: JBColor = JBColor.namedColor("Editor.background", JBColor(0x2B2B2B, 0x1E1E1E))

    /** Code block header label color. */
    val CODE_HEADER_FG: JBColor = JBColor.namedColor("EditorPane.foreground", JBColor(0xCCCCCC, 0xAAAAAA))

    // --- Spacing Constants ---

    const val CORNER_RADIUS_SMALL = 6
    const val CORNER_RADIUS_MEDIUM = 10
    const val CORNER_RADIUS_LARGE = 14

    // --- Editor scheme colors (resolved lazily) ---

    /**
     * Get the global editor scheme's default background.
     */
    fun editorBackground(): Color {
        return try {
            EditorColorsManager.getInstance().globalScheme.defaultBackground
        } catch (_: Exception) {
            CODE_BODY_BG
        }
    }

    /**
     * Get the global editor scheme's default foreground.
     */
    fun editorForeground(): Color {
        return try {
            EditorColorsManager.getInstance().globalScheme.defaultForeground
        } catch (_: Exception) {
            CODE_HEADER_FG
        }
    }

    // --- DefaultLanguageHighlighterColors (syntax highlighting palette) ---

    /** Keyword color (e.g. `fun`, `val`, `if`) — useful for accent text, inline code. */
    fun keywordColor(): Color = editorAttrColor(DefaultLanguageHighlighterColors.KEYWORD, ACCENT)

    /** Line comment color — useful for muted/thinking text. */
    fun commentColor(): Color = editorAttrColor(DefaultLanguageHighlighterColors.LINE_COMMENT, MUTED_TEXT)

    /** Doc comment color — slightly brighter than line comments, for documentation-style text. */
    fun docCommentColor(): Color = editorAttrColor(DefaultLanguageHighlighterColors.DOC_COMMENT, MUTED_TEXT)

    /** String literal color — useful for inline code spans, file paths. */
    fun stringColor(): Color = editorAttrColor(DefaultLanguageHighlighterColors.STRING, ACCENT)

    /** Number literal color — useful for metrics, counters, token counts. */
    fun numberColor(): Color = editorAttrColor(DefaultLanguageHighlighterColors.NUMBER, PRIMARY_TEXT)

    /** Function declaration color — useful for tool names, function references. */
    fun functionColor(): Color = editorAttrColor(DefaultLanguageHighlighterColors.FUNCTION_DECLARATION, ACCENT)

    /** Class name color — useful for headers, titles, type references. */
    fun classNameColor(): Color = editorAttrColor(DefaultLanguageHighlighterColors.CLASS_NAME, PRIMARY_TEXT)

    /** Metadata/annotation color — useful for timestamps, metadata labels. */
    fun metadataColor(): Color = editorAttrColor(DefaultLanguageHighlighterColors.METADATA, SECONDARY_TEXT)

    /** Inline code in documentation — useful for inline code spans in messages. */
    fun docInlineCodeColor(): Color = editorAttrColor(DefaultLanguageHighlighterColors.DOC_CODE_INLINE, ACCENT)

    /** Valid string escape color — useful for highlighting special tokens. */
    fun validEscapeColor(): Color = editorAttrColor(DefaultLanguageHighlighterColors.VALID_STRING_ESCAPE, ACCENT)

    /** Invalid string escape color — useful for error highlighting in code. */
    fun invalidEscapeColor(): Color = editorAttrColor(DefaultLanguageHighlighterColors.INVALID_STRING_ESCAPE, ERROR_TEXT)

    // --- EditorColors (editor chrome / diff / gutter palette) ---

    /** Selection background color — for selected items, active tab backgrounds. */
    fun selectionBackground(): Color = editorColorKey(EditorColors.SELECTION_BACKGROUND_COLOR, ACCENT)

    /** Notification background — for info/notification-style panels. */
    fun notificationBackground(): Color = editorColorKey(EditorColors.NOTIFICATION_BACKGROUND, TOOL_CARD_BG)

    /** Indent guide color — for subtle vertical/horizontal separators. */
    fun indentGuideColor(): Color = editorColorKey(EditorColors.INDENT_GUIDE_COLOR, SUBTLE_BORDER)

    /** Added lines color (diff green) — for success/added indicators. */
    fun addedLinesColor(): Color = editorColorKey(EditorColors.ADDED_LINES_COLOR, SUCCESS)

    /** Deleted lines color (diff red) — for error/deleted indicators. */
    fun deletedLinesColor(): Color = editorColorKey(EditorColors.DELETED_LINES_COLOR, ERROR_BORDER)

    /** Modified lines color (diff yellow/amber) — for in-progress/modified indicators. */
    fun modifiedLinesColor(): Color = editorColorKey(EditorColors.MODIFIED_LINES_COLOR, WARNING)

    /** Border lines color (diff borders) — for diff-style card borders. */
    fun borderLinesColor(): Color = editorColorKey(EditorColors.BORDER_LINES_COLOR, SUBTLE_BORDER)

    /** Hyperlink color (CTRL+CLICK) — for clickable links in messages. */
    fun hyperlinkColor(): Color = editorAttrColor(EditorColors.REFERENCE_HYPERLINK_COLOR, ACCENT)

    /** Documentation background — for documentation-style panels. */
    fun documentationBackground(): Color = editorColorKey(EditorColors.DOCUMENTATION_COLOR, ASSISTANT_BUBBLE_BG)

    /** Preview background — for code preview areas. */
    fun previewBackground(): Color = editorColorKey(EditorColors.PREVIEW_BACKGROUND, CODE_BODY_BG)

    /** Read-only background — for read-only code display areas. */
    fun readonlyBackground(): Color = editorColorKey(EditorColors.READONLY_BACKGROUND_COLOR, CODE_BODY_BG)

    /** Tearline color — for separator lines between sections. */
    fun tearlineColor(): Color = editorColorKey(EditorColors.TEARLINE_COLOR, SEPARATOR)

    /** Gutter background — for gutter-like side panels. */
    fun gutterBackground(): Color = editorColorKey(EditorColors.GUTTER_BACKGROUND, PANEL_BG)

    /** Caret row color — for highlighted/active row backgrounds. */
    fun caretRowColor(): Color = editorColorKey(EditorColors.CARET_ROW_COLOR, ASSISTANT_BUBBLE_BG)

    // --- Private helpers for editor scheme resolution ---

    private fun editorAttrColor(key: com.intellij.openapi.editor.colors.TextAttributesKey, fallback: Color): Color {
        return try {
            EditorColorsManager.getInstance().globalScheme
                .getAttributes(key)?.foregroundColor ?: fallback
        } catch (_: Exception) {
            fallback
        }
    }

    private fun editorColorKey(key: com.intellij.openapi.editor.colors.ColorKey, fallback: Color): Color {
        return try {
            EditorColorsManager.getInstance().globalScheme.getColor(key) ?: fallback
        } catch (_: Exception) {
            fallback
        }
    }

    /**
     * Convert a Color to an HTML hex string (e.g. "#0066CC").
     * Useful for embedding editor-scheme colors into HTML styled text.
     */
    fun colorToHex(color: Color): String {
        val r = color.red
        val g = color.green
        val b = color.blue
        return String.format("#%02X%02X%02X", r, g, b)
    }

    // --- Helper Methods ---

    /**
     * Enables anti-aliased rendering on a Graphics2D context.
     */
    fun enableAntiAliasing(g2: Graphics2D) {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    }

    /**
     * Draws a rounded rectangle border on the given component.
     */
    fun drawRoundedBorder(g: java.awt.Graphics, component: JComponent, color: JBColor, radius: Int, strokeWidth: Float = 1f) {
        val g2 = g.create() as Graphics2D
        try {
            enableAntiAliasing(g2)
            g2.color = color
            g2.stroke = java.awt.BasicStroke(strokeWidth)
            g2.drawRoundRect(0, 0, component.width - 1, component.height - 1, radius, radius)
        } finally {
            g2.dispose()
        }
    }

    /**
     * Fills a rounded rectangle background on the given component.
     */
    fun fillRoundedBackground(g: java.awt.Graphics, component: JComponent, color: JBColor, radius: Int) {
        val g2 = g.create() as Graphics2D
        try {
            enableAntiAliasing(g2)
            g2.color = color
            g2.fill(RoundRectangle2D.Float(
                0f, 0f,
                component.width.toFloat(), component.height.toFloat(),
                radius.toFloat(), radius.toFloat()
            ))
        } finally {
            g2.dispose()
        }
    }

    /**
     * Creates standard padding insets.
     */
    fun padding(all: Int) = JBUI.insets(all)
    fun padding(v: Int, h: Int) = JBUI.insets(v, h)
    fun padding(top: Int, left: Int, bottom: Int, right: Int) = JBUI.insets(top, left, bottom, right)

    /**
     * Registers a listener that fires when the IDE theme changes (light/dark switch).
     * Returns a disposable that can be used to unregister.
     */
    fun onThemeChange(callback: () -> Unit) {
        val connection = ApplicationManager.getApplication().messageBus.connect()
        connection.subscribe(LafManagerListener.TOPIC, LafManagerListener {
            callback()
        })
    }

    /**
     * Returns true if the current theme is dark.
     */
    fun isDarkTheme(): Boolean {
        return !JBColor.isBright()
    }

    /**
     * Gets a themed color from UIManager with a fallback.
     */
    fun getThemedColor(key: String, fallback: JBColor): Color {
        return try {
            UIManager.getColor(key) ?: fallback
        } catch (_: Exception) {
            fallback
        }
    }
}
