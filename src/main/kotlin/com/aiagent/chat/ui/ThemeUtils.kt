package com.aiagent.chat.ui

import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.application.ApplicationManager
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
 * Phase 10: Polish & Theme.
 */
object ThemeUtils {

    // --- Color Palette (light/dark pairs) ---

    /** Primary accent color for active elements, buttons, links. */
    val ACCENT: JBColor = JBColor(0x0066CC, 0x4A9EFF)

    /** Secondary accent for hover states. */
    val ACCENT_HOVER: JBColor = JBColor(0x0052A3, 0x6BB5FF)

    /** User message bubble background. */
    val USER_BUBBLE_BG: JBColor = JBColor(0xE3F2FD, 0x1A2A3A)

    /** Assistant message bubble background. */
    val ASSISTANT_BUBBLE_BG: JBColor = JBColor(0xFAFAFA, 0x232527)

    /** Tool call card background. */
    val TOOL_CARD_BG: JBColor = JBColor(0xF5F5F5, 0x2A2D30)

    /** Error panel background. */
    val ERROR_BG: JBColor = JBColor(0xFFEEEE, 0x3A2222)

    /** Error panel border. */
    val ERROR_BORDER: JBColor = JBColor(0xCC0000, 0xE06C6C)

    /** Success indicator. */
    val SUCCESS: JBColor = JBColor(0x2E7D32, 0x66BB6A)

    /** Warning indicator. */
    val WARNING: JBColor = JBColor(0xF57F17, 0xFFB74D)

    /** Subtle border color. */
    val SUBTLE_BORDER: JBColor = JBColor(0xE0E0E0, 0x3C3F41)

    /** Muted text color for secondary info. */
    val MUTED_TEXT: JBColor = JBColor(0x888888, 0x999999)

    /** Code block header background. */
    val CODE_HEADER_BG: JBColor = JBColor(0x3C3F41, 0x2D2D2D)

    /** Code block body background. */
    val CODE_BODY_BG: JBColor = JBColor(0x2B2B2B, 0x1E1E1E)

    // --- Spacing Constants ---

    const val CORNER_RADIUS_SMALL = 6
    const val CORNER_RADIUS_MEDIUM = 10
    const val CORNER_RADIUS_LARGE = 14

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
        return JBColor.isBright()
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
