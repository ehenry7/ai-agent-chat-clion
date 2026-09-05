package com.aiagent.chat.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.*
import javax.swing.table.DefaultTableModel

/**
 * Non-modal progress dialog for model measurement.
 *
 * Shows:
 *  - Provider name + URL (connection info)
 *  - Progress bar (0..total)
 *  - Live table of measurements: Model, Status, Latency (ms)
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
        columnModel.getColumn(2).preferredWidth = 80
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
            preferredSize = java.awt.Dimension(380, 180)
        }
        content.add(tableScroll, BorderLayout.CENTER)

        // Bottom: cancel button
        val btnPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply { isOpaque = false }
        btnPanel.add(cancelButton)
        content.add(btnPanel, BorderLayout.SOUTH)

        contentPane = content
        pack()
        setLocationRelativeTo(owner)
    }

    /**
     * Add or update a row for a measured model.
     * Must be called on the EDT.
     */
    fun updateModelResult(modelName: String, latencyMs: Long) {
        SwingUtilities.invokeLater {
            val status = if (latencyMs > 0) "OK" else "FAILED"
            val latencyStr = if (latencyMs > 0) latencyMs.toString() else "-"
            tableModel.addRow(arrayOf(modelName, status, latencyStr))

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
            convertButtonToClose()
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
}
