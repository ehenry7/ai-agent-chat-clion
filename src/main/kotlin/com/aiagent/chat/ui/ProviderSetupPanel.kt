package com.aiagent.chat.ui

import com.aiagent.chat.model.AuthHeaderType
import com.aiagent.chat.model.ModelCost
import com.aiagent.chat.model.ModelInfo
import com.aiagent.chat.model.ModelSize
import com.aiagent.chat.model.ProviderConfig
import com.aiagent.chat.model.ProviderManager
import com.aiagent.chat.services.ChatStateService
import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.*
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.FlowLayout
import java.awt.GridLayout
import javax.swing.*
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableCellEditor
import javax.swing.table.TableCellRenderer

/**
 * Provider-only setup panel v2.
 *
 * Features:
 *  - Provider table (Enabled, Name, URL, Key) with add/edit/remove via popup
 *  - Model table (Enabled, Name, Id, Type, Cost, Context, Output) — all editable
 *  - Test Connection button (auto-detects auth type)
 *  - Measure button (measures TEE timing, disables failed models)
 *  - Default model selector (auto-selects best performance)
 *  - Default provider selector
 *  - No auth-type display (auto-detected on test/sync)
 *  - Auto-fetches models after successful connection test
 *  - All boxes top-aligned, minimal spacing
 */
class ProviderSetupPanel(
    private val settings: ChatStateService,
    private val onSave: () -> Unit,
    private val onCancel: () -> Unit
) : JBPanel<ProviderSetupPanel>(BorderLayout()) {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // --- Provider table ---
    private val providerTableModel = DefaultTableModel(0, 4)
    private val providerTable = JTable(providerTableModel).apply {
        columnModel.getColumn(0).headerValue = "Enabled"
        columnModel.getColumn(0).preferredWidth = 50
        columnModel.getColumn(1).headerValue = "Name"
        columnModel.getColumn(1).preferredWidth = 120
        columnModel.getColumn(2).headerValue = "URL"
        columnModel.getColumn(2).preferredWidth = 250
        columnModel.getColumn(3).headerValue = "Key"
        columnModel.getColumn(3).preferredWidth = 80
        rowHeight = 24
        autoCreateRowSorter = true
    }

    // --- Model table ---
    private val modelTableModel = DefaultTableModel(0, 7)
    private val modelTable = JTable(modelTableModel).apply {
        columnModel.getColumn(0).headerValue = "Enabled"
        columnModel.getColumn(0).preferredWidth = 50
        columnModel.getColumn(1).headerValue = "Name"
        columnModel.getColumn(1).preferredWidth = 120
        columnModel.getColumn(2).headerValue = "Id"
        columnModel.getColumn(2).preferredWidth = 150
        columnModel.getColumn(3).headerValue = "Type"
        columnModel.getColumn(3).preferredWidth = 70
        columnModel.getColumn(4).headerValue = "Cost"
        columnModel.getColumn(4).preferredWidth = 70
        columnModel.getColumn(5).headerValue = "Context"
        columnModel.getColumn(5).preferredWidth = 70
        columnModel.getColumn(6).headerValue = "Output"
        columnModel.getColumn(6).preferredWidth = 70
        rowHeight = 24
    }

    // --- Default model selector ---
    private val defaultModelCombo = JComboBox<String>()

    // --- Default provider selector ---
    private val defaultProviderCombo = JComboBox<String>()

    // --- Agent params ---
    private val maxStepsField = JBTextField()

    // --- Status label for test/measure feedback ---
    private val statusLabel = JBLabel(" ").apply {
        font = font.deriveFont(java.awt.Font.PLAIN, 11f)
        foreground = JBColor(0x666666, 0x999999)
    }

    // --- Currently selected provider for model table display ---
    private var selectedProviderId: String? = null

    // --- Provider edit popup fields ---
    private val popupNameField = JBTextField()
    private val popupUrlField = JBTextField()
    private val popupKeyField = JPasswordField()

    init {
        border = JBUI.Borders.empty(8)
        background = JBColor.PanelBackground

        // Top-aligned content — BoxLayout sizes each section to its content,
        // does NOT distribute vertical space across all sections.
        val scrollContent = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }
        scrollContent.add(buildGeneralParamsSection())
        scrollContent.add(Box.createVerticalStrut(4))
        scrollContent.add(buildProviderSection())
        scrollContent.add(Box.createVerticalStrut(4))
        scrollContent.add(buildModelSection())
        scrollContent.add(Box.createVerticalGlue()) // push everything to top

        val scrollPane = JBScrollPane(scrollContent).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        }
        add(scrollPane, BorderLayout.CENTER)

        // Bottom: status + buttons
        val bottomPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 0, 0, 0)
        }
        bottomPanel.add(statusLabel, BorderLayout.WEST)
        bottomPanel.add(buildButtonBar(), BorderLayout.EAST)
        add(bottomPanel, BorderLayout.SOUTH)

        // Wire provider table selection
        providerTable.selectionModel.addListSelectionListener { e ->
            if (!e.valueIsAdjusting && providerTable.selectedRow >= 0) {
                val modelRow = providerTable.convertRowIndexToModel(providerTable.selectedRow)
                selectedProviderId = providerTableModel.getValueAt(modelRow, 1)?.toString()
                // Find provider by name and load its models
                val provider = settings.getProviders().find { it.name == selectedProviderId }
                if (provider != null) {
                    selectedProviderId = provider.id
                    refreshModelTable(provider)
                }
            }
        }

        refreshProviderTable()
        refreshDefaultCombos()
        syncFieldsFromSettings()

        // Requirement 1: if no providers, auto-start add provider
        if (settings.getProviders().isEmpty()) {
            SwingUtilities.invokeLater { showAddProviderPopup() }
        }
    }

    // ----------------------------------------------------------------
    // Section builders
    // ----------------------------------------------------------------

    /**
     * General Parameters section — placed on TOP of all other sections.
     * Contains: Default Provider, Default Model, Max Steps.
     * Sized to content, not stretched.
     */
    private fun buildGeneralParamsSection(): JComponent {
        val outer = JPanel(GridLayout(0, 1, 2, 2)).apply {
            isOpaque = false
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(6)
            )
            // Size to content, do not stretch
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, getPreferredSize().height)
        }

        outer.add(JBLabel("AI Agent General Parameters").apply {
            font = font.deriveFont(java.awt.Font.BOLD, 13f)
        })

        // Default Provider row
        val providerRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply { isOpaque = false }
        providerRow.add(JBLabel("Default Provider:").apply { font = font.deriveFont(java.awt.Font.PLAIN, 11f) })
        defaultProviderCombo.preferredSize = java.awt.Dimension(150, 24)
        defaultProviderCombo.addActionListener {
            val selectedName = defaultProviderCombo.selectedItem as? String ?: return@addActionListener
            val providers = settings.getProviders()
            providers.forEach { p ->
                val updated = p.copy(isDefault = (p.name == selectedName))
                settings.addProvider(updated)
            }
        }
        providerRow.add(defaultProviderCombo)
        outer.add(providerRow)

        // Default Model row
        val modelRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply { isOpaque = false }
        modelRow.add(JBLabel("Default Model:").apply { font = font.deriveFont(java.awt.Font.PLAIN, 11f) })
        defaultModelCombo.preferredSize = java.awt.Dimension(200, 24)
        modelRow.add(defaultModelCombo)
        outer.add(modelRow)

        // Max Steps row
        val stepsRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply { isOpaque = false }
        stepsRow.add(JBLabel("Max Steps:").apply { font = font.deriveFont(java.awt.Font.PLAIN, 11f) })
        maxStepsField.preferredSize = java.awt.Dimension(60, 24)
        stepsRow.add(maxStepsField)
        stepsRow.add(JBLabel("(default: 25)").apply {
            font = font.deriveFont(java.awt.Font.ITALIC, 10f)
            foreground = JBColor(0x999999, 0x666666)
        })
        outer.add(stepsRow)

        return outer
    }

    private fun buildProviderSection(): JComponent {
        val outer = JPanel(BorderLayout(0, 4)).apply { isOpaque = false }
        outer.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border(), 1),
            JBUI.Borders.empty(6)
        )
        // Size to content, do not stretch
        outer.maximumSize = java.awt.Dimension(Int.MAX_VALUE, outer.preferredSize.height)

        // Title row with buttons
        val titleRow = JPanel(BorderLayout()).apply { isOpaque = false }
        titleRow.add(JBLabel("Providers").apply {
            font = font.deriveFont(java.awt.Font.BOLD, 13f)
        }, BorderLayout.WEST)

        val btnPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0)).apply { isOpaque = false }
        val addBtn = JButton("Add", AllIcons.General.Add).apply {
            font = font.deriveFont(java.awt.Font.PLAIN, 11f)
            addActionListener { showAddProviderPopup() }
        }
        val removeBtn = JButton("Remove", AllIcons.Actions.Cancel).apply {
            font = font.deriveFont(java.awt.Font.PLAIN, 11f)
            foreground = JBColor(0xCC0000, 0xFF6666)
            addActionListener { removeSelectedProvider() }
        }
        btnPanel.add(addBtn)
        btnPanel.add(removeBtn)
        titleRow.add(btnPanel, BorderLayout.EAST)
        outer.add(titleRow, BorderLayout.NORTH)

        // Provider table in scroll
        val tableScroll = JBScrollPane(providerTable).apply {
            border = JBUI.Borders.empty()
            preferredSize = java.awt.Dimension(0, 100)
        }
        outer.add(tableScroll, BorderLayout.CENTER)

        return outer
    }

    private fun buildModelSection(): JComponent {
        val outer = JPanel(BorderLayout(0, 4)).apply { isOpaque = false }
        outer.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border(), 1),
            JBUI.Borders.empty(6)
        )
        // Size to content, do not stretch
        outer.maximumSize = java.awt.Dimension(Int.MAX_VALUE, outer.preferredSize.height)

        // Title row with buttons
        val titleRow = JPanel(BorderLayout()).apply { isOpaque = false }
        titleRow.add(JBLabel("Models").apply {
            font = font.deriveFont(java.awt.Font.BOLD, 13f)
        }, BorderLayout.WEST)

        val btnPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0)).apply { isOpaque = false }
        val syncBtn = JButton("Sync Models", AllIcons.Actions.Refresh).apply {
            font = font.deriveFont(java.awt.Font.PLAIN, 11f)
            toolTipText = "Fetch available models from the selected provider"
            addActionListener { syncModelsForSelected() }
        }
        val testBtn = JButton("Test Connection").apply {
            font = font.deriveFont(java.awt.Font.PLAIN, 11f)
            addActionListener { testConnectionForSelected() }
        }
        val measureBtn = JButton("Measure").apply {
            font = font.deriveFont(java.awt.Font.PLAIN, 11f)
            toolTipText = "Measure TEE timing for all models of selected provider"
            addActionListener { measureModelsForSelected() }
        }
        btnPanel.add(testBtn)
        btnPanel.add(syncBtn)
        btnPanel.add(measureBtn)
        titleRow.add(btnPanel, BorderLayout.EAST)
        outer.add(titleRow, BorderLayout.NORTH)

        // Model table in scroll
        val tableScroll = JBScrollPane(modelTable).apply {
            border = JBUI.Borders.empty()
            preferredSize = java.awt.Dimension(0, 150)
        }
        outer.add(tableScroll, BorderLayout.CENTER)

        return outer
    }

    private fun buildButtonBar(): JComponent {
        val panel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 4)).apply {
            isOpaque = false
        }

        val cancelBtn = JButton("Cancel").apply {
            font = font.deriveFont(java.awt.Font.PLAIN, 12f)
            addActionListener { onCancel() }
        }
        val saveBtn = JButton("Save and Start", AllIcons.Actions.Commit).apply {
            font = font.deriveFont(java.awt.Font.BOLD, 12f)
            addActionListener { saveAll() }
        }

        panel.add(cancelBtn)
        panel.add(saveBtn)
        return panel
    }

    // ----------------------------------------------------------------
    // Provider table logic
    // ----------------------------------------------------------------

    private fun refreshProviderTable() {
        providerTableModel.rowCount = 0
        for (p in settings.getProviders()) {
            providerTableModel.addRow(arrayOf(
                p.enabled,
                p.name,
                p.baseUrl,
                if (p.apiKey.isNotBlank()) "***" else ""
            ))
        }
        // Set checkbox renderer/editor for Enabled column
        providerTable.columnModel.getColumn(0).cellRenderer = CheckboxRenderer()
        providerTable.columnModel.getColumn(0).cellEditor = CheckboxEditor()
        refreshDefaultCombos()
    }

    private fun refreshModelTable(provider: ProviderConfig) {
        modelTableModel.rowCount = 0
        for (m in provider.models) {
            modelTableModel.addRow(arrayOf(
                m.enabled,
                m.name,
                m.id,
                m.sizeTag.displayName,
                m.costTag.displayName,
                m.maxContextTokens.toString(),
                m.maxOutputTokens.toString()
            ))
        }
        // Set editors
        modelTable.columnModel.getColumn(0).cellRenderer = CheckboxRenderer()
        modelTable.columnModel.getColumn(0).cellEditor = CheckboxEditor()
        modelTable.columnModel.getColumn(3).cellEditor = ComboBoxEditor(arrayOf("small", "medium", "large", "xl"))
        modelTable.columnModel.getColumn(4).cellEditor = ComboBoxEditor(arrayOf("free", "low-cost", "medium-cost", "high-cost"))
    }

    private fun refreshDefaultCombos() {
        // Default provider combo
        val providerNames = settings.getProviders().map { it.name }
        defaultProviderCombo.removeAllItems()
        providerNames.forEach { defaultProviderCombo.addItem(it) }
        val defaultProvider = settings.getProviders().find { it.isDefault }
        if (defaultProvider != null && providerNames.contains(defaultProvider.name)) {
            defaultProviderCombo.selectedItem = defaultProvider.name
        } else if (providerNames.isNotEmpty()) {
            defaultProviderCombo.selectedIndex = 0
        }

        // Default model combo — all models from all providers as ProviderName/ModelName
        val allModels = settings.getProviders().flatMap { p ->
            p.models.filter { it.enabled }.map { "${p.name}/${it.name}" }
        }
        defaultModelCombo.removeAllItems()
        allModels.forEach { defaultModelCombo.addItem(it) }

        // Try to restore saved default model
        val saved = settings.state.defaultModelDisplayName
        if (saved.isNotBlank() && allModels.contains(saved)) {
            defaultModelCombo.selectedItem = saved
        } else if (allModels.isNotEmpty()) {
            // Requirement 7: by default choose the model with best performance
            // Best performance = largest model (XL > LARGE > MEDIUM > SMALL) with lowest latency
            val bestModel = settings.getProviders()
                .flatMap { p -> p.models.filter { it.enabled }.map { p to it } }
                .maxByOrNull { (_, m) ->
                    // Prefer larger size, then lower latency (if measured)
                    m.sizeTag.ordinal * 1000000 + (if (m.measured && m.latencyMs > 0) (100000 - m.latencyMs.coerceAtMost(100000)).toInt() else 0)
                }
            if (bestModel != null) {
                val (p, m) = bestModel
                defaultModelCombo.selectedItem = "${p.name}/${m.name}"
            }
        }
    }

    // ----------------------------------------------------------------
    // Add provider popup (Requirement 10)
    // ----------------------------------------------------------------

    private fun showAddProviderPopup() {
        popupNameField.text = ""
        popupUrlField.text = ""
        popupKeyField.text = ""

        val panel = JPanel(GridLayout(0, 1, 4, 4))
        panel.add(JBLabel("Provider Name:"))
        panel.add(popupNameField)
        panel.add(JBLabel("Base URL:"))
        panel.add(popupUrlField)
        panel.add(JBLabel("API Key:"))
        panel.add(popupKeyField)

        val result = JOptionPane.showConfirmDialog(
            this, panel, "Add Provider",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE
        )

        if (result == JOptionPane.OK_OPTION) {
            val name = popupNameField.text.trim()
            val url = popupUrlField.text.trim()
            val key = String(popupKeyField.password).trim()
            if (name.isNotEmpty() && url.isNotEmpty()) {
                val id = "prov_${System.currentTimeMillis()}"
                val provider = ProviderConfig(
                    id = id, name = name, baseUrl = url, apiKey = key,
                    authHeaderType = AuthHeaderType.BEARER, enabled = true,
                    isDefault = settings.getProviders().isEmpty()
                )
                settings.addProvider(provider)
                refreshProviderTable()

                // Select the new provider
                for (i in 0 until providerTableModel.rowCount) {
                    if (providerTableModel.getValueAt(i, 1) == name) {
                        providerTable.setRowSelectionInterval(i, i)
                        break
                    }
                }

                // Requirement 10: auto-start refresh models on OK
                setStatus("Auto-syncing models for '$name'...")
                scope.launch {
                    try {
                        // First test connection to auto-detect auth type
                        val testResult = onTestConnection?.invoke(provider)
                        val providerWithAuth = if (testResult?.success == true && testResult.authType != null) {
                            provider.copy(authHeaderType = testResult.authType)
                        } else {
                            provider
                        }
                        val synced = onSyncModels?.invoke(providerWithAuth)
                        SwingUtilities.invokeLater {
                            if (synced != null) {
                                onModelsSynced(synced)
                                setStatus("Models synced: ${synced.models.size} found")
                            } else {
                                setStatus("Model sync failed")
                            }
                        }
                    } catch (e: Exception) {
                        SwingUtilities.invokeLater { setStatus("Sync error: ${e.message}") }
                    }
                }
            }
        }
    }

    private fun removeSelectedProvider() {
        val row = providerTable.selectedRow
        if (row < 0) return
        val modelRow = providerTable.convertRowIndexToModel(row)
        val name = providerTableModel.getValueAt(modelRow, 1)?.toString() ?: return
        val provider = settings.getProviders().find { it.name == name } ?: return
        settings.removeProvider(provider.id)
        refreshProviderTable()
        modelTableModel.rowCount = 0
        setStatus("Provider '$name' removed")
    }

    // ----------------------------------------------------------------
    // Test connection (Requirement 2, 3, 4)
    // ----------------------------------------------------------------

    private fun testConnectionForSelected() {
        val provider = getSelectedProvider() ?: run {
            setStatus("Select a provider first")
            return
        }
        setStatus("Testing connection to '${provider.name}'...")
        scope.launch {
            val result = onTestConnection?.invoke(provider)
            SwingUtilities.invokeLater {
                if (result?.success == true && result.authType != null) {
                    // Auto-set auth type (Requirement 3)
                    val updated = provider.copy(authHeaderType = result.authType)
                    settings.addProvider(updated)
                    setStatus("Connection OK (${result.latencyMs}ms), auth: ${result.authType}")

                    // Requirement 4: auto-fetch models after successful test
                    syncModelsForProvider(updated)
                } else {
                    setStatus("Connection failed: ${result?.message ?: "unknown error"}")
                }
            }
        }
    }

    // ----------------------------------------------------------------
    // Sync models
    // ----------------------------------------------------------------

    private fun syncModelsForSelected() {
        val provider = getSelectedProvider() ?: run {
            setStatus("Select a provider first")
            return
        }
        syncModelsForProvider(provider)
    }

    private fun syncModelsForProvider(provider: ProviderConfig) {
        setStatus("Syncing models for '${provider.name}'...")
        scope.launch {
            try {
                val synced = onSyncModels?.invoke(provider)
                SwingUtilities.invokeLater {
                    if (synced != null) {
                        onModelsSynced(synced)
                        setStatus("Synced ${synced.models.size} models from '${provider.name}'")
                    } else {
                        setStatus("Sync failed")
                    }
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater { setStatus("Sync error: ${e.message}") }
            }
        }
    }

    // ----------------------------------------------------------------
    // Measure (Requirement 2)
    // ----------------------------------------------------------------

    private fun measureModelsForSelected() {
        val provider = getSelectedProvider() ?: run {
            setStatus("Select a provider first")
            return
        }
        if (provider.models.isEmpty()) {
            setStatus("No models to measure. Sync first.")
            return
        }

        // Find parent frame for dialog
        val parentFrame = SwingUtilities.getWindowAncestor(this) as? JFrame
        val dialog = MeasureProgressDialog(
            owner = parentFrame,
            providerName = provider.name,
            providerUrl = provider.baseUrl,
            totalModels = provider.models.size
        )
        dialog.isVisible = true

        setStatus("Measuring ${provider.models.size} models...")
        scope.launch {
            try {
                val results = onMeasureModels?.invoke(
                    provider,
                    { modelId, latency -> dialog.updateModelResult(modelId, latency) },
                    { dialog.isCancelled }
                )
                SwingUtilities.invokeLater {
                    if (results != null) {
                        // Update models with measurement results
                        val updatedModels = provider.models.map { m ->
                            val latency = results[m.id] ?: 0L
                            // Requirement 2: if measured as failed (0ms), mark as disabled
                            m.copy(
                                measured = true,
                                latencyMs = latency,
                                enabled = if (latency == 0L) false else m.enabled
                            )
                        }
                        val updatedProvider = provider.copy(models = updatedModels)
                        settings.addProvider(updatedProvider)
                        refreshProviderTable()
                        // Reselect
                        for (i in 0 until providerTableModel.rowCount) {
                            if (providerTableModel.getValueAt(i, 1) == provider.name) {
                                providerTable.setRowSelectionInterval(i, i)
                                break
                            }
                        }
                        refreshModelTable(updatedProvider)
                        val okCount = results.count { it.value > 0 }
                        val failCount = results.count { it.value == 0L }
                        setStatus("Measured: $okCount OK, $failCount failed/disabled")

                        if (dialog.isCancelled) {
                            dialog.finishCancelled(results.size)
                        } else {
                            dialog.finish(okCount, failCount)
                        }
                    } else {
                        dialog.finishCancelled(0)
                    }
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    setStatus("Measure error: ${e.message}")
                    dialog.finishCancelled(0)
                }
            }
        }
    }

    // ----------------------------------------------------------------
    // Save all (Requirement 8)
    // ----------------------------------------------------------------

    private fun saveAll() {
        // Save max steps
        settings.state.maxSteps = maxStepsField.text.trim().toIntOrNull() ?: 25

        // Save provider enabled states from table
        val providers = settings.getProviders().toMutableList()
        for (i in 0 until providerTableModel.rowCount) {
            val name = providerTableModel.getValueAt(i, 1)?.toString() ?: continue
            val enabled = providerTableModel.getValueAt(i, 0) as? Boolean ?: true
            val idx = providers.indexOfFirst { it.name == name }
            if (idx >= 0) {
                providers[idx] = providers[idx].copy(enabled = enabled)
            }
        }

        // Save model edits from model table for the selected provider
        val selectedProvider = getSelectedProvider()
        if (selectedProvider != null) {
            val updatedModels = mutableListOf<ModelInfo>()
            for (i in 0 until modelTableModel.rowCount) {
                val enabled = modelTableModel.getValueAt(i, 0) as? Boolean ?: true
                val name = modelTableModel.getValueAt(i, 1)?.toString() ?: ""
                val id = modelTableModel.getValueAt(i, 2)?.toString() ?: ""
                val typeStr = modelTableModel.getValueAt(i, 3)?.toString() ?: "medium"
                val costStr = modelTableModel.getValueAt(i, 4)?.toString() ?: "low-cost"
                val ctx = modelTableModel.getValueAt(i, 5)?.toString()?.toIntOrNull() ?: 32768
                val out = modelTableModel.getValueAt(i, 6)?.toString()?.toIntOrNull() ?: 4096

                val size = parseSize(typeStr)
                val cost = parseCost(costStr)

                // Find existing model to preserve measured/latency
                val existing = selectedProvider.models.find { it.id == id }
                updatedModels.add(ModelInfo(
                    id = id,
                    providerId = selectedProvider.id,
                    providerName = selectedProvider.name,
                    name = name,
                    sizeTag = size,
                    costTag = cost,
                    maxContextTokens = ctx,
                    maxOutputTokens = out,
                    enabled = enabled,
                    measured = existing?.measured ?: false,
                    latencyMs = existing?.latencyMs ?: 0
                ))
            }
            val idx = providers.indexOfFirst { it.id == selectedProvider.id }
            if (idx >= 0) {
                providers[idx] = providers[idx].copy(models = updatedModels)
            }
        }

        // Save default provider
        val defaultProviderName = defaultProviderCombo.selectedItem as? String
        providers.forEach { p ->
            providers[providers.indexOfFirst { it.id == p.id }] = p.copy(isDefault = (p.name == defaultProviderName))
        }
        settings.setProviders(providers)

        // Save default model (Requirement 8: ProviderName/ModelName)
        val defaultModel = defaultModelCombo.selectedItem as? String
        if (defaultModel != null) {
            settings.state.defaultModelDisplayName = defaultModel
            // Also set the legacy model field to the model ID for backward compat
            val parts = defaultModel.split("/", limit = 2)
            if (parts.size == 2) {
                val provName = parts[0]
                val modelName = parts[1]
                val provider = providers.find { it.name == provName }
                val model = provider?.models?.find { it.name == modelName }
                if (model != null && provider != null) {
                    settings.state.model = model.id
                    settings.state.baseUrl = provider.baseUrl
                    settings.setApiKey(provider.apiKey)
                    settings.state.maxContextTokens = model.maxContextTokens
                    settings.state.maxOutputTokens = model.maxOutputTokens
                    settings.state.defaultProviderId = provider.id
                }
            }
        }

        onSave()
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private fun getSelectedProvider(): ProviderConfig? {
        val row = providerTable.selectedRow
        if (row < 0) return null
        val modelRow = providerTable.convertRowIndexToModel(row)
        val name = providerTableModel.getValueAt(modelRow, 1)?.toString() ?: return null
        return settings.getProviders().find { it.name == name }
    }

    private fun syncFieldsFromSettings() {
        maxStepsField.text = settings.state.maxSteps.toString()
    }

    private fun setStatus(msg: String) {
        SwingUtilities.invokeLater { statusLabel.text = msg }
    }

    private fun parseSize(s: String): ModelSize = when (s.lowercase()) {
        "small" -> ModelSize.SMALL
        "medium" -> ModelSize.MEDIUM
        "large" -> ModelSize.LARGE
        "xl" -> ModelSize.XL
        else -> ModelSize.MEDIUM
    }

    private fun parseCost(s: String): ModelCost = when (s.lowercase()) {
        "free" -> ModelCost.FREE
        "low-cost", "low_cost" -> ModelCost.LOW_COST
        "medium-cost", "medium_cost" -> ModelCost.MEDIUM_COST
        "high-cost", "high_cost" -> ModelCost.HIGH_COST
        else -> ModelCost.LOW_COST
    }

    // ----------------------------------------------------------------
    // Callbacks (set by parent ChatToolWindowPanel)
    // ----------------------------------------------------------------

    var onSyncModels: (suspend (ProviderConfig) -> ProviderConfig?)? = null
    var onTestConnection: (suspend (ProviderConfig) -> ProviderManager.ConnectionTestResult?)? = null

    /**
     * Measure all models for a provider.
     * @param provider the provider whose models to measure
     * @param onProgress callback invoked after each model measurement (modelId, latencyMs)
     * @param isCancelled returns true if the user cancelled the operation
     * @return map of modelId -> latencyMs (only for measured models)
     */
    var onMeasureModels: (suspend (ProviderConfig, (String, Long) -> Unit, () -> Boolean) -> Map<String, Long>?)? = null

    /**
     * Called by parent after model sync completes — refreshes the UI.
     */
    fun onModelsSynced(provider: ProviderConfig) {
        refreshProviderTable()
        // Reselect the provider
        for (i in 0 until providerTableModel.rowCount) {
            if (providerTableModel.getValueAt(i, 1)?.toString() == provider.name) {
                providerTable.setRowSelectionInterval(i, i)
                break
            }
        }
        refreshModelTable(provider)
        refreshDefaultCombos()
    }

    /**
     * Get all models from all enabled providers as ProviderName/ModelName (Requirement 9).
     */
    fun getAllModelDisplayNames(): List<String> {
        return settings.getProviders()
            .filter { it.enabled }
            .flatMap { p -> p.models.filter { it.enabled }.map { "${p.name}/${it.name}" } }
    }

    // ----------------------------------------------------------------
    // Cell renderers and editors
    // ----------------------------------------------------------------

    private class CheckboxRenderer : JCheckBox(), TableCellRenderer {
        override fun getTableCellRendererComponent(
            table: JTable?, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, col: Int
        ): java.awt.Component {
            horizontalAlignment = SwingConstants.CENTER
            this.isSelected = value as? Boolean ?: false
            return this
        }
    }

    private class CheckboxEditor : AbstractCellEditor(), TableCellEditor {
        private val checkbox = JCheckBox()
        init {
            checkbox.horizontalAlignment = SwingConstants.CENTER
            checkbox.addActionListener { fireEditingStopped() }
        }
        override fun getTableCellEditorComponent(
            table: JTable?, value: Any?, isSelected: Boolean, row: Int, col: Int
        ): java.awt.Component {
            checkbox.isSelected = value as? Boolean ?: false
            return checkbox
        }
        override fun getCellEditorValue(): Any = checkbox.isSelected
    }

    private class ComboBoxEditor(items: Array<String>) : AbstractCellEditor(), TableCellEditor {
        private val combo = JComboBox(items)
        override fun getTableCellEditorComponent(
            table: JTable?, value: Any?, isSelected: Boolean, row: Int, col: Int
        ): java.awt.Component {
            combo.selectedItem = value
            return combo
        }
        override fun getCellEditorValue(): Any = combo.selectedItem ?: ""
    }
}
