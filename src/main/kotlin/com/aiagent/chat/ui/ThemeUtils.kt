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

    /**
     * Get the keyword color from the editor scheme (for syntax highlighting references).
     */
    fun keywordColor(): Color {
        return try {
            EditorColorsManager.getInstance().globalScheme
                .getAttributes(DefaultLanguageHighlighterColors.KEYWORD)?.foregroundColor
                ?: ACCENT
        } catch (_: Exception) {
            ACCENT
        }
    }

    /**
     * Get the comment color from the editor scheme.
     */
    fun commentColor(): Color {
        return try {
            EditorColorsManager.getInstance().globalScheme
                .getAttributes(DefaultLanguageHighlighterColors.LINE_COMMENT)?.foregroundColor
                ?: MUTED_TEXT
        } catch (_: Exception) {
            MUTED_TEXT
        }
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
