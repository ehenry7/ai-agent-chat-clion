package com.aiagent.chat.ui

import com.aiagent.chat.agent.AgentCommand
import com.aiagent.chat.agent.AgentDelta
import com.aiagent.chat.agent.AgentEngine
import com.aiagent.chat.agent.AgentSessionState
import com.aiagent.chat.agent.CommandQueue
import com.aiagent.chat.agent.SessionStateMachine
import com.aiagent.chat.debug.DebugLog
import com.aiagent.chat.model.ChatMessage
import com.aiagent.chat.model.MessageRole
import com.aiagent.chat.model.SessionState
import com.aiagent.chat.model.TodoItem
import com.aiagent.chat.model.ToolCategory
import com.aiagent.chat.model.UiLogEntry
import com.aiagent.chat.net.ApiClient
import com.aiagent.chat.persistence.PersistenceManager
import com.aiagent.chat.services.ChatStateService
import com.aiagent.chat.tools.PlatformToolHandler
import com.aiagent.chat.tools.SlashCommands
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
    private val LANDING_CARD = "LANDING_CARD"

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
        onModelChange = { newModel -> settings.state.model = newModel }
    )

    // Menu button (replaces separate Settings + Mode buttons)
    private val menuBtn = JButton(AllIcons.General.Settings).apply {
        toolTipText = "Menu"
        isContentAreaFilled = false
        isBorderPainted = false
        isFocusPainted = false
        margin = JBUI.insets(2)
        preferredSize = Dimension(28, 28)
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
    }
    private val statusLabel = JBLabel("Ready")

    private val baseUrlField = JBTextField()
    private val modelField = JBTextField()
    private val apiKeyField = JPasswordField()

    private var activeEngineJob: Job? = null
    private var activeConversationId: String? = null
    private var currentPhase = "execution"

    // --- State machine + command queue (agent-improvements) ---
    private val stateMachine = SessionStateMachine()
    private val commandQueue = CommandQueue()

    private var todoList: List<TodoItem> = emptyList()
    private val pendingSteerMessages = java.util.concurrent.ConcurrentLinkedQueue<String>()

    private var activeStreamingPanel: StreamingResponsePanel? = null

    // Track pending tool approval call ID for routing user decisions
    private var pendingToolCallId: String? = null

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
        }
    )

    init {
        conversationTabPanel.newConversation("Session 1")

        conversationTabPanel.onTabChanged = { _ -> }

        conversationTabPanel.onNewTab = {
            val tabCount = conversationTabPanel.getAllConversations().size
            conversationTabPanel.newConversation("Session ${tabCount + 1}")
            todoList = emptyList()
            todoListPanel.updateItems(emptyList())
        }

        val chatPanel = JPanel(BorderLayout())
        chatPanel.background = JBColor.PanelBackground

        // Header bar with "AI Agent Chat" title and menu button on the right
        val headerBar = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(ThemeUtils.SUBTLE_BORDER, 0, 0, 1, 0),
                JBUI.Borders.empty(4, 8)
            )
            val titleLabel = JBLabel("AI Agent Chat").apply {
                font = font.deriveFont(java.awt.Font.BOLD, 13f)
            }
            add(titleLabel, BorderLayout.WEST)
            add(menuBtn, BorderLayout.EAST)
        }
        chatPanel.add(headerBar, BorderLayout.NORTH)

        chatPanel.add(conversationTabPanel, BorderLayout.CENTER)

        val bottomPanel = buildBottomPanel()
        chatPanel.add(bottomPanel, BorderLayout.SOUTH)

        val setupPanel = buildSetupPanel()

        val landingPanel = LandingPanel(
            onQuickAction = { action ->
                enhancedInputPanel.setText(when (action) {
                    "Explain Code" -> "Please explain the code in the currently open file."
                    "Write Tests" -> "Please write unit tests for the currently open file."
                    "Find Bugs" -> "Please analyze the currently open file for potential bugs and issues."
                    "Refactor" -> "Please suggest refactoring improvements for the currently open file."
                    "Explore Project" -> "Please explore the project structure and give me an overview."
                    else -> action
                })
                cardLayout.show(this, CHAT_CARD)
                enhancedInputPanel.requestFocus()
            },
            onConfigure = {
                baseUrlField.text = settings.state.baseUrl
                modelField.text = settings.state.model
                apiKeyField.text = settings.getApiKey() ?: ""
                cardLayout.show(this, SETUP_CARD)
            }
        )

        add(chatPanel, CHAT_CARD)
        add(setupPanel, SETUP_CARD)
        add(landingPanel, LANDING_CARD)

        if (!settings.isApiKeySet()) {
            cardLayout.show(this, SETUP_CARD)
        } else {
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
                cardLayout.show(this, LANDING_CARD)
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
            } catch (_: Exception) { }
        }

        menuBtn.addActionListener { showMenuPopup() }

        ThemeUtils.onThemeChange {
            SwingUtilities.invokeLater {
                revalidate()
                repaint()
            }
        }
    }

    private fun showMenuPopup() {
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

        val modeLabel = if (currentPhase == "execution") "Mode: Execution" else "Mode: Discovery"
        val modeItem = JMenuItem(modeLabel, AllIcons.Actions.ChangeView)
        modeItem.addActionListener {
            currentPhase = if (currentPhase == "execution") "discovery" else "execution"
            DebugLog.info("ChatToolWindow", "Mode toggled to: $currentPhase")
        }
        popup.add(modeItem)

        popup.show(menuBtn, 0, menuBtn.height)
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
                    cardLayout.show(this@ChatToolWindowPanel, LANDING_CARD)
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
        bottomWrapper.add(enhancedInputPanel, BorderLayout.CENTER)
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
        SwingUtilities.invokeLater {
            statusLabel.text = "Stopped"
            enhancedInputPanel.updateRunningState(false)
        }
    }

    private fun executePrompt(promptText: String, displayText: String = promptText, referencedFiles: List<String> = emptyList()) {
        DebugLog.info("ChatToolWindow", "=== EXECUTE PROMPT ===")
        cardLayout.show(this, CHAT_CARD)

        activeConversationId = conversationTabPanel.getActiveTabId()
        addMessageBubbleToActiveTab("user", displayText, referencedFiles)

        if (promptText.startsWith("/")) {
            val res = SlashCommands.processCommand(promptText, project.basePath ?: "")
            if (res != null) {
                addMessageBubbleToActiveTab("assistant", res)
                activeConversationId = null
                return
            }
        }

        val userMsg = ChatMessage(MessageRole.USER, promptText)
        statusLabel.text = "Agent running..."
        enhancedInputPanel.updateRunningState(true)

        activeEngineJob = scope.launch {
            val client = ApiClient(
                baseUrl = settings.state.baseUrl,
                apiKey = settings.getApiKey() ?: "",
                model = settings.state.model
            )

            val engine = AgentEngine(
                client = client,
                toolExecutor = { name, args ->
                    DebugLog.info("AgentEngine", "Executing tool: $name")
                    val result = toolHandler.execute(name, args)
                    DebugLog.info("AgentEngine", "Tool $name completed, result length: ${result.length}")
                    result
                },
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
                        is AgentDelta.ToolApprovalRequest -> {
                            DebugLog.info("ChatToolWindow", "Tool approval request: ${delta.toolName} (${delta.category})")
                            pendingToolCallId = delta.toolCallId
                            // The actual approval UI is handled by the PlatformToolHandler's ApprovalHandler
                            // This delta is for informational/logging purposes
                        }
                        is AgentDelta.QueueUpdate -> {
                            DebugLog.info("ChatToolWindow", "Queue update: ${delta.pendingCommands} pending commands")
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
                persistence.saveSession(
                    SessionState(
                        history = tabHistory,
                        uiLog = tabUiLog.toList(),
                        todoList = todoList,
                        savedAt = System.currentTimeMillis()
                    )
                )
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
