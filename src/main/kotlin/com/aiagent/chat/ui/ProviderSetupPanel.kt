package com.aiagent.chat.ui

import com.aiagent.chat.model.AuthHeaderType
import com.aiagent.chat.model.ModelInfo
import com.aiagent.chat.model.ProviderConfig
import com.aiagent.chat.services.ChatStateService
import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

/**
 * Provider-only setup panel — no standalone base-url/api-key/model fields.
 * Everything is configured through providers and their models.
 * Per-model context and output token settings.
 * All UI is inline within the chat window — no popup dialogs.
 */
class ProviderSetupPanel(
    private val settings: ChatStateService,
    private val onSave: () -> Unit,
    private val onCancel: () -> Unit
) : JBPanel<ProviderSetupPanel>(BorderLayout()) {

    // --- Provider list ---
    private val providerListModel = DefaultListModel<ProviderConfig>()
    private val providerList = JList(providerListModel).apply {
        cellRenderer = ProviderListCellRenderer()
        selectionMode = ListSelectionModel.SINGLE_SELECTION
    }

    // --- Inline provider form ---
    private val formNameField = JBTextField()
    private val formUrlField = JBTextField()
    private val formKeyField = JPasswordField()
    private val formAuthCombo = JComboBox(arrayOf("Bearer (Authorization header)", "x-api-key header"))
    private val formCardPanel = JPanel(CardLayout())
    private val formCardLayout get() = formCardPanel.layout as CardLayout
    private val FORM_EMPTY = "EMPTY"
    private val FORM_EDIT = "EDIT"

    private var editingProviderId: String? = null

    // --- Model list for selected provider ---
    private val modelListModel = DefaultListModel<ModelInfo>()
    private val modelList = JList(modelListModel).apply {
        cellRenderer = ModelListCellRenderer()
        selectionMode = ListSelectionModel.SINGLE_SELECTION
    }

    // --- Per-model token fields ---
    private val modelContextTokensField = JBTextField()
    private val modelOutputTokensField = JBTextField()

    // --- Active model selector ---
    private val activeModelCombo = JComboBox<ModelInfo>()

    // --- Max steps (agent parameter, not per-model) ---
    private val maxStepsField = JBTextField()

    init {
        border = JBUI.Borders.empty(16)
        background = JBColor.PanelBackground

        val scrollContent = JPanel(GridLayout(0, 1, 0, 12)).apply { isOpaque = false }
        scrollContent.add(buildHeaderSection())
        scrollContent.add(buildProviderSection())
        scrollContent.add(buildModelSection())
        scrollContent.add(buildActiveModelSection())
        scrollContent.add(buildAgentParamsSection())

        val scrollPane = JBScrollPane(scrollContent).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        }
        add(scrollPane, BorderLayout.CENTER)
        add(buildButtonBar(), BorderLayout.SOUTH)

        // Wire up list selection
        providerList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                onProviderSelected()
            }
        }
        modelList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                onModelSelected()
            }
        }

        refreshProviderList()
        refreshActiveModelCombo()
        syncFieldsFromSettings()
    }

    // ----------------------------------------------------------------
    // Section builders
    // ----------------------------------------------------------------

    private fun buildHeaderSection(): JComponent {
        val panel = JPanel(BorderLayout()).apply { isOpaque = false }
        panel.add(JBLabel("Configure Providers").apply {
            font = font.deriveFont(java.awt.Font.BOLD, 16f)
        }, BorderLayout.WEST)
        panel.add(JBLabel("Set up your AI providers and models. Context and output sizes are per-model.").apply {
            font = font.deriveFont(java.awt.Font.PLAIN, 11f)
            foreground = JBColor(0x666666, 0x999999)
        }, BorderLayout.SOUTH)
        return panel
    }

    private fun buildProviderSection(): JComponent {
        val outer = JPanel(BorderLayout()).apply { isOpaque = false }
        outer.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border(), 1),
            JBUI.Borders.empty(12)
        )

        // Title row
        val titleRow = JPanel(BorderLayout()).apply { isOpaque = false }
        titleRow.add(JBLabel("Providers").apply {
            font = font.deriveFont(java.awt.Font.BOLD, 13f)
        }, BorderLayout.WEST)

        val addBtn = JButton("Add", AllIcons.General.Add).apply {
            isContentAreaFilled = false
            isBorderPainted = true
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            font = font.deriveFont(java.awt.Font.PLAIN, 11f)
            addActionListener { startAddProvider() }
        }
        titleRow.add(addBtn, BorderLayout.EAST)
        outer.add(titleRow, BorderLayout.NORTH)

        // Provider list (scrollable, fixed height)
        val listScroll = JBScrollPane(providerList).apply {
            border = JBUI.Borders.empty()
            preferredSize = java.awt.Dimension(0, 80)
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }
        outer.add(listScroll, BorderLayout.CENTER)

        // Inline form (below list)
        formCardPanel.isOpaque = false

        // Empty state
        val emptyForm = JPanel(FlowLayout(FlowLayout.CENTER)).apply { isOpaque = false }
        emptyForm.add(JBLabel("Click 'Add' to create a provider").apply {
            font = font.deriveFont(java.awt.Font.ITALIC, 11f)
            foreground = JBColor(0x999999, 0x666666)
        })
        formCardPanel.add(emptyForm, FORM_EMPTY)

        // Edit form
        val editForm = buildProviderEditForm()
        formCardPanel.add(editForm, FORM_EDIT)

        formCardLayout.show(formCardPanel, FORM_EMPTY)
        outer.add(formCardPanel, BorderLayout.SOUTH)

        return outer
    }

    private fun buildProviderEditForm(): JComponent {
        val panel = JPanel(GridLayout(0, 1, 4, 4)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(8, 0, 0, 0)
        }

        panel.add(JBLabel("Provider Name:").apply { font = font.deriveFont(java.awt.Font.PLAIN, 11f) })
        panel.add(formNameField)
        panel.add(JBLabel("Base URL:").apply { font = font.deriveFont(java.awt.Font.PLAIN, 11f) })
        panel.add(formUrlField)
        panel.add(JBLabel("API Key:").apply { font = font.deriveFont(java.awt.Font.PLAIN, 11f) })
        panel.add(formKeyField)
        panel.add(JBLabel("Auth Type:").apply { font = font.deriveFont(java.awt.Font.PLAIN, 11f) })
        panel.add(formAuthCombo)

        val btnRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply { isOpaque = false }
        val saveBtn = JButton("Save Provider").apply {
            font = font.deriveFont(java.awt.Font.PLAIN, 11f)
            addActionListener { saveProviderFromForm() }
        }
        val cancelBtn = JButton("Cancel").apply {
            font = font.deriveFont(java.awt.Font.PLAIN, 11f)
            addActionListener { cancelProviderForm() }
        }
        val removeBtn = JButton("Remove", AllIcons.Actions.Cancel).apply {
            font = font.deriveFont(java.awt.Font.PLAIN, 11f)
            foreground = JBColor(0xCC0000, 0xFF6666)
            addActionListener { removeSelectedProvider() }
        }
        btnRow.add(saveBtn)
        btnRow.add(cancelBtn)
        btnRow.add(removeBtn)
        panel.add(btnRow)

        return panel
    }

    private fun buildModelSection(): JComponent {
        val outer = JPanel(BorderLayout()).apply { isOpaque = false }
        outer.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border(), 1),
            JBUI.Borders.empty(12)
        )

        val titleRow = JPanel(BorderLayout()).apply { isOpaque = false }
        titleRow.add(JBLabel("Models (for selected provider)").apply {
            font = font.deriveFont(java.awt.Font.BOLD, 13f)
        }, BorderLayout.WEST)

        val syncBtn = JButton("Sync Models", AllIcons.Actions.Refresh).apply {
            isContentAreaFilled = false
            isBorderPainted = true
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            font = font.deriveFont(java.awt.Font.PLAIN, 11f)
            toolTipText = "Fetch available models from the provider API"
            addActionListener { syncModels() }
        }
        titleRow.add(syncBtn, BorderLayout.EAST)
        outer.add(titleRow, BorderLayout.NORTH)

        // Model list
        val listScroll = JBScrollPane(modelList).apply {
            border = JBUI.Borders.empty()
            preferredSize = java.awt.Dimension(0, 80)
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }
        outer.add(listScroll, BorderLayout.CENTER)

        // Per-model token settings (below model list)
        val tokenPanel = JPanel(GridLayout(0, 2, 8, 4)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(8, 0, 0, 0)
        }
        tokenPanel.add(JBLabel("Max Context Tokens:").apply { font = font.deriveFont(java.awt.Font.PLAIN, 11f) })
        tokenPanel.add(modelContextTokensField)
        tokenPanel.add(JBLabel("Max Output Tokens:").apply { font = font.deriveFont(java.awt.Font.PLAIN, 11f) })
        tokenPanel.add(modelOutputTokensField)

        val saveModelBtn = JButton("Apply to Model").apply {
            font = font.deriveFont(java.awt.Font.PLAIN, 11f)
            addActionListener { applyTokenSettingsToModel() }
        }
        val btnWrapper = JPanel(FlowLayout(FlowLayout.LEFT, 0, 4)).apply { isOpaque = false }
        btnWrapper.add(saveModelBtn)
        tokenPanel.add(btnWrapper)

        outer.add(tokenPanel, BorderLayout.SOUTH)

        return outer
    }

    private fun buildActiveModelSection(): JComponent {
        val outer = JPanel(BorderLayout()).apply { isOpaque = false }
        outer.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border(), 1),
            JBUI.Borders.empty(12)
        )

        outer.add(JBLabel("Active Model").apply {
            font = font.deriveFont(java.awt.Font.BOLD, 13f)
        }, BorderLayout.NORTH)

        val comboPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 4)).apply { isOpaque = false }
        comboPanel.add(activeModelCombo)
        outer.add(comboPanel, BorderLayout.CENTER)

        return outer
    }

    private fun buildAgentParamsSection(): JComponent {
        val outer = JPanel(GridLayout(0, 1, 4, 4)).apply {
            isOpaque = false
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(12)
            )
        }

        outer.add(JBLabel("Agent Parameters").apply {
            font = font.deriveFont(java.awt.Font.BOLD, 13f)
        })
        outer.add(JBLabel("Max Steps:").apply { font = font.deriveFont(java.awt.Font.PLAIN, 11f) })
        outer.add(maxStepsField)
        outer.add(JBLabel("Maximum agent reasoning steps per request (default: 25)").apply {
            font = font.deriveFont(java.awt.Font.ITALIC, 10f)
            foreground = JBColor(0x999999, 0x666666)
        })

        return outer
    }

    private fun buildButtonBar(): JComponent {
        val panel = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 8)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(8, 0, 0, 0)
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
    // Logic
    // ----------------------------------------------------------------

    private fun syncFieldsFromSettings() {
        maxStepsField.text = settings.state.maxSteps.toString()
    }

    private fun refreshProviderList() {
        providerListModel.clear()
        for (p in settings.getProviders()) {
            providerListModel.addElement(p)
        }
        refreshActiveModelCombo()
    }

    private fun refreshActiveModelCombo() {
        activeModelCombo.removeAllItems()
        for (p in settings.getProviders()) {
            for (m in p.models) {
                activeModelCombo.addItem(m)
            }
        }
        // Try to select current model
        val currentModel = settings.state.model
        for (i in 0 until activeModelCombo.itemCount) {
            val item = activeModelCombo.getItemAt(i)
            if (item.id == currentModel) {
                activeModelCombo.selectedIndex = i
                break
            }
        }
    }

    private fun onProviderSelected() {
        val provider = providerList.selectedValue ?: return
        editingProviderId = provider.id
        formNameField.text = provider.name
        formUrlField.text = provider.baseUrl
        formKeyField.text = provider.apiKey
        formAuthCombo.selectedIndex = if (provider.authHeaderType == AuthHeaderType.X_API_KEY) 1 else 0
        formCardLayout.show(formCardPanel, FORM_EDIT)

        // Update model list
        modelListModel.clear()
        for (m in provider.models) {
            modelListModel.addElement(m)
        }
    }

    private fun onModelSelected() {
        val model = modelList.selectedValue ?: return
        modelContextTokensField.text = model.maxContextTokens.toString()
        modelOutputTokensField.text = model.maxOutputTokens.toString()
    }

    private fun startAddProvider() {
        editingProviderId = null
        formNameField.text = ""
        formUrlField.text = ""
        formKeyField.text = ""
        formAuthCombo.selectedIndex = 0
        formCardLayout.show(formCardPanel, FORM_EDIT)
        formNameField.requestFocus()
    }

    private fun saveProviderFromForm() {
        val name = formNameField.text.trim()
        val url = formUrlField.text.trim()
        val key = String(formKeyField.password).trim()
        if (name.isEmpty() || url.isEmpty()) return

        val authType = if (formAuthCombo.selectedIndex == 1) AuthHeaderType.X_API_KEY else AuthHeaderType.BEARER
        val id = editingProviderId ?: "prov_${System.currentTimeMillis()}"
        val existing = settings.getProviders().find { it.id == id }
        val provider = ProviderConfig(
            id = id,
            name = name,
            baseUrl = url,
            apiKey = key,
            authHeaderType = authType,
            enabled = true,
            models = existing?.models ?: emptyList()
        )
        settings.addProvider(provider)
        refreshProviderList()

        // Select the saved provider
        for (i in 0 until providerListModel.size()) {
            if (providerListModel[i].id == id) {
                providerList.selectedIndex = i
                break
            }
        }
    }

    private fun cancelProviderForm() {
        formCardLayout.show(formCardPanel, FORM_EMPTY)
        editingProviderId = null
        providerList.clearSelection()
    }

    private fun removeSelectedProvider() {
        val provider = providerList.selectedValue ?: return
        settings.removeProvider(provider.id)
        refreshProviderList()
        formCardLayout.show(formCardPanel, FORM_EMPTY)
        modelListModel.clear()
    }

    private fun syncModels() {
        val provider = providerList.selectedValue ?: return
        // This will be handled by the parent panel via a callback
        // For now, we just signal that sync is needed
        onSyncModels?.invoke(provider)
    }

    private fun applyTokenSettingsToModel() {
        val provider = providerList.selectedValue ?: return
        val model = modelList.selectedValue ?: return
        val ctx = modelContextTokensField.text.trim().toIntOrNull() ?: 32768
        val out = modelOutputTokensField.text.trim().toIntOrNull() ?: 4096

        val updatedModels = provider.models.map { m ->
            if (m.id == model.id) m.copy(maxContextTokens = ctx, maxOutputTokens = out) else m
        }
        val updatedProvider = provider.copy(models = updatedModels)
        settings.addProvider(updatedProvider)
        refreshProviderList()

        // Reselect
        for (i in 0 until providerListModel.size()) {
            if (providerListModel[i].id == provider.id) {
                providerList.selectedIndex = i
                break
            }
        }
    }

    private fun saveAll() {
        // Save max steps
        settings.state.maxSteps = maxStepsField.text.trim().toIntOrNull() ?: 25

        // Set active model from combo
        val activeModel = activeModelCombo.selectedItem as? ModelInfo
        if (activeModel != null) {
            settings.state.model = activeModel.id
            settings.state.maxContextTokens = activeModel.maxContextTokens
            settings.state.maxOutputTokens = activeModel.maxOutputTokens

            // Find the provider for this model and set baseUrl/apiKey
            val provider = settings.getProviders().find { p -> p.models.any { it.id == activeModel.id } }
            if (provider != null) {
                settings.state.baseUrl = provider.baseUrl
                settings.setApiKey(provider.apiKey)
            }
        }

        onSave()
    }

    // ----------------------------------------------------------------
    // Callback for model sync (set by parent)
    // ----------------------------------------------------------------

    var onSyncModels: ((ProviderConfig) -> Unit)? = null

    /**
     * Called by parent after model sync completes — refreshes the UI.
     */
    fun onModelsSynced(provider: ProviderConfig) {
        refreshProviderList()
        for (i in 0 until providerListModel.size()) {
            if (providerListModel[i].id == provider.id) {
                providerList.selectedIndex = i
                break
            }
        }
    }

    // ----------------------------------------------------------------
    // Cell renderers
    // ----------------------------------------------------------------

    private class ProviderListCellRenderer : ListCellRenderer<ProviderConfig> {
        override fun getListCellRendererComponent(
            list: JList<out ProviderConfig>?,
            value: ProviderConfig?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): java.awt.Component {
            val label = JBLabel(value?.let { "${it.name}  |  ${it.baseUrl}  |  ${it.models.size} models" } ?: "")
            label.border = JBUI.Borders.empty(4, 8)
            if (isSelected) {
                label.background = JBColor(0xE8EAF0, 0x2A2D30)
                label.isOpaque = true
            } else {
                label.isOpaque = false
            }
            return label
        }
    }

    private class ModelListCellRenderer : ListCellRenderer<ModelInfo> {
        override fun getListCellRendererComponent(
            list: JList<out ModelInfo>?,
            value: ModelInfo?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): java.awt.Component {
            val label = JBLabel(value?.let {
                "${it.id}  |  ctx: ${it.maxContextTokens}  |  out: ${it.maxOutputTokens}"
            } ?: "")
            label.border = JBUI.Borders.empty(4, 8)
            if (isSelected) {
                label.background = JBColor(0xE8EAF0, 0x2A2D30)
                label.isOpaque = true
            } else {
                label.isOpaque = false
            }
            return label
        }
    }
}
