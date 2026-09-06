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
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagLayout
import java.awt.GridBagConstraints
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
        columnModel.getColumn(0).headerValue = ""
        columnModel.getColumn(0).preferredWidth = 30
        columnModel.getColumn(0).maxWidth = 30
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
    private val modelTable = object : JTable(modelTableModel) {
        override fun getToolTipText(e: java.awt.event.MouseEvent): String? {
            val row = rowAtPoint(e.point)
            if (row < 0) return null
            val modelRow = convertRowIndexToModel(row)
            val modelId = modelTableModel.getValueAt(modelRow, 2)?.toString() ?: return null
            val provider = settings.getProviders().find { p -> p.models.any { it.id == modelId } }
            val model = provider?.models?.find { it.id == modelId }
            return if (model != null && model.measured && model.latencyMs > 0) {
                "Measured latency: " + model.latencyMs + "ms"
            } else {
                "Not measured"
            }
        }
    }.apply {
        columnModel.getColumn(0).headerValue = ""
        columnModel.getColumn(0).preferredWidth = 30
        columnModel.getColumn(0).maxWidth = 30
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

    // --- Model section title (updates with selected provider) ---
    private val modelSectionLabel = JBLabel("Models").apply {
        font = font.deriveFont(java.awt.Font.BOLD, 13f)
    }

    // --- General params table ---
    private val generalParamsTableModel = DefaultTableModel(0, 2).apply {
        setColumnIdentifiers(arrayOf("Parameter", "Value"))
        addRow(arrayOf("Default Provider", ""))
        addRow(arrayOf("Default Model", ""))
        addRow(arrayOf("Max Steps", "25"))
    }
    private val generalParamsTable = object : JTable(generalParamsTableModel) {
        override fun getCellEditor(row: Int, column: Int): TableCellEditor {
            if (column == 1) {
                return when (row) {
                    0 -> DefaultCellEditor(defaultProviderCombo)
                    1 -> DefaultCellEditor(defaultModelCombo)
                    else -> DefaultCellEditor(maxStepsField)
                }
            }
            return super.getCellEditor(row, column)
        }
        override fun isCellEditable(row: Int, column: Int): Boolean = column == 1
    }.apply {
        columnModel.getColumn(0).preferredWidth = 120
        columnModel.getColumn(1).preferredWidth = 200
        rowHeight = 24
    }

    // --- Currently selected provider for model table display ---
    private var selectedProviderId: String? = null

    // Flag to suppress table model listener during programmatic updates
    private var suppressTableListener = false

    // --- Scroll pane references for dynamic sizing ---
    private var providerTableScroll: JBScrollPane? = null
    private var modelTableScroll: JBScrollPane? = null
    private var generalParamsTableScroll: JBScrollPane? = null

    // --- Provider edit popup fields ---
    private val popupNameField = JBTextField()
    private val popupUrlCombo = JComboBox<String>().apply {
        isEditable = true
        addItem("http://100.102.112.77")
        addItem("http://models.ascend.huawei.com/v1")
        addItem("https://aigateway.csitool.rnd.huawei.com/v1")
        addItem("http://techdev.hicomputing.huawei.com:18000/v1")
    }
    private val popupKeyField = JPasswordField()
    private val popupErrorLabel = JBLabel(" ").apply {
        font = font.deriveFont(java.awt.Font.PLAIN, 11f)
        foreground = JBColor(0xCC0000, 0xFF6666)
    }

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
                    modelSectionLabel.text = "Models of: ${provider.name}"
                    refreshModelTable(provider)
                }
            }
        }

        // Auto-refresh models when provider table cells are edited (URL, Key, Name, Enabled)
        providerTableModel.addTableModelListener { e ->
            if (suppressTableListener) return@addTableModelListener
            if (e.type == javax.swing.event.TableModelEvent.UPDATE) {
                val row = e.firstRow
                if (row < 0 || row >= providerTableModel.rowCount) return@addTableModelListener
                val name = providerTableModel.getValueAt(row, 1)?.toString() ?: return@addTableModelListener
                // Find the provider by name and trigger a model refresh
                val provider = settings.getProviders().find { it.name == name }
                if (provider != null) {
                    // Read the edited URL from the table
                    val editedUrl = providerTableModel.getValueAt(row, 2)?.toString()?.trim() ?: ""
                    val editedKeyCell = providerTableModel.getValueAt(row, 3)?.toString()?.trim() ?: ""
                    val updatedProvider = provider.copy(
                        baseUrl = if (editedUrl.isNotEmpty()) editedUrl else provider.baseUrl,
                        apiKey = if (editedKeyCell.isNotEmpty() && editedKeyCell != "***") editedKeyCell else provider.apiKey
                    )
                    autoRefreshModels(updatedProvider)
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
     * Contains: Default Provider, Default Model, Max Steps — displayed as a table
     * with the same look as the provider and model tables.
     */
    private fun buildGeneralParamsSection(): JComponent {
        val outer = JPanel(BorderLayout(0, 4)).apply { isOpaque = false }
        outer.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border(), 1),
            JBUI.Borders.empty(6)
        )

        // Title
        outer.add(JBLabel("AI Agent General Parameters").apply {
            font = font.deriveFont(java.awt.Font.BOLD, 13f)
        }, BorderLayout.NORTH)

        // Table in scroll — sized dynamically to content
        val tableScroll = JBScrollPane(generalParamsTable).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
        }
        generalParamsTableScroll = tableScroll
        outer.add(tableScroll, BorderLayout.CENTER)

        // Wire default provider combo action
        defaultProviderCombo.addActionListener {
            val selectedName = defaultProviderCombo.selectedItem as? String ?: return@addActionListener
            val providers = settings.getProviders()
            providers.forEach { p ->
                val updated = p.copy(isDefault = (p.name == selectedName))
                settings.addProvider(updated)
            }
        }

        return outer
    }

    private fun buildProviderSection(): JComponent {
        val outer = JPanel(BorderLayout(0, 4)).apply { isOpaque = false }
        outer.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border(), 1),
            JBUI.Borders.empty(6)
        )

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

        // Provider table in scroll — sized dynamically to content
        val tableScroll = JBScrollPane(providerTable).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
        }
        providerTableScroll = tableScroll
        outer.add(tableScroll, BorderLayout.CENTER)

        return outer
    }

    private fun buildModelSection(): JComponent {
        val outer = JPanel(BorderLayout(0, 4)).apply { isOpaque = false }
        outer.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border(), 1),
            JBUI.Borders.empty(6)
        )

        // Title row with buttons
        val titleRow = JPanel(BorderLayout()).apply { isOpaque = false }
        titleRow.add(modelSectionLabel, BorderLayout.WEST)

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
        val measureBtn = JButton("Measure Timing").apply {
            font = font.deriveFont(java.awt.Font.PLAIN, 11f)
            toolTipText = "Measure TEE timing for all models of selected provider"
            addActionListener { measureModelsForSelected() }
        }
        btnPanel.add(testBtn)
        btnPanel.add(syncBtn)
        btnPanel.add(measureBtn)
        titleRow.add(btnPanel, BorderLayout.EAST)
        outer.add(titleRow, BorderLayout.NORTH)

        // Model table in scroll — sized dynamically to content
        val tableScroll = JBScrollPane(modelTable).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
        }
        modelTableScroll = tableScroll
        outer.add(tableScroll, BorderLayout.CENTER)

        return outer
    }

    private fun buildButtonBar(): JComponent {
        val panel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 4)).apply {
            isOpaque = false
        }

        val cancelBtn = JButton("Cancel Changes").apply {
            font = font.deriveFont(java.awt.Font.PLAIN, 12f)
            addActionListener { onCancel() }
        }
        val saveBtn = JButton("Save Settings", AllIcons.Actions.Commit).apply {
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
        suppressTableListener = true
        providerTableModel.rowCount = 0
        for (p in settings.getProviders()) {
            providerTableModel.addRow(arrayOf(
                p.enabled,
                p.name,
                p.baseUrl,
                if (p.apiKey.isNotBlank()) "***" else ""
            ))
        }
        suppressTableListener = false
        // Set checkbox renderer/editor for Enabled column
        providerTable.columnModel.getColumn(0).cellRenderer = CheckboxRenderer()
        providerTable.columnModel.getColumn(0).cellEditor = CheckboxEditor()
        resizeTableScroll(providerTableScroll, providerTable)
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
        modelTable.columnModel.getColumn(3).cellEditor = ComboBoxEditor(arrayOf("small", "medium", "large", "X-Large"))
        modelTable.columnModel.getColumn(4).cellEditor = ComboBoxEditor(arrayOf("free", "low-cost", "medium-cost", "high-cost"))
        resizeTableScroll(modelTableScroll, modelTable)
    }

    /**
     * Dynamically size a table's scroll pane to fit all rows without scroll bars.
     * Falls back to a max height cap so very large tables still scroll.
     */
    private fun resizeTableScroll(scroll: JBScrollPane?, table: JTable) {
        if (scroll == null) return
        val rowCount = table.rowCount
        if (rowCount == 0) {
            scroll.preferredSize = java.awt.Dimension(0, 28) // header only
        } else {
            val headerHeight = table.tableHeader.preferredSize.height
            val rowsHeight = rowCount * table.rowHeight
            val totalHeight = headerHeight + rowsHeight + 4
            // Cap at 300px so very large tables still scroll
            val cappedHeight = totalHeight.coerceAtMost(300)
            scroll.preferredSize = java.awt.Dimension(0, cappedHeight)
        }
        scroll.revalidate()
        scroll.repaint()
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

        // Sync general params table value cells from the combos
        syncGeneralParamsTable()
    }

    /**
     * Sync the Value column of the general params table from the combo boxes and max steps field.
     */
    private fun syncGeneralParamsTable() {
        val providerVal = defaultProviderCombo.selectedItem as? String ?: ""
        val modelVal = defaultModelCombo.selectedItem as? String ?: ""
        val stepsVal = maxStepsField.text
        if (generalParamsTableModel.rowCount >= 3) {
            generalParamsTableModel.setValueAt(providerVal, 0, 1)
            generalParamsTableModel.setValueAt(modelVal, 1, 1)
            generalParamsTableModel.setValueAt(stepsVal, 2, 1)
        }
        resizeTableScroll(generalParamsTableScroll, generalParamsTable)
    }

    // ----------------------------------------------------------------
    // Add provider popup (Requirement 10)
    // ----------------------------------------------------------------

    private fun showAddProviderPopup() {
        popupNameField.text = ""
        popupUrlCombo.selectedIndex = 0
        popupUrlCombo.editor.item = ""
        popupKeyField.text = ""
        popupErrorLabel.text = " "

        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(2, 2, 2, 2)
            weightx = 1.0
        }

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0
        panel.add(JBLabel("Provider Name:"), gbc)
        gbc.gridx = 1; gbc.gridy = 0; gbc.weightx = 1.0
        panel.add(popupNameField, gbc)

        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0
        panel.add(JBLabel("Base URL:"), gbc)
        gbc.gridx = 1; gbc.gridy = 1; gbc.weightx = 1.0
        panel.add(popupUrlCombo, gbc)

        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0.0
        panel.add(JBLabel("API Key:"), gbc)
        gbc.gridx = 1; gbc.gridy = 2; gbc.weightx = 1.0
        panel.add(popupKeyField, gbc)

        gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 2; gbc.weightx = 1.0
        panel.add(popupErrorLabel, gbc)

        // Auto-fill API key when URL changes
        val autoFillKey = {
            val url = (popupUrlCombo.editor.item as? String ?: popupUrlCombo.selectedItem as? String ?: "").trim()
            if (url == "http://100.102.112.77") {
                popupKeyField.text = "sk-1234"
            }
        }
        popupUrlCombo.addActionListener {
            if (popupUrlCombo.selectedItem != null) autoFillKey()
        }
        popupUrlCombo.editor.item = "http://100.102.112.77"
        autoFillKey()

        val parentFrame = SwingUtilities.getWindowAncestor(this) as? JFrame

        val dialog = JDialog(parentFrame, "Add Provider", true).apply {
            isResizable = false
            defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE
            val content = JPanel(BorderLayout(8, 8)).apply {
                border = JBUI.Borders.empty(16)
                background = JBColor.PanelBackground
            }
            content.add(panel, BorderLayout.CENTER)

            val btnPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply { isOpaque = false }
            val cancelBtn = JButton("Cancel").apply {
                addActionListener { dispose() }
            }
            val okBtn = JButton("OK").apply {
                addActionListener {
                    val name = popupNameField.text.trim()
                    val url = (popupUrlCombo.editor.item as? String ?: popupUrlCombo.selectedItem as? String ?: "").trim()
                    val key = String(popupKeyField.password).trim()

                    if (name.isEmpty()) {
                        popupErrorLabel.text = "Provider name is required."
                        return@addActionListener
                    }
                    if (url.isEmpty()) {
                        popupErrorLabel.text = "Base URL is required."
                        return@addActionListener
                    }
                    if (key.isEmpty()) {
                        popupErrorLabel.text = "API key is required for this provider."
                        return@addActionListener
                    }

                    // Validation passed — add the provider
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

                    // Auto-start refresh models on OK
                    setStatus("Auto-syncing models for '$name'...")
                    scope.launch {
                        try {
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

                    dispose()
                }
            }
            btnPanel.add(cancelBtn)
            btnPanel.add(okBtn)
            content.add(btnPanel, BorderLayout.SOUTH)
            contentPane = content
            pack()
            setLocationRelativeTo(parentFrame)
            rootPane.defaultButton = okBtn
        }
        dialog.isVisible = true
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

        // Read edited values from the table (in case user edited URL/key but hasn't saved yet)
        val row = providerTable.selectedRow
        val modelRow = if (row >= 0) providerTable.convertRowIndexToModel(row) else -1
        val editedProvider = if (modelRow >= 0) {
            val editedUrl = providerTableModel.getValueAt(modelRow, 2)?.toString()?.trim() ?: provider.baseUrl
            val editedKeyCell = providerTableModel.getValueAt(modelRow, 3)?.toString()?.trim() ?: ""
            val newKey = if (editedKeyCell.isNotEmpty() && editedKeyCell != "***") editedKeyCell else provider.apiKey
            provider.copy(baseUrl = editedUrl, apiKey = newKey)
        } else provider

        val parentFrame = SwingUtilities.getWindowAncestor(this) as? JFrame

        // Show "testing" popup
        val testDialog = JDialog(parentFrame, "Testing Connection", false).apply {
            defaultCloseOperation = JDialog.DO_NOTHING_ON_CLOSE
            isResizable = false
            val content = JPanel(BorderLayout(8, 8)).apply {
                border = JBUI.Borders.empty(16)
                background = JBColor.PanelBackground
            }
            val msgLabel = JBLabel("Testing connection to '${editedProvider.name}'...").apply {
                font = font.deriveFont(java.awt.Font.PLAIN, 13f)
            }
            val urlLabel = JBLabel("URL: ${editedProvider.baseUrl}").apply {
                font = font.deriveFont(java.awt.Font.PLAIN, 11f)
                foreground = JBColor(0x666666, 0x999999)
            }
            val progressBar = JProgressBar().apply {
                isIndeterminate = true
                preferredSize = Dimension(300, 20)
            }
            content.add(msgLabel, BorderLayout.NORTH)
            content.add(urlLabel, BorderLayout.CENTER)
            content.add(progressBar, BorderLayout.SOUTH)
            contentPane = content
            pack()
            setLocationRelativeTo(parentFrame)
        }
        testDialog.isVisible = true

        setStatus("Testing connection to '${editedProvider.name}'...")
        scope.launch {
            val result = onTestConnection?.invoke(editedProvider)
            SwingUtilities.invokeLater {
                testDialog.dispose()

                if (result?.success == true && result.authType != null) {
                    // Auto-set auth type (Requirement 3)
                    val updated = editedProvider.copy(authHeaderType = result.authType)
                    settings.addProvider(updated)
                    setStatus("Connection OK (${result.latencyMs}ms), auth: ${result.authType}")

                    // Show result popup
                    showResultPopup(parentFrame, "Connection Successful",
                        "Provider: ${editedProvider.name}\n" +
                        "URL: ${editedProvider.baseUrl}\n" +
                        "Latency: ${result.latencyMs}ms\n" +
                        "Auth Type: ${result.authType}\n\n" +
                        "Models will be refreshed automatically.")

                    // Requirement 4: auto-fetch models after successful test
                    syncModelsForProvider(updated)
                } else {
                    setStatus("Connection failed: ${result?.message ?: "unknown error"}")
                    showResultPopup(parentFrame, "Connection Failed",
                        "Provider: ${editedProvider.name}\n" +
                        "URL: ${editedProvider.baseUrl}\n" +
                        "Error: ${result?.message ?: "unknown error"}")
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
        val parentFrame = SwingUtilities.getWindowAncestor(this) as? JFrame

        // Show "refreshing" popup
        val syncDialog = JDialog(parentFrame, "Refreshing Models", false).apply {
            defaultCloseOperation = JDialog.DO_NOTHING_ON_CLOSE
            isResizable = false
            val content = JPanel(BorderLayout(8, 8)).apply {
                border = JBUI.Borders.empty(16)
                background = JBColor.PanelBackground
            }
            val msgLabel = JBLabel("Refreshing models from '${provider.name}'...").apply {
                font = font.deriveFont(java.awt.Font.PLAIN, 13f)
            }
            val urlLabel = JBLabel("URL: ${provider.baseUrl}").apply {
                font = font.deriveFont(java.awt.Font.PLAIN, 11f)
                foreground = JBColor(0x666666, 0x999999)
            }
            val progressBar = JProgressBar().apply {
                isIndeterminate = true
                preferredSize = Dimension(300, 20)
            }
            content.add(msgLabel, BorderLayout.NORTH)
            content.add(urlLabel, BorderLayout.CENTER)
            content.add(progressBar, BorderLayout.SOUTH)
            contentPane = content
            pack()
            setLocationRelativeTo(parentFrame)
        }
        syncDialog.isVisible = true

        setStatus("Syncing models for '${provider.name}'...")
        scope.launch {
            try {
                val synced = onSyncModels?.invoke(provider)
                SwingUtilities.invokeLater {
                    syncDialog.dispose()
                    if (synced != null) {
                        onModelsSynced(synced)
                        setStatus("Synced ${synced.models.size} models from '${provider.name}'")
                        showResultPopup(parentFrame, "Models Refreshed",
                            "Provider: ${provider.name}\n" +
                            "URL: ${provider.baseUrl}\n" +
                            "Models found: ${synced.models.size}")
                    } else {
                        setStatus("Sync failed")
                        showResultPopup(parentFrame, "Model Refresh Failed",
                            "Provider: ${provider.name}\n" +
                            "URL: ${provider.baseUrl}\n" +
                            "Error: Could not fetch models. Check URL and API key.")
                    }
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    syncDialog.dispose()
                    setStatus("Sync error: ${e.message}")
                    showResultPopup(parentFrame, "Model Refresh Error",
                        "Provider: ${provider.name}\n" +
                        "URL: ${provider.baseUrl}\n" +
                        "Error: ${e.message}")
                }
            }
        }
    }

    /**
     * Auto-refresh models when provider settings are edited in the table.
     * Shows a popup during the refresh.
     */
    private fun autoRefreshModels(provider: ProviderConfig) {
        val parentFrame = SwingUtilities.getWindowAncestor(this) as? JFrame

        // Show "refreshing" popup
        val refreshDialog = JDialog(parentFrame, "Refreshing Models", false).apply {
            defaultCloseOperation = JDialog.DO_NOTHING_ON_CLOSE
            isResizable = false
            val content = JPanel(BorderLayout(8, 8)).apply {
                border = JBUI.Borders.empty(16)
                background = JBColor.PanelBackground
            }
            val msgLabel = JBLabel("Provider settings changed. Refreshing models from '${provider.name}'...").apply {
                font = font.deriveFont(java.awt.Font.PLAIN, 13f)
            }
            val urlLabel = JBLabel("URL: ${provider.baseUrl}").apply {
                font = font.deriveFont(java.awt.Font.PLAIN, 11f)
                foreground = JBColor(0x666666, 0x999999)
            }
            val progressBar = JProgressBar().apply {
                isIndeterminate = true
                preferredSize = Dimension(350, 20)
            }
            content.add(msgLabel, BorderLayout.NORTH)
            content.add(urlLabel, BorderLayout.CENTER)
            content.add(progressBar, BorderLayout.SOUTH)
            contentPane = content
            pack()
            setLocationRelativeTo(parentFrame)
        }
        refreshDialog.isVisible = true

        setStatus("Auto-refreshing models for '${provider.name}' (settings changed)...")
        scope.launch {
            try {
                val synced = onSyncModels?.invoke(provider)
                SwingUtilities.invokeLater {
                    refreshDialog.dispose()
                    if (synced != null) {
                        // Update the provider in settings with synced models
                        settings.addProvider(synced)
                        onModelsSynced(synced)
                        setStatus("Auto-refreshed ${synced.models.size} models from '${provider.name}'")
                        showResultPopup(parentFrame, "Models Refreshed",
                            "Provider: ${provider.name}\n" +
                            "URL: ${provider.baseUrl}\n" +
                            "Models found: ${synced.models.size}\n\n" +
                            "Model list updated with new settings.")
                    } else {
                        setStatus("Auto-refresh failed for '${provider.name}'")
                        showResultPopup(parentFrame, "Model Refresh Failed",
                            "Provider: ${provider.name}\n" +
                            "URL: ${provider.baseUrl}\n" +
                            "Error: Could not fetch models. Check URL and API key.")
                    }
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    refreshDialog.dispose()
                    setStatus("Auto-refresh error: ${e.message}")
                    showResultPopup(parentFrame, "Model Refresh Error",
                        "Provider: ${provider.name}\n" +
                        "URL: ${provider.baseUrl}\n" +
                        "Error: ${e.message}")
                }
            }
        }
    }

    /**
     * Show a modal result popup with an OK button.
     */
    private fun showResultPopup(parent: JFrame?, title: String, message: String) {
        val dialog = JDialog(parent, title, true).apply {
            isResizable = false
            val content = JPanel(BorderLayout(8, 8)).apply {
                border = JBUI.Borders.empty(16)
                background = JBColor.PanelBackground
            }
            val textArea = JTextArea(message).apply {
                isEditable = false
                background = JBColor.PanelBackground
                font = font.deriveFont(java.awt.Font.PLAIN, 12f)
                border = JBUI.Borders.empty()
            }
            val okBtn = JButton("OK").apply {
                font = font.deriveFont(java.awt.Font.PLAIN, 12f)
                addActionListener { dispose() }
            }
            val btnPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply { isOpaque = false }
            btnPanel.add(okBtn)
            content.add(textArea, BorderLayout.CENTER)
            content.add(btnPanel, BorderLayout.SOUTH)
            contentPane = content
            pack()
            setLocationRelativeTo(parent)
        }
        dialog.isVisible = true
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
                    { modelId -> dialog.setCurrentModel(modelId) },
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

        // Save provider enabled states, URL, and key from table
        val providers = settings.getProviders().toMutableList()
        for (i in 0 until providerTableModel.rowCount) {
            val name = providerTableModel.getValueAt(i, 1)?.toString() ?: continue
            val enabled = providerTableModel.getValueAt(i, 0) as? Boolean ?: true
            val url = providerTableModel.getValueAt(i, 2)?.toString()?.trim() ?: ""
            val keyCell = providerTableModel.getValueAt(i, 3)?.toString()?.trim() ?: ""
            val idx = providers.indexOfFirst { it.name == name }
            if (idx >= 0) {
                val existing = providers[idx]
                // Only update key if user typed something new (not the "***" mask)
                val newKey = if (keyCell.isNotEmpty() && keyCell != "***") keyCell else existing.apiKey
                providers[idx] = existing.copy(
                    enabled = enabled,
                    baseUrl = if (url.isNotEmpty()) url else existing.baseUrl,
                    apiKey = newKey
                )
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
        syncGeneralParamsTable()
    }

    private fun setStatus(msg: String) {
        SwingUtilities.invokeLater { statusLabel.text = msg }
    }

    private fun parseSize(s: String): ModelSize = when (s.lowercase()) {
        "small" -> ModelSize.SMALL
        "medium" -> ModelSize.MEDIUM
        "large" -> ModelSize.LARGE
        "xl", "x-large", "xlarge" -> ModelSize.XL
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
     * @param onMeasuring callback invoked before each model measurement (modelId)
     * @param onProgress callback invoked after each model measurement (modelId, latencyMs)
     * @param isCancelled returns true if the user cancelled the operation
     * @return map of modelId -> latencyMs (only for measured models)
     */
    var onMeasureModels: (suspend (ProviderConfig, (String) -> Unit, (String, Long) -> Unit, () -> Boolean) -> Map<String, Long>?)? = null

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
        modelSectionLabel.text = "Models of: ${provider.name}"
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
