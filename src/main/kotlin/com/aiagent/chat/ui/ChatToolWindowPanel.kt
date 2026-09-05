package com.aiagent.chat.ui

import com.aiagent.chat.agent.AgentCommand
import com.aiagent.chat.agent.AgentDelta
import com.aiagent.chat.agent.AgentEngine
import com.aiagent.chat.agent.AgentSessionState
import com.aiagent.chat.agent.CommandQueue
import com.aiagent.chat.agent.ContextCompactor
import com.aiagent.chat.agent.SessionStateMachine
import com.aiagent.chat.agent.UsageTracker
import com.aiagent.chat.debug.DebugLog
import com.aiagent.chat.model.ChatMessage
import com.aiagent.chat.model.MessageRole
import com.aiagent.chat.model.SessionState
import com.aiagent.chat.model.TodoItem
import com.aiagent.chat.model.ToolCategory
import com.aiagent.chat.model.ApprovalMode
import com.aiagent.chat.model.Usage
import com.aiagent.chat.model.UiLogEntry
import com.aiagent.chat.net.ApiClient
import com.aiagent.chat.persistence.PersistenceManager
import com.aiagent.chat.services.ChatStateService
import com.aiagent.chat.tools.PlatformToolHandler
import com.aiagent.chat.tools.SlashCommands
import com.aiagent.chat.tools.SlashCommandContext
import com.aiagent.chat.tools.SlashCommandAction
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.*
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.dsl.builder.*
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.*
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import javax.swing.*
import com.intellij.openapi.project.DumbAware

class ChatToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ChatToolWindowPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}

class ChatToolWindowPanel(private val project: Project) : JBPanel<ChatToolWindowPanel>(CardLayout()) {
    private val cardLayout = layout as CardLayout
    private val CHAT_CARD = "CHAT_CARD"
    private val SETUP_CARD = "SETUP_CARD"

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val persistence = PersistenceManager(project.basePath ?: "")
    private val settings = ApplicationManager.getApplication().getService(ChatStateService::class.java)

    private val conversationTabPanel = ConversationTabPanel()
    private val todoListPanel = TodoListPanel()

    private val enhancedInputPanel = EnhancedInputPanel(
        project = project,
        onSubmit = { text, fileTags -> handlePromptSubmit(text, fileTags) },
        onSteer = { text ->
            if (activeEngineJob?.isActive == true) {
                addMessageBubbleToActiveTab("user (steering)", text)
                // Use command queue for steering (high priority, processed at next loop iteration)
                commandQueue.enqueue(AgentCommand.Steer(text))
                // Also keep legacy queue for backward compat with steerProvider
                pendingSteerMessages.add(text)
            }
        },
        onStop = { handleStop() },
        isRunning = { activeEngineJob?.isActive == true },
        currentModel = { settings.state.model },
        onModelChange = { newModel ->
            settings.state.model = newModel
            conversationTabPanel.updateModelStatus(newModel)
        }
    )

    private val statusLabel = JBLabel("Ready")

    private val baseUrlField = JBTextField()
    private val modelField = JBTextField()
    private val apiKeyField = JPasswordField()

    private var activeEngineJob: Job? = null
    private var activeConversationId: String? = null
    private var currentPhase = "execution"

    // --- Approval mode (strict/balanced/permissive/autopilot) ---
    private var approvalMode: ApprovalMode = ApprovalMode.BALANCED

    // --- State machine + command queue (agent-improvements) ---
    private val stateMachine = SessionStateMachine()
    private val commandQueue = CommandQueue()

    // --- Plan manager (tool-expansion) ---
    private val planManager = com.aiagent.chat.agent.PlanManager()

    // --- Multi-provider manager (multi-provider architecture) ---
    private val providerManager = com.aiagent.chat.model.ProviderManager()

    // --- Usage tracking (context/token/memory summary UI) ---
    private val usageTracker = UsageTracker(maxContextTokens = settings.state.maxContextTokens.coerceAtLeast(1024))
    private val usageCounterPanel = UsageCounterPanel(maxContextTokens = settings.state.maxContextTokens.coerceAtLeast(1024))

    private var todoList: List<TodoItem> = emptyList()
    private val pendingSteerMessages = java.util.concurrent.ConcurrentLinkedQueue<String>()

    private var activeStreamingPanel: StreamingResponsePanel? = null

    private val toolHandler = PlatformToolHandler(
        project = project,
        getMemory = { persistence.loadFolderMemory() },
        setMemory = { persistence.saveFolderMemory(it) },
        getGlobalMemory = { persistence.loadGlobalMemory() },
        setGlobalMemory = { persistence.saveGlobalMemory(it) },
        getTodoList = { todoList },
        setTodoList = {
            todoList = it
            SwingUtilities.invokeLater { todoListPanel.updateItems(it) }
        },
        approvalHandler = object : PlatformToolHandler.ApprovalHandler {
            override fun requestApproval(toolName: String, toolArgs: String, category: ToolCategory): PlatformToolHandler.ApprovalResult {
                val latch = java.util.concurrent.CountDownLatch(1)
                var result = PlatformToolHandler.ApprovalResult(false, false)

                SwingUtilities.invokeLater {
                    val approvalPanel = ToolApprovalPanel(
                        toolName = toolName,
                        toolArgs = toolArgs,
                        onApprove = { autoApprove ->
                            result = PlatformToolHandler.ApprovalResult(true, autoApprove)
                            latch.countDown()
                        },
                        onReject = {
                            result = PlatformToolHandler.ApprovalResult(false, false, denyReason = "User rejected in dialog")
                            latch.countDown()
                        }
                    )

                    val conv = conversationTabPanel.getActiveConversation()
                    if (conv != null) {
                        conv.messageContainer.remove(conv.fillerComponent)
                        val constraints = GridBagConstraints().apply {
                            gridx = 0
                            gridy = conv.currentRow++
                            weightx = 1.0
                            weighty = 0.0
                            fill = GridBagConstraints.HORIZONTAL
                            anchor = GridBagConstraints.NORTH
                            insets = JBUI.insets(6, 8, 6, 8)
                        }
                        conv.messageContainer.add(approvalPanel, constraints)
                        conv.fillerGbc.gridy = conv.currentRow
                        conv.messageContainer.add(conv.fillerComponent, conv.fillerGbc)
                        conv.messageContainer.revalidate()
                        conv.messageContainer.repaint()

                        val scrollBar = conv.scrollPane.verticalScrollBar
                        scrollBar.value = scrollBar.maximum
                    }
                }

                try {
                    latch.await(120, java.util.concurrent.TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    // Timeout or interruption - default to rejected
                }

                return result
            }
        },
        approvalMode = approvalMode,
        askQuestionsHandler = com.aiagent.chat.tools.AskQuestionsHandler(project),
        planManager = planManager
    )

    init {
        conversationTabPanel.newConversation("Session 1")

        // Restore approval mode from saved settings
        approvalMode = try {
            ApprovalMode.valueOf(settings.state.approvalMode)
        } catch (_: Exception) {
            ApprovalMode.BALANCED
        }
        toolHandler.approvalMode = approvalMode

        // Wire inline question handler to add panels inside the chat message area
        toolHandler.askQuestionsHandler?.inlineComponentAdder = { panel, gbc ->
            val conv = conversationTabPanel.getActiveConversation()
            if (conv != null) {
                conv.messageContainer.remove(conv.fillerComponent)
                conv.messageContainer.add(panel, gbc)
                conv.fillerGbc.gridy = GridBagConstraints.RELATIVE
                conv.messageContainer.add(conv.fillerComponent, conv.fillerGbc)
                conv.messageContainer.revalidate()
                conv.messageContainer.repaint()
                val scrollBar = conv.scrollPane.verticalScrollBar
                scrollBar.value = scrollBar.maximum
            }
        }

        conversationTabPanel.onTabChanged = { _ -> }

        conversationTabPanel.onNewTab = {
            val tabCount = conversationTabPanel.getAllConversations().size
            conversationTabPanel.newConversation("Session ${tabCount + 1}")
            todoList = emptyList()
            todoListPanel.updateItems(emptyList())
        }

        conversationTabPanel.onMenuClick = { source -> showMenuPopup(source) }
        conversationTabPanel.onModelStatusClick = { source -> showModelStatusPopup(source) }
        conversationTabPanel.onRenameRequest = { _ ->
            val activeTabId = conversationTabPanel.getActiveTabId()
            val activeConv = activeTabId?.let { conversationTabPanel.getConversation(it) }
            if (activeConv != null) {
                val renamePanel = InlineRenamePanel(
                    currentName = activeConv.title,
                    onRename = { newName -> conversationTabPanel.renameConversation(activeTabId, newName) }
                )
                addComponentToActiveTab(renamePanel)
            }
        }

        val chatPanel = JPanel(BorderLayout())
        chatPanel.background = JBColor.PanelBackground

        // No in-panel header bar — CLion's tool window tab already shows "AI Agent Chat"
        // from plugin.xml. The menu button lives in the conversation tab bar to save space.
        chatPanel.add(conversationTabPanel, BorderLayout.CENTER)

        val bottomPanel = buildBottomPanel()
        chatPanel.add(bottomPanel, BorderLayout.SOUTH)

        val setupPanel = buildSetupPanel()

        add(chatPanel, CHAT_CARD)
        add(setupPanel, SETUP_CARD)

        if (!settings.isApiKeySet()) {
            cardLayout.show(this, SETUP_CARD)
        } else {
            // --- Session restore on restart ---
            // Try to restore multiple saved sessions from the sessions index
            val sessionsIndex = persistence.loadSessionsIndex()
            if (sessionsIndex.isNotEmpty()) {
                // Restore each saved session as a tab
                var firstRestored = true
                for (meta in sessionsIndex.sortedBy { it.createdAt }) {
                    val savedState = persistence.loadSessionById(meta.id)
                    if (savedState != null && (savedState.history.isNotEmpty() || savedState.uiLog.isNotEmpty())) {
                        if (!firstRestored) {
                            conversationTabPanel.newConversation(meta.name)
                        } else {
                            // Rename the default "Session 1" tab
                            conversationTabPanel.renameConversation(
                                conversationTabPanel.getActiveTabId() ?: "",
                                meta.name
                            )
                            firstRestored = false
                        }
                        val conv = conversationTabPanel.getActiveConversation()
                        if (conv != null) {
                            conv.history.addAll(savedState.history)
                            savedState.uiLog.forEach {
                                addMessageBubbleToActiveTab(it.role, it.text, recordUiLog = false)
                            }
                        }
                        // Restore todo list from the last session
                        if (savedState.todoList.isNotEmpty()) {
                            todoList = savedState.todoList
                            todoListPanel.updateItems(todoList)
                        }
                    }
                }
                if (firstRestored) {
                    // No sessions were actually restored (all empty), show chat
                    cardLayout.show(this, CHAT_CARD)
                } else {
                    cardLayout.show(this, CHAT_CARD)
                }
            } else {
                // Fallback: try single-session restore (backward compat)
                val savedState = persistence.loadSession()
                if (savedState != null && savedState.uiLog.isNotEmpty()) {
                    val conv = conversationTabPanel.getActiveConversation()
                    if (conv != null) {
                        conv.history.addAll(savedState.history)
                    }
                    todoList = savedState.todoList
                    todoListPanel.updateItems(todoList)
                    savedState.uiLog.forEach { addMessageBubbleToActiveTab(it.role, it.text, recordUiLog = false) }
                    cardLayout.show(this, CHAT_CARD)
                } else {
                    cardLayout.show(this, CHAT_CARD)
                }
            }
        }

        scope.launch {
            try {
                val client = ApiClient(
                    baseUrl = settings.state.baseUrl,
                    apiKey = settings.getApiKey() ?: "",
                    model = settings.state.model
                )
                val models = client.listModels()
                enhancedInputPanel.updateModelList(models)
                SwingUtilities.invokeLater { conversationTabPanel.updateModelStatus(settings.state.model) }
            } catch (_: Exception) { }
        }

        // Set initial model status
        conversationTabPanel.updateModelStatus(settings.state.model)

        ThemeUtils.onThemeChange {
            SwingUtilities.invokeLater {
                revalidate()
                repaint()
            }
        }
    }

    private fun showMenuPopup(source: java.awt.Component) {
        val popup = JPopupMenu()

        val settingsItem = JMenuItem("Settings", AllIcons.General.Settings)
        settingsItem.addActionListener {
            baseUrlField.text = settings.state.baseUrl
            modelField.text = settings.state.model
            apiKeyField.text = settings.getApiKey() ?: ""
            cardLayout.show(this, SETUP_CARD)
        }
        popup.add(settingsItem)

        popup.addSeparator()

        // --- Approval mode submenu ---
        val approvalMenu = JMenu("Approval Mode").apply {
            icon = AllIcons.General.Settings
        }
        for (mode in ApprovalMode.entries) {
            val item = JCheckBoxMenuItem("${mode.displayName} - ${mode.description}").apply {
                isSelected = mode == approvalMode
                addActionListener {
                    approvalMode = mode
                    toolHandler.approvalMode = mode
                    settings.state.approvalMode = mode.name
                    DebugLog.info("ChatToolWindow", "Approval mode changed to: ${mode.name}")
                    statusLabel.text = "Approval: ${mode.displayName}"
                }
            }
            approvalMenu.add(item)
        }
        popup.add(approvalMenu)

        popup.addSeparator()

        val modeLabel = if (currentPhase == "execution") "Mode: Execution" else "Mode: Discovery"
        val modeItem = JMenuItem(modeLabel, AllIcons.Actions.ChangeView)
        modeItem.addActionListener {
            currentPhase = if (currentPhase == "execution") "discovery" else "execution"
            DebugLog.info("ChatToolWindow", "Mode toggled to: $currentPhase")
        }
        popup.add(modeItem)

        popup.show(source, 0, source.height)
    }

    private fun showModelStatusPopup(source: java.awt.Component) {
        val popup = JPopupMenu()

        val modelLabel = JMenuItem("Model: ${settings.state.model}")
        modelLabel.icon = AllIcons.General.Balloon
        modelLabel.isEnabled = false
        popup.add(modelLabel)

        val baseUrlLabel = JMenuItem("Endpoint: ${settings.state.baseUrl.takeLast(30)}")
        baseUrlLabel.isEnabled = false
        popup.add(baseUrlLabel)

        popup.addSeparator()

        val phaseLabel = if (currentPhase == "execution") "Phase: Execution" else "Phase: Discovery"
        val phaseItem = JMenuItem(phaseLabel, AllIcons.Actions.ChangeView)
        phaseItem.addActionListener {
            currentPhase = if (currentPhase == "execution") "discovery" else "execution"
            DebugLog.info("ChatToolWindow", "Mode toggled to: $currentPhase")
        }
        popup.add(phaseItem)

        popup.addSeparator()

        // Force context compression
        val compressItem = JMenuItem("Compress Context", AllIcons.Actions.GC)
        compressItem.addActionListener {
            scope.launch {
                try {
                    val activeConv = conversationTabPanel.getActiveConversation()
                    val messages = activeConv?.history?.toList() ?: emptyList()
                    if (messages.isEmpty()) {
                        SwingUtilities.invokeLater { statusLabel.text = "No messages to compress" }
                        return@launch
                    }
                    val compactor = toolHandler.contextCompactor
                    if (compactor == null) {
                        SwingUtilities.invokeLater { statusLabel.text = "Compactor not available" }
                        return@launch
                    }
                    val sizeBefore = messages.size
                    SwingUtilities.invokeLater { statusLabel.text = "Compressing context ($sizeBefore messages)..." }
                    val compacted = compactor.compact(messages)
                    val sizeAfter = compacted.size
                    // Update the conversation history in place
                    if (activeConv != null) {
                        activeConv.history.clear()
                        activeConv.history.addAll(compacted)
                    }
                    usageTracker.recordCompaction(sizeBefore, sizeAfter)
                    SwingUtilities.invokeLater {
                        statusLabel.text = "Context compressed: $sizeBefore -> $sizeAfter messages"
                        val activeConv2 = conversationTabPanel.getActiveConversation()
                        val allMessages = activeConv2?.history?.toList() ?: emptyList()
                        val summary = usageTracker.computeSummary(allMessages)
                        usageCounterPanel.updateUsage(summary)
                    }
                } catch (e: Exception) {
                    DebugLog.error("ChatToolWindow", "Manual context compression failed: ${e.message}", e)
                    SwingUtilities.invokeLater { statusLabel.text = "Compression failed: ${e.message}" }
                }
            }
        }
        popup.add(compressItem)

        popup.show(source, 0, source.height)
    }

    private fun buildSetupPanel(): JPanel {
        baseUrlField.text = settings.state.baseUrl
        modelField.text = settings.state.model
        apiKeyField.text = settings.getApiKey() ?: ""

        return panel {
            row {
                label("Welcome to AI Agent Chat").applyToComponent {
                    font = font.deriveFont(java.awt.Font.BOLD, 16f)
                }
            }
            row { label("Please configure your API connection to begin.") }
            separator()
            row("Base URL:") { cell(baseUrlField).align(Align.FILL) }
            row("Model:") { cell(modelField).align(Align.FILL) }
            row("API Key:") { cell(apiKeyField).align(Align.FILL) }
            row {
                button("Save and Start Chat") {
                    settings.state.baseUrl = baseUrlField.text
                    settings.state.model = modelField.text
                    settings.setApiKey(String(apiKeyField.password))
                    cardLayout.show(this@ChatToolWindowPanel, CHAT_CARD)
                    scope.launch {
                        try {
                            val client = ApiClient(
                                baseUrl = settings.state.baseUrl,
                                apiKey = settings.getApiKey() ?: "",
                                model = settings.state.model
                            )
                            val models = client.listModels()
                            enhancedInputPanel.updateModelList(models)
                        } catch (_: Exception) { }
                    }
                }.align(AlignX.RIGHT)
            }
        }.apply { border = JBUI.Borders.empty(16) }
    }

    private fun buildBottomPanel(): JComponent {
        val container = JPanel(BorderLayout())
        container.background = JBColor.PanelBackground

        val statusBar = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(ThemeUtils.SUBTLE_BORDER, 0, 0, 1, 0),
                JBUI.Borders.empty(2, 8)
            )
            add(statusLabel, BorderLayout.WEST)
            add(usageCounterPanel, BorderLayout.EAST)
        }
        container.add(statusBar, BorderLayout.NORTH)

        val todoScrollWrapper = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 8, 0, 8)
            add(todoListPanel, BorderLayout.CENTER)
        }
        container.add(todoScrollWrapper, BorderLayout.CENTER)

        val bottomWrapper = JPanel(BorderLayout())
        bottomWrapper.isOpaque = false
        bottomWrapper.border = JBUI.Borders.empty(0, 8)
        bottomWrapper.add(enhancedInputPanel, BorderLayout.CENTER)

        // "Content generated by AI" label below the prompt box
        val aiLabel = JBLabel("Content generated by AI").apply {
            font = font.deriveFont(9f)
            foreground = JBColor(0x999999, 0x666666)
        }
        val aiLabelPanel = JPanel(FlowLayout(FlowLayout.CENTER, 0, 1)).apply {
            isOpaque = false
            add(aiLabel)
        }
        bottomWrapper.add(aiLabelPanel, BorderLayout.SOUTH)

        container.add(bottomWrapper, BorderLayout.SOUTH)

        return container
    }

    private fun handlePromptSubmit(text: String, fileTags: List<EnhancedInputPanel.FileTag>) {
        DebugLog.info("ChatToolWindow", "handlePromptSubmit: text length=${text.length}, fileTags=${fileTags.size}")
        val promptText = if (fileTags.isNotEmpty()) {
            val fileContext = fileTags.joinToString("\n") { "- ${it.path}" }
            "$text\n\nReferenced files:\n$fileContext"
        } else {
            text
        }

        executePrompt(
            promptText = promptText,
            displayText = text,
            referencedFiles = fileTags.map { it.path }
        )
    }

    private fun addMessageBubbleToActiveTab(
        role: String,
        text: String,
        referencedFiles: List<String> = emptyList(),
        recordUiLog: Boolean = true
    ) {
        val targetConversationId = activeConversationId ?: conversationTabPanel.getActiveTabId()
        if (text.isBlank() && !role.startsWith("tool")) {
            return
        }

        val conv = targetConversationId?.let { conversationTabPanel.getConversation(it) } ?: conversationTabPanel.getActiveConversation()
        if (recordUiLog) {
            conv?.uiLog?.add(UiLogEntry(role, text))
        }

        SwingUtilities.invokeLater {
            val activeConv = targetConversationId?.let { conversationTabPanel.getConversation(it) } ?: conversationTabPanel.getActiveConversation()
            if (activeConv == null) {
                DebugLog.error("ChatToolWindow", "addMessageBubbleToActiveTab: activeConv is null, cannot add message!")
                return@invokeLater
            }

            val componentToAdd: JComponent = when {
                role.startsWith("tool") -> {
                    val toolName = role.removePrefix("tool:").trim()
                    val status = if (text.startsWith("Error")) ToolCallCard.ToolStatus.ERROR else ToolCallCard.ToolStatus.COMPLETED
                    ToolCallCard(toolName, text, status)
                }
                role.contains("user") -> {
                    UserMessagePanel(text, referencedFiles)
                }
                role == "error" -> {
                    createErrorPanel(text)
                }
                else -> {
                    ResponseMessagePanel(text, project)
                }
            }

            activeConv.messageContainer.remove(activeConv.fillerComponent)

            val constraints = GridBagConstraints().apply {
                gridx = 0
                gridy = activeConv.currentRow++
                weightx = 1.0
                weighty = 0.0
                fill = GridBagConstraints.HORIZONTAL
                anchor = GridBagConstraints.NORTH
                insets = JBUI.insets(6, 8, 6, 8)
            }

            activeConv.messageContainer.add(componentToAdd, constraints)

            activeConv.fillerGbc.gridy = activeConv.currentRow
            activeConv.messageContainer.add(activeConv.fillerComponent, activeConv.fillerGbc)

            activeConv.messageContainer.revalidate()
            activeConv.messageContainer.repaint()

            SwingUtilities.invokeLater {
                val scrollBar = activeConv.scrollPane.verticalScrollBar
                scrollBar.value = scrollBar.maximum
            }
        }
    }

    /**
     * Add an arbitrary Swing component to the active conversation's message container.
     * Used for summarization event panels and other non-message UI elements.
     */
    private fun addComponentToActiveTab(component: JComponent) {
        val targetConversationId = activeConversationId ?: conversationTabPanel.getActiveTabId()
        val activeConv = targetConversationId?.let { conversationTabPanel.getConversation(it) } ?: conversationTabPanel.getActiveConversation()
        if (activeConv == null) return

        activeConv.messageContainer.remove(activeConv.fillerComponent)

        val constraints = GridBagConstraints().apply {
            gridx = 0
            gridy = activeConv.currentRow++
            weightx = 1.0
            weighty = 0.0
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.NORTH
            insets = JBUI.insets(6, 8, 6, 8)
        }

        activeConv.messageContainer.add(component, constraints)
        activeConv.fillerGbc.gridy = activeConv.currentRow
        activeConv.messageContainer.add(activeConv.fillerComponent, activeConv.fillerGbc)
        activeConv.messageContainer.revalidate()
        activeConv.messageContainer.repaint()

        SwingUtilities.invokeLater {
            val scrollBar = activeConv.scrollPane.verticalScrollBar
            scrollBar.value = scrollBar.maximum
        }
    }

    private fun createErrorPanel(errorText: String): JComponent {
        val card = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(ThemeUtils.ERROR_BORDER, 1),
                JBUI.Borders.empty(8, 12)
            )
            background = ThemeUtils.ERROR_BG
        }
        val titlePanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            val titleLabel = JBLabel("Error").apply {
                font = font.deriveFont(java.awt.Font.BOLD, 12f)
                foreground = ThemeUtils.ERROR_BORDER
                icon = AllIcons.General.Error
            }
            add(titleLabel, BorderLayout.WEST)
            // Dismiss [x] button
            val closeBtn = JButton(AllIcons.Actions.Close).apply {
                toolTipText = "Dismiss"
                isContentAreaFilled = false
                isBorderPainted = false
                isFocusPainted = false
                margin = JBUI.insets(2)
                preferredSize = Dimension(20, 20)
                cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                addActionListener {
                    card.isVisible = false
                    card.parent?.revalidate()
                    card.parent?.repaint()
                }
            }
            add(closeBtn, BorderLayout.EAST)
        }
        val body = JBTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            text = errorText
            background = card.background
            border = JBUI.Borders.emptyTop(4)
        }
        card.add(titlePanel, BorderLayout.NORTH)
        card.add(body, BorderLayout.CENTER)
        return card
    }

    private fun handleStop() {
        // Use command queue for abort (highest priority, cancels active stream + tool calls)
        commandQueue.enqueue(AgentCommand.Abort)
        // Also cancel the job directly for immediate response
        activeEngineJob?.cancel()
        activeStreamingPanel?.let { panel ->
            panel.finalize()
            activeStreamingPanel = null
        }
        activeConversationId = null
        stateMachine.reset()
        usageTracker.reset()
        SwingUtilities.invokeLater {
            statusLabel.text = "Stopped"
            enhancedInputPanel.updateRunningState(false)
            usageCounterPanel.updateUsage(usageTracker.computeSummary(emptyList()))
        }
    }

    /**
     * Generates a concise session title from the first user prompt and renames
     * the active tab. Only fires when the session still has a default title
     * ("Session N"), so manually renamed sessions are preserved.
     */
    private fun autoRenameSession(promptText: String) {
        val convId = activeConversationId ?: return
        val conv = conversationTabPanel.getConversation(convId) ?: return
        // Only auto-rename if the title is still a default like "Session 1", "Session 2", etc.
        if (!conv.title.matches(Regex("Session \\d+"))) return
        // Only auto-rename on the first message (history is empty before this prompt)
        if (conv.history.isNotEmpty()) return

        // Generate title: take first line, trim, truncate to 40 chars
        val firstLine = promptText.trim().lines().firstOrNull()?.trim() ?: return
        val title = if (firstLine.length > 40) {
            firstLine.take(37) + "..."
        } else {
            firstLine
        }

        SwingUtilities.invokeLater {
            conversationTabPanel.renameConversation(convId, title)
        }
    }

    private fun executePrompt(promptText: String, displayText: String = promptText, referencedFiles: List<String> = emptyList()) {
        DebugLog.info("ChatToolWindow", "=== EXECUTE PROMPT ===")
        cardLayout.show(this, CHAT_CARD)

        activeConversationId = conversationTabPanel.getActiveTabId()

        // Auto-rename session from first prompt if it still has a default title
        autoRenameSession(displayText)

        addMessageBubbleToActiveTab("user", displayText, referencedFiles)

        if (promptText.startsWith("/")) {
            val activeConv = conversationTabPanel.getActiveConversation()
            val usageSummary = usageTracker.computeSummary(activeConv?.history?.toList() ?: emptyList())
            val plan = planManager.getPlan()
            val ctx = SlashCommandContext(
                projectRoot = project.basePath ?: "",
                baseUrl = settings.state.baseUrl,
                model = settings.state.model,
                apiKey = settings.getApiKey() ?: "",
                maxSteps = settings.state.maxSteps,
                approvalMode = settings.state.approvalMode,
                maxContextTokens = settings.state.maxContextTokens,
                maxOutputTokens = settings.state.maxOutputTokens,
                multiProviderEnabled = settings.isMultiProviderEnabled(),
                dynamicRoutingEnabled = settings.isDynamicRoutingEnabled(),
                providers = settings.getProviders(),
                folderMemory = persistence.loadFolderMemory(),
                globalMemory = persistence.loadGlobalMemory(),
                summaryMemory = persistence.loadSummaryMemory(),
                sessionCount = conversationTabPanel.getAllConversations().size,
                activeMessageCount = activeConv?.history?.size ?: 0,
                todoCount = todoList.size,
                hasPlan = plan != null,
                planSummary = plan?.toSystemPromptSection()?.trim() ?: "",
                currentSessionTokens = usageSummary.currentSessionTokens,
                totalInputTokens = usageSummary.totalInputTokens,
                totalOutputTokens = usageSummary.totalOutputTokens
            )
            val result = SlashCommands.processCommand(promptText, ctx)
            if (result != null) {
                addMessageBubbleToActiveTab("assistant", result.message)
                when (result.action) {
                    SlashCommandAction.CLEAR_CONVERSATION -> {
                        val conv = conversationTabPanel.getActiveConversation()
                        conv?.history?.clear()
                        conv?.uiLog?.clear()
                        conv?.messageContainer?.let { container ->
                            SwingUtilities.invokeLater {
                                container.components.forEach { c ->
                                    if (c !== conv?.fillerComponent) container.remove(c)
                                }
                                conv?.currentRow = 0
                                conv?.fillerGbc?.gridy = 9999
                                container.revalidate()
                                container.repaint()
                            }
                        }
                        usageTracker.reset()
                    }
                    SlashCommandAction.NEW_SESSION -> {
                        conversationTabPanel.onNewTab?.invoke()
                    }
                    null -> { /* no action */ }
                }
                activeConversationId = null
                return
            }
        }

        val userMsg = ChatMessage(MessageRole.USER, promptText)
        statusLabel.text = "Agent running..."
        enhancedInputPanel.updateRunningState(true)

        activeEngineJob = scope.launch {
            // --- Multi-provider: load providers from settings and sync models ---
            if (settings.isMultiProviderEnabled()) {
                val savedProviders = settings.getProviders()
                providerManager.clear()
                savedProviders.forEach { p ->
                    providerManager.addProviderOffline(p)
                    // Try to sync models in background (non-blocking on first launch)
                    try {
                        val synced = providerManager.syncModels(p)
                        providerManager.updateProvider(synced)
                    } catch (e: Exception) {
                        DebugLog.warn("ChatToolWindow", "Model sync failed for provider '${p.name}': ${e.message}")
                    }
                }
                DebugLog.info("ChatToolWindow", "Multi-provider mode: ${providerManager.providers.size} providers, ${providerManager.allModels.size} models")
            }

            // --- Dynamic model routing: analyze task and select optimal model ---
            var selectedModel = settings.state.model
            var selectedBaseUrl = settings.state.baseUrl
            var selectedApiKey = settings.getApiKey() ?: ""
            var selectedAuthType = com.aiagent.chat.model.AuthHeaderType.BEARER

            if (settings.isDynamicRoutingEnabled() && providerManager.allModels.isNotEmpty()) {
                val complexity = com.aiagent.chat.model.ModelRouter.analyzeComplexity(promptText)
                val routedModel = com.aiagent.chat.model.ModelRouter.selectModel(complexity, providerManager.allModels)
                if (routedModel != null) {
                    selectedModel = routedModel.id
                    val provider = providerManager.findProviderForModel(routedModel.id)
                    if (provider != null) {
                        selectedBaseUrl = provider.baseUrl
                        selectedApiKey = provider.apiKey
                        selectedAuthType = provider.authHeaderType
                    }
                    val routingExplain = com.aiagent.chat.model.ModelRouter.explainRouting(complexity, routedModel)
                    DebugLog.info("ChatToolWindow", "Dynamic routing: $routingExplain")
                    SwingUtilities.invokeLater {
                        statusLabel.text = "Routed to: ${routedModel.id} (${routedModel.sizeTag.displayName})"
                    }
                }
            }

            val client = ApiClient(
                baseUrl = selectedBaseUrl,
                apiKey = selectedApiKey,
                model = selectedModel,
                authHeaderType = selectedAuthType,
                maxOutputTokens = if (settings.state.maxOutputTokens > 0) settings.state.maxOutputTokens else null
            )

            val contextCompactor = ContextCompactor(client)

            // Wire up message access for compress_chat tools
            toolHandler.contextCompactor = contextCompactor
            toolHandler.bindMessagesAccessor(
                getMessages = {
                    val conv = activeConversationId?.let { conversationTabPanel.getConversation(it) } ?: conversationTabPanel.getActiveConversation()
                    conv?.history?.toList() ?: emptyList()
                },
                setMessages = { newMessages ->
                    val conv = activeConversationId?.let { conversationTabPanel.getConversation(it) } ?: conversationTabPanel.getActiveConversation()
                    if (conv != null) {
                        conv.history.clear()
                        conv.history.addAll(newMessages)
                    }
                }
            )

            val engine = AgentEngine(
                client = client,
                toolExecutor = { name, args ->
                    DebugLog.info("AgentEngine", "Executing tool: $name")
                    val result = toolHandler.execute(name, args)
                    DebugLog.info("AgentEngine", "Tool $name completed, result length: ${result.length}")
                    result
                },
                contextCompactor = contextCompactor,
                planManager = planManager,
                providerManager = providerManager,
                onDelta = { delta ->
                    when (delta) {
                        is AgentDelta.Status -> {
                            SwingUtilities.invokeLater { statusLabel.text = delta.text }
                        }
                        is AgentDelta.Assistant -> {
                            activeStreamingPanel?.let { panel ->
                                SwingUtilities.invokeLater {
                                    val parent = panel.parent
                                    if (parent is JPanel) {
                                        parent.remove(panel)
                                        parent.revalidate()
                                        parent.repaint()
                                    }
                                }
                            }
                            activeStreamingPanel = null
                            addMessageBubbleToActiveTab("assistant", delta.text)
                        }
                        is AgentDelta.ToolOutput -> {
                            activeStreamingPanel?.let { panel ->
                                panel.finalize()
                                activeStreamingPanel = null
                            }
                            addMessageBubbleToActiveTab("tool: ${delta.name}", delta.text)
                        }
                        is AgentDelta.StreamingStart -> { }
                        is AgentDelta.StreamingContent -> {
                            activeStreamingPanel?.appendText(delta.text)
                        }
                        is AgentDelta.StreamingReasoning -> {
                            activeStreamingPanel?.appendThinking(delta.text)
                        }
                        is AgentDelta.StreamingEnd -> {
                            if (delta.fullText.isNotBlank()) {
                                activeStreamingPanel?.let { panel ->
                                    panel.finalize()
                                    activeStreamingPanel = null
                                }
                            } else {
                                activeStreamingPanel?.let { panel ->
                                    SwingUtilities.invokeLater {
                                        panel.parent?.remove(panel)
                                        panel.parent?.revalidate()
                                        panel.parent?.repaint()
                                    }
                                    activeStreamingPanel = null
                                }
                            }
                        }
                        is AgentDelta.StateChange -> {
                            DebugLog.info("ChatToolWindow", "State: ${delta.from} -> ${delta.to} (${delta.reason})")
                            SwingUtilities.invokeLater {
                                statusLabel.text = "${delta.to.name.lowercase().replaceFirstChar { it.uppercase() }}: ${delta.reason}"
                            }
                        }
                        is AgentDelta.QueueUpdate -> {
                            DebugLog.info("ChatToolWindow", "Queue update: ${delta.pendingCommands} pending commands")
                        }
                        is AgentDelta.CompactionNotice -> {
                            DebugLog.info("ChatToolWindow", "Compaction: ${delta.message} (${delta.messagesBefore} -> ${delta.messagesAfter} messages)")
                            usageTracker.recordCompaction(delta.messagesBefore, delta.messagesAfter)
                            SwingUtilities.invokeLater {
                                statusLabel.text = "Context compacted: ${delta.messagesBefore} -> ${delta.messagesAfter} messages"
                            }
                            addMessageBubbleToActiveTab("system", delta.message)
                            // Also add a summarization event panel to the chat
                            SwingUtilities.invokeLater {
                                val event = UsageTracker.CompactionEvent(delta.messagesBefore, delta.messagesAfter,
                                    (delta.messagesBefore - delta.messagesAfter) * 500)
                                addComponentToActiveTab(SummarizationEventPanel(event))
                            }
                        }
                        is AgentDelta.UsageUpdate -> {
                            DebugLog.info("ChatToolWindow", "Usage update: prompt=${delta.usage.promptTokens}, completion=${delta.usage.completionTokens}")
                            usageTracker.recordUsage(delta.usage)
                            // Update the usage counter panel with the latest summary
                            val activeConv = activeConversationId?.let { conversationTabPanel.getConversation(it) } ?: conversationTabPanel.getActiveConversation()
                            val allMessages = activeConv?.history?.toList() ?: emptyList()
                            val summary = usageTracker.computeSummary(allMessages)
                            SwingUtilities.invokeLater {
                                usageCounterPanel.updateUsage(summary)
                            }
                        }
                    }
                },
                stateMachine = stateMachine,
                commandQueue = commandQueue
            )

            try {
                DebugLog.info("AgentEngine", "Starting agent loop, phase: $currentPhase")
                val activeConv = conversationTabPanel.getActiveConversation()
                val targetConv = activeConversationId?.let { conversationTabPanel.getConversation(it) } ?: activeConv
                val tabHistory = targetConv?.history ?: mutableListOf()
                val tabUiLog = targetConv?.uiLog ?: mutableListOf()

                val newMsgs = engine.runAgentLoopStreaming(
                    initialHistory = tabHistory.toList(),
                    userMessage = userMsg,
                    availableTools = PlatformToolHandler.getToolDefinitions(),
                    memory = persistence.loadFolderMemory(),
                    globalMemory = persistence.loadGlobalMemory(),
                    initialPhase = currentPhase,
                    onPhaseChange = { newPhase ->
                        SwingUtilities.invokeLater { currentPhase = newPhase }
                    },
                    steerProvider = { pendingSteerMessages.poll() }
                )
                tabHistory.addAll(newMsgs)
                val now = System.currentTimeMillis()
                val chatId = activeConversationId ?: "session_${now}"
                val chatName = conversationTabPanel.getConversation(chatId)?.title ?: "Session"
                val sessionState = SessionState(
                    history = tabHistory,
                    uiLog = tabUiLog.toList(),
                    todoList = todoList,
                    savedAt = now,
                    selectedModel = settings.state.model,
                    chatId = chatId,
                    chatName = chatName,
                    createdAt = targetConv?.let { null } ?: now,
                    updatedAt = now
                )
                persistence.saveSession(sessionState)
                persistence.saveSessionById(sessionState)
            } catch (e: Exception) {
                val errorMsg = e.message ?: e::class.simpleName ?: "Unknown error"
                DebugLog.error("AgentEngine", "Agent loop failed: $errorMsg", e)
                activeStreamingPanel?.let { panel ->
                    panel.finalize()
                    activeStreamingPanel = null
                }
                addMessageBubbleToActiveTab("error", errorMsg)
            } finally {
                activeStreamingPanel?.let { panel ->
                    panel.finalize()
                    activeStreamingPanel = null
                }
                activeConversationId = null
                stateMachine.reset()
                commandQueue.clear()
                SwingUtilities.invokeLater {
                    statusLabel.text = "Ready"
                    enhancedInputPanel.updateRunningState(false)
                }
                DebugLog.info("AgentEngine", "Prompt execution finished and UI reset")
            }
        }
    }
}
