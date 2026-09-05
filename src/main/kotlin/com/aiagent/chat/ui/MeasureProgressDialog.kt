package com.aiagent.chat.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.*
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableCellRenderer

/**
 * Non-modal progress dialog for model measurement.
 *
 * Shows:
 *  - Provider name + URL (connection info)
 *  - Progress bar (0..total)
 *  - Live table of measurements: Model, Status, Latency (ms) — color-coded by speed
 *  - Summary line: fastest / slowest / average after completion
 *  - Cancel button
 *
 * Thread-safety: all UI updates are marshalled to the EDT via SwingUtilities.
 */
class MeasureProgressDialog(
    owner: JFrame?,
    providerName: String,
    providerUrl: String,
    totalModels: Int
) : JDialog(owner, "Measuring Models - $providerName", false) {

    private val tableModel = DefaultTableModel(0, 3).apply {
        setColumnIdentifiers(arrayOf("Model", "Status", "Latency (ms)"))
    }
    private val table = JTable(tableModel).apply {
        columnModel.getColumn(0).preferredWidth = 200
        columnModel.getColumn(1).preferredWidth = 80
        columnModel.getColumn(2).preferredWidth = 100
        rowHeight = 22
        autoCreateRowSorter = true
    }

    private val progressBar = JProgressBar(0, totalModels).apply {
        isIndeterminate = false
        isStringPainted = true
        string = "0 / $totalModels"
    }

    private val infoLabel = JBLabel("Provider: $providerName    URL: $providerUrl").apply {
        font = font.deriveFont(java.awt.Font.PLAIN, 11f)
    }

    private val statusLabel = JBLabel("Starting...").apply {
        font = font.deriveFont(java.awt.Font.PLAIN, 11f)
        foreground = JBColor(0x666666, 0x999999)
    }

    private val summaryLabel = JBLabel(" ").apply {
        font = font.deriveFont(java.awt.Font.PLAIN, 11f)
        foreground = JBColor(0x444444, 0xAAAAAA)
    }

    private val cancelButton = JButton("Cancel").apply {
        font = font.deriveFont(java.awt.Font.PLAIN, 12f)
    }

    @Volatile
    private var cancelled = false

    val isCancelled: Boolean get() = cancelled

    init {
        defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        isResizable = true

        cancelButton.addActionListener {
            cancelled = true
            cancelButton.text = "Cancelling..."
            cancelButton.isEnabled = false
            statusLabel.text = "Cancelling..."
        }

        val content = JPanel(BorderLayout(0, 6)).apply {
            border = JBUI.Borders.empty(10)
            background = JBColor.PanelBackground
        }

        // Top: info + progress
        val topPanel = JPanel(BorderLayout(0, 4)).apply {
            isOpaque = false
            add(infoLabel, BorderLayout.NORTH)
            add(progressBar, BorderLayout.CENTER)
            add(statusLabel, BorderLayout.SOUTH)
        }
        content.add(topPanel, BorderLayout.NORTH)

        // Center: table
        val tableScroll = JBScrollPane(table).apply {
            border = JBUI.Borders.empty()
            preferredSize = java.awt.Dimension(400, 180)
        }
        content.add(tableScroll, BorderLayout.CENTER)

        // Summary line above buttons
        content.add(summaryLabel, BorderLayout.SOUTH)

        // Bottom: cancel button (below summary)
        val btnPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply { isOpaque = false }
        btnPanel.add(cancelButton)
        content.add(btnPanel, BorderLayout.AFTER_LAST_LINE)

        contentPane = content
        pack()
        setLocationRelativeTo(owner)
    }

    /**
     * Indicate which model is currently being measured.
     */
    fun setCurrentModel(modelName: String) {
        SwingUtilities.invokeLater {
            statusLabel.text = "Measuring: $modelName"
        }
    }

    /**
     * Add or update a row for a measured model.
     * Must be called on the EDT.
     */
    fun updateModelResult(modelName: String, latencyMs: Long) {
        SwingUtilities.invokeLater {
            val status = if (latencyMs > 0) "OK" else "FAILED"
            val latencyStr = if (latencyMs > 0) formatLatency(latencyMs) else "-"
            tableModel.addRow(arrayOf(modelName, status, latencyMs))

            // Apply color-coded renderer to the new row's latency cell
            val row = tableModel.rowCount - 1
            table.columnModel.getColumn(2).cellRenderer = LatencyCellRenderer()

            val done = tableModel.rowCount
            val total = progressBar.maximum
            progressBar.value = done
            progressBar.string = "$done / $total"
            statusLabel.text = "Measured $done / $total models"
        }
    }

    /**
     * Mark the dialog as finished (all models measured).
     */
    fun finish(okCount: Int, failCount: Int) {
        SwingUtilities.invokeLater {
            progressBar.value = progressBar.maximum
            statusLabel.text = "Done: $okCount OK, $failCount failed"
            title = "Measurement Complete - $okCount OK, $failCount failed"
            showSummary()
            sortTableByLatency()
            convertButtonToClose()
        }
    }

    /**
     * Show a "cancelled" state.
     */
    fun finishCancelled(measuredCount: Int) {
        SwingUtilities.invokeLater {
            statusLabel.text = "Cancelled after $measuredCount models"
            title = "Measurement Cancelled"
            showSummary()
            sortTableByLatency()
            convertButtonToClose()
        }
    }

    /**
     * Show fastest / slowest / average latency summary.
     */
    private fun showSummary() {
        val latencies = mutableListOf<Long>()
        for (i in 0 until tableModel.rowCount) {
            val lat = tableModel.getValueAt(i, 2) as? Long ?: continue
            if (lat > 0) latencies.add(lat)
        }
        if (latencies.isEmpty()) {
            summaryLabel.text = "No successful measurements."
            return
        }
        val fastest = latencies.min()
        val slowest = latencies.max()
        val avg = latencies.sum() / latencies.size
        summaryLabel.text = "Fastest: ${formatLatency(fastest)}    Slowest: ${formatLatency(slowest)}    Average: ${formatLatency(avg)}"
    }

    /**
     * Sort the table by latency ascending (fastest first, failed last).
     */
    private fun sortTableByLatency() {
        val sorter = table.rowSorter as? javax.swing.table.TableRowSorter<*> ?: return
        // Latency column = 2; failed (0) sorts last by using a custom comparator
        sorter.setComparator(2, Comparator<Any> { a, b ->
            val la = (a as? Long) ?: 0L
            val lb = (b as? Long) ?: 0L
            // Treat 0 (failed) as max so they go to the bottom
            val sa = if (la == 0L) Long.MAX_VALUE else la
            val sb = if (lb == 0L) Long.MAX_VALUE else lb
            sa.compareTo(sb)
        })
        sorter.sort()
    }

    private fun formatLatency(ms: Long): String {
        return if (ms >= 1000) {
            String.format("%.2fs", ms / 1000.0)
        } else {
            "${ms}ms"
        }
    }

    private fun convertButtonToClose() {
        for (listener in cancelButton.actionListeners) {
            cancelButton.removeActionListener(listener)
        }
        cancelButton.text = "Close"
        cancelButton.isEnabled = true
        cancelButton.addActionListener { dispose() }
    }

    /**
     * Custom renderer for the Latency column.
     * Color-codes: green (< 1000ms), yellow (1000-3000ms), red (> 3000ms or failed).
     */
    private class LatencyCellRenderer : JLabel(), TableCellRenderer {
        init {
            isOpaque = true
            horizontalAlignment = SwingConstants.CENTER
            font = font.deriveFont(java.awt.Font.PLAIN, 11f)
        }

        override fun getTableCellRendererComponent(
            table: JTable?, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, col: Int
        ): java.awt.Component {
            val ms = (value as? Long) ?: 0L
            if (ms == 0L) {
                text = "-"
                background = JBColor(0xFFCCCC, 0x4A2A2A)
            } else {
                text = if (ms >= 1000) String.format("%.2fs", ms / 1000.0) else "${ms}ms"
                background = when {
                    ms < 1000 -> JBColor(0xCCFFCC, 0x2A4A2A)   // green
                    ms <= 3000 -> JBColor(0xFFFFCC, 0x4A4A2A)   // yellow
                    else -> JBColor(0xFFDDCC, 0x4A3A2A)         // red-orange
                }
            }
            if (isSelected) {
                background = table?.selectionBackground ?: background
            }
            return this
        }
    }
}
