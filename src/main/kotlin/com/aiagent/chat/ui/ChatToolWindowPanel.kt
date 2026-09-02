package com.aiagent.chat.ui

import com.aiagent.chat.agent.AgentDelta
import com.aiagent.chat.agent.AgentEngine
import com.aiagent.chat.model.ChatMessage
import com.aiagent.chat.model.MessageRole
import com.aiagent.chat.model.SessionState
import com.aiagent.chat.model.TodoItem
import com.aiagent.chat.model.UiLogEntry
import com.aiagent.chat.net.ApiClient
import com.aiagent.chat.persistence.PersistenceManager
import com.aiagent.chat.services.ChatStateService
import com.aiagent.chat.tools.PlatformToolHandler
import com.aiagent.chat.tools.SlashCommands
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
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
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.*

class ChatToolWindowFactory : ToolWindowFactory {
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

    // --- Conversation Tab Panel (Phase 8 wiring) ---
    private val conversationTabPanel = ConversationTabPanel()

    // Todo list panel (shown above input area)
    private val todoListPanel = TodoListPanel()

    // --- Enhanced Input Panel (Phase 4 wiring) ---
    private val enhancedInputPanel = EnhancedInputPanel(
        project = project,
        onSubmit = { text, fileTags ->
            handlePromptSubmit(text, fileTags)
        },
        onSteer = { text ->
            if (activeEngineJob?.isActive == true) {
                addMessageBubbleToActiveTab("user (steering)", text)
                pendingSteerMessages.add(text)
            }
        },
        onStop = { handleStop() },
        isRunning = { activeEngineJob?.isActive == true },
        currentModel = { settings.state.model },
        onModelChange = { newModel ->
            settings.state.model = newModel
        }
    )

    // Settings / navigation buttons
    private val settingsBtn = JButton(AllIcons.General.Settings).apply {
        toolTipText = "Configure AI Agent"
    }
    private val newChatBtn = JButton("New", AllIcons.Actions.New).apply {
        toolTipText = "New Chat"
    }
    private val statusLabel = JBLabel("Ready")

    private val baseUrlField = JBTextField()
    private val modelField = JBTextField()
    private val apiKeyField = JPasswordField()

    private var activeEngineJob: Job? = null
    private var currentPhase = "discovery"

    private val phaseBtn = JToggleButton("Discovery Mode").apply {
        toolTipText = "Toggle Write Access"
        addActionListener {
            currentPhase = if (isSelected) "execution" else "discovery"
            text = if (isSelected) "Execution Mode" else "Discovery Mode"
        }
    }

    private var todoList: List<TodoItem> = emptyList()
    private val pendingSteerMessages = java.util.concurrent.ConcurrentLinkedQueue<String>()

    // Track the active streaming panel so we can append tokens to it
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
            override fun requestApproval(toolName: String, toolArgs: String): PlatformToolHandler.ApprovalResult {
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
                            result = PlatformToolHandler.ApprovalResult(false, false)
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
                            insets = JBUI.insets(4, 8, 4, 8)
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
        // Create initial conversation tab
        conversationTabPanel.newConversation("Chat 1")

        conversationTabPanel.onTabChanged = { tabId ->
            // Could update status or save state per tab
        }

        val chatPanel = JPanel(BorderLayout())
        chatPanel.background = JBColor.PanelBackground

        // Center: conversation tabs
        chatPanel.add(conversationTabPanel, BorderLayout.CENTER)

        // Bottom panel includes todo list + enhanced input + status bar
        val bottomPanel = buildBottomPanel()
        chatPanel.add(bottomPanel, BorderLayout.SOUTH)

        val setupPanel = buildSetupPanel()

        // Landing panel with quick actions
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

        // Decide which card to show
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
                savedState.uiLog.forEach { addMessageBubbleToActiveTab(it.role, it.text) }
                cardLayout.show(this, CHAT_CARD)
            } else {
                cardLayout.show(this, LANDING_CARD)
            }
        }

        // Load available models in background
        scope.launch {
            try {
                val client = ApiClient(
                    baseUrl = settings.state.baseUrl,
                    apiKey = settings.getApiKey() ?: "",
                    model = settings.state.model
                )
                val models = client.listModels()
                enhancedInputPanel.updateModelList(models)
            } catch (_: Exception) {
                // Silently fail — user can still type a model name
            }
        }

        settingsBtn.addActionListener {
            baseUrlField.text = settings.state.baseUrl
            modelField.text = settings.state.model
            apiKeyField.text = settings.getApiKey() ?: ""
            cardLayout.show(this, SETUP_CARD)
        }

        newChatBtn.addActionListener {
            // Create a new conversation tab instead of clearing
            val tabCount = conversationTabPanel.getAllConversations().size
            conversationTabPanel.newConversation("Chat ${tabCount + 1}")
            todoList = emptyList()
            todoListPanel.updateItems(emptyList())
        }

        // Register theme change listener (Phase 10)
        ThemeUtils.onThemeChange {
            SwingUtilities.invokeLater {
                revalidate()
                repaint()
            }
        }
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
                    // Reload models
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

        // Status bar at top (Phase 10 polish)
        val statusBar = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(ThemeUtils.SUBTLE_BORDER, 0, 0, 1, 0),
                JBUI.Borders.empty(2, 8)
            )
            add(statusLabel, BorderLayout.WEST)
            val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                isOpaque = false
                add(newChatBtn)
                add(settingsBtn)
                add(phaseBtn)
            }
            add(rightPanel, BorderLayout.EAST)
        }
        container.add(statusBar, BorderLayout.NORTH)

        // Todo list panel (only visible when there are items)
        val todoScrollWrapper = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 8, 0, 8)
            add(todoListPanel, BorderLayout.CENTER)
        }
        container.add(todoScrollWrapper, BorderLayout.CENTER)

        // Enhanced input panel at bottom (Phase 4 wiring)
        container.add(enhancedInputPanel, BorderLayout.SOUTH)

        return container
    }

    /**
     * Handles prompt submission from the EnhancedInputPanel.
     * Includes file tag paths in the prompt context.
     */
    private fun handlePromptSubmit(text: String, fileTags: List<EnhancedInputPanel.FileTag>) {
        // Build the prompt with file context if tags are present
        val promptText = if (fileTags.isNotEmpty()) {
            val fileContext = fileTags.joinToString("\n") { "- ${it.path}" }
            "$text\n\nReferenced files:\n$fileContext"
        } else {
            text
        }

        executePrompt(promptText)
    }

    /**
     * Adds a message bubble to the active conversation tab.
     */
    private fun addMessageBubbleToActiveTab(role: String, text: String) {
        if (text.isBlank() && !role.startsWith("tool")) return

        val conv = conversationTabPanel.getActiveConversation()
        conv?.uiLog?.add(UiLogEntry(role, text))

        SwingUtilities.invokeLater {
            val activeConv = conversationTabPanel.getActiveConversation() ?: return@invokeLater

            val componentToAdd: JComponent = when {
                role.startsWith("tool") -> {
                    val toolName = role.removePrefix("tool:").trim()
                    val status = if (text.startsWith("Error")) ToolCallCard.ToolStatus.ERROR else ToolCallCard.ToolStatus.COMPLETED
                    ToolCallCard(toolName, text, status)
                }
                role.contains("user") -> {
                    UserMessagePanel(text)
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
                insets = JBUI.insets(4, 8, 4, 8)
            }

            activeConv.messageContainer.add(componentToAdd, constraints)

            activeConv.fillerGbc.gridy = activeConv.currentRow
            activeConv.messageContainer.add(activeConv.fillerComponent, activeConv.fillerGbc)

            activeConv.messageContainer.revalidate()
            activeConv.messageContainer.repaint()

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
        val titleLabel = JBLabel("Error").apply {
            font = font.deriveFont(java.awt.Font.BOLD, 12f)
            foreground = ThemeUtils.ERROR_BORDER
            icon = AllIcons.General.Error
        }
        val body = JBTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            text = errorText
            background = card.background
            border = JBUI.Borders.empty(4, 0, 0, 0)
        }
        card.add(titleLabel, BorderLayout.NORTH)
        card.add(body, BorderLayout.CENTER)
        return card
    }

    /**
     * Handles the Stop button — cancels the running agent and cleans up UI.
     */
    private fun handleStop() {
        activeEngineJob?.cancel()
        activeStreamingPanel?.let { panel ->
            panel.finalize()
            activeStreamingPanel = null
        }
        SwingUtilities.invokeLater {
            statusLabel.text = "Stopped"
            enhancedInputPanel.updateRunningState(false)
        }
    }

    private fun executePrompt(promptText: String) {
        // Make sure we're on the chat card
        cardLayout.show(this, CHAT_CARD)

        addMessageBubbleToActiveTab("user", promptText)

        if (promptText.startsWith("/")) {
            val res = SlashCommands.processCommand(promptText, project.basePath ?: "")
            if (res != null) {
                addMessageBubbleToActiveTab("assistant", res)
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

            // Create a streaming response panel and add it to the active tab
            val streamingPanel = StreamingResponsePanel(project)
            activeStreamingPanel = streamingPanel

            SwingUtilities.invokeLater {
                val conv = conversationTabPanel.getActiveConversation() ?: return@invokeLater
                conv.messageContainer.remove(conv.fillerComponent)

                val constraints = GridBagConstraints().apply {
                    gridx = 0
                    gridy = conv.currentRow++
                    weightx = 1.0
                    weighty = 0.0
                    fill = GridBagConstraints.HORIZONTAL
                    anchor = GridBagConstraints.NORTH
                    insets = JBUI.insets(4, 8, 4, 8)
                }
                conv.messageContainer.add(streamingPanel, constraints)
                conv.fillerGbc.gridy = conv.currentRow
                conv.messageContainer.add(conv.fillerComponent, conv.fillerGbc)
                conv.messageContainer.revalidate()
                conv.messageContainer.repaint()
            }

            val engine = AgentEngine(
                client = client,
                toolExecutor = { name, args -> toolHandler.execute(name, args) },
                onDelta = { delta ->
                    when (delta) {
                        is AgentDelta.Status -> SwingUtilities.invokeLater { statusLabel.text = delta.text }
                        is AgentDelta.Assistant -> {
                            // Final assistant text (non-streaming fallback or post-tool-call text)
                            // Finalize the streaming panel if active, then add the full message
                            activeStreamingPanel?.let { panel ->
                                panel.finalize()
                                activeStreamingPanel = null
                            }
                            addMessageBubbleToActiveTab("assistant", delta.text)
                        }
                        is AgentDelta.ToolOutput -> {
                            // Finalize streaming panel before showing tool output
                            activeStreamingPanel?.let { panel ->
                                panel.finalize()
                                activeStreamingPanel = null
                            }
                            addMessageBubbleToActiveTab("tool: ${delta.name}", delta.text)
                        }
                        is AgentDelta.StreamingStart -> {
                            // Streaming starts — panel already added
                        }
                        is AgentDelta.StreamingContent -> {
                            // Append token to the streaming panel
                            streamingPanel.appendText(delta.text)
                        }
                        is AgentDelta.StreamingEnd -> {
                            // Streaming finished — finalize the panel into a full ResponseMessagePanel
                            if (delta.fullText.isNotBlank()) {
                                activeStreamingPanel?.let { panel ->
                                    panel.finalize()
                                    activeStreamingPanel = null
                                }
                            } else {
                                // Empty stream — remove the streaming panel
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
                    }
                }
            )

            try {
                val activeConv = conversationTabPanel.getActiveConversation()
                val tabHistory = activeConv?.history ?: mutableListOf()
                val tabUiLog = activeConv?.uiLog ?: mutableListOf()

                val newMsgs = engine.runAgentLoopStreaming(
                    initialHistory = tabHistory.toList(),
                    userMessage = userMsg,
                    availableTools = PlatformToolHandler.getToolDefinitions(),
                    memory = persistence.loadFolderMemory(),
                    globalMemory = persistence.loadGlobalMemory(),
                    initialPhase = currentPhase,
                    onPhaseChange = { newPhase ->
                        SwingUtilities.invokeLater {
                            currentPhase = newPhase
                            phaseBtn.isSelected = (newPhase == "execution")
                            phaseBtn.text = if (phaseBtn.isSelected) "Execution Mode" else "Discovery Mode"
                        }
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
                // Clean up any active streaming panel
                activeStreamingPanel?.let { panel ->
                    panel.finalize()
                    activeStreamingPanel = null
                }
                addMessageBubbleToActiveTab("error", e.message ?: "Failed")
            } finally {
                // Ensure streaming panel is cleaned up
                activeStreamingPanel?.let { panel ->
                    panel.finalize()
                    activeStreamingPanel = null
                }
                SwingUtilities.invokeLater {
                    statusLabel.text = "Ready"
                    enhancedInputPanel.updateRunningState(false)
                }
            }
        }
    }
}
