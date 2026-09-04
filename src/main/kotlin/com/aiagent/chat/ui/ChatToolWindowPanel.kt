package com.aiagent.chat.ui

import com.aiagent.chat.agent.AgentDelta
import com.aiagent.chat.agent.AgentEngine
import com.aiagent.chat.debug.DebugLog
import com.aiagent.chat.debug.DebugLogPanel
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
    private var activeConversationId: String? = null
    private var currentPhase = "discovery"

    private val phaseBtn = JToggleButton("Discovery Mode").apply {
        toolTipText = "Toggle Write Access"
        addActionListener {
            currentPhase = if (isSelected) "execution" else "discovery"
            text = if (isSelected) "Execution Mode" else "Discovery Mode"
        }
    }

    private val debugBtn = JToggleButton("Debug").apply {
        toolTipText = "Toggle Debug Log"
        addActionListener {
            debugLogPanel.isVisible = isSelected
        }
    }

    // Debug-only button: injects a "Hello World" bubble through the exact same
    // code path as regular chat bubbles, with verbose logging at every step.
    private val debugBubbleBtn = JButton("DBG Bubble").apply {
        toolTipText = "Insert a Hello World bubble using the same code path as regular messages"
        addActionListener {
            addDebugHelloWorldBubble()
        }
    }

    private var todoList: List<TodoItem> = emptyList()
    private val pendingSteerMessages = java.util.concurrent.ConcurrentLinkedQueue<String>()

    // Track the active streaming panel so we can append tokens to it
    private var activeStreamingPanel: StreamingResponsePanel? = null

    // Debug log panel (collapsible)
    private val debugLogPanel = DebugLogPanel()

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
        // Create initial conversation tab
        conversationTabPanel.newConversation("Chat 1")

        conversationTabPanel.onTabChanged = { _ ->
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
                savedState.uiLog.forEach { addMessageBubbleToActiveTab(it.role, it.text, recordUiLog = false) }
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
                add(debugBtn)
                add(debugBubbleBtn)
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

        // Bottom wrapper: debug panel (toggleable) + enhanced input
        val bottomWrapper = JPanel(BorderLayout())
        bottomWrapper.isOpaque = false

        // Debug log panel (hidden by default)
        debugLogPanel.isVisible = false
        debugLogPanel.preferredSize = Dimension(0, 150)
        bottomWrapper.add(debugLogPanel, BorderLayout.NORTH)

        // Enhanced input panel at bottom (Phase 4 wiring)
        bottomWrapper.add(enhancedInputPanel, BorderLayout.SOUTH)

        container.add(bottomWrapper, BorderLayout.SOUTH)

        return container
    }

    /**
     * Handles prompt submission from the EnhancedInputPanel.
     * Includes file tag paths in the prompt context.
     */
    private fun handlePromptSubmit(text: String, fileTags: List<EnhancedInputPanel.FileTag>) {
        DebugLog.info("ChatToolWindow", "handlePromptSubmit: text length=${text.length}, fileTags=${fileTags.size}")
        val promptText = if (fileTags.isNotEmpty()) {
            val fileContext = fileTags.joinToString("\n") { "- ${it.path}" }
            "$text\n\nReferenced files:\n$fileContext"
        } else {
            text
        }

        DebugLog.info(
            "ChatToolWindow",
            "Prompt prepared for LLM: displayLength=${text.length}, enrichedLength=${promptText.length}, referencedFiles=${fileTags.map { it.path }}"
        )

        executePrompt(
            promptText = promptText,
            displayText = text,
            referencedFiles = fileTags.map { it.path }
        )
    }

    /**
     * Adds a message bubble to the active conversation tab.
     */
    private fun addMessageBubbleToActiveTab(
        role: String,
        text: String,
        referencedFiles: List<String> = emptyList(),
        recordUiLog: Boolean = true
    ) {
        val targetConversationId = activeConversationId ?: conversationTabPanel.getActiveTabId()
        DebugLog.info("ChatToolWindow", "addMessageBubbleToActiveTab: role=$role, textLength=${text.length}, targetConversationId=$targetConversationId, activeConversationId=$activeConversationId")
        if (text.isBlank() && !role.startsWith("tool")) {
            DebugLog.warn("ChatToolWindow", "addMessageBubbleToActiveTab: early return due to blank text")
            return
        }

        val conv = targetConversationId?.let { conversationTabPanel.getConversation(it) } ?: conversationTabPanel.getActiveConversation()
        DebugLog.info("ChatToolWindow", "addMessageBubbleToActiveTab: conv=${conv?.id}, uiLog entries before=${conv?.uiLog?.size}")
        if (recordUiLog) {
            conv?.uiLog?.add(UiLogEntry(role, text))
        }

        SwingUtilities.invokeLater {
            val activeConv = targetConversationId?.let { conversationTabPanel.getConversation(it) } ?: conversationTabPanel.getActiveConversation()
            DebugLog.info("ChatToolWindow", "addMessageBubbleToActiveTab [EDT]: activeConv=$activeConv, textLength=${text.length}")
            if (activeConv == null) {
                DebugLog.error("ChatToolWindow", "addMessageBubbleToActiveTab [EDT]: activeConv is null, cannot add message!")
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
                    DebugLog.info("ChatToolWindow", "addMessageBubbleToActiveTab [EDT]: creating ResponseMessagePanel with text length ${text.length}")
                    ResponseMessagePanel(text, project)
                }
            }

            DebugLog.info("ChatToolWindow", "addMessageBubbleToActiveTab [EDT]: component class=${componentToAdd.javaClass.name}, isVisible=${componentToAdd.isVisible}, preferredSize=${componentToAdd.preferredSize}")
            DebugLog.info("ChatToolWindow", "addMessageBubbleToActiveTab [EDT]: removing fillerComponent, componentCount(beforeRemove)=${activeConv.messageContainer.componentCount}")
            activeConv.messageContainer.remove(activeConv.fillerComponent)
            DebugLog.info("ChatToolWindow", "addMessageBubbleToActiveTab [EDT]: fillerComponent removed, componentCount(afterRemove)=${activeConv.messageContainer.componentCount}")

            val constraints = GridBagConstraints().apply {
                gridx = 0
                gridy = activeConv.currentRow++
                weightx = 1.0
                weighty = 0.0
                fill = GridBagConstraints.HORIZONTAL
                anchor = GridBagConstraints.NORTH
                insets = JBUI.insets(6, 8, 6, 8)
            }
            DebugLog.info("ChatToolWindow", "addMessageBubbleToActiveTab [EDT]: constraints gridx=${constraints.gridx} gridy=${constraints.gridy}(row now ${activeConv.currentRow}) weightx=${constraints.weightx} weighty=${constraints.weighty} fill=${constraints.fill} anchor=${constraints.anchor}")

            activeConv.messageContainer.add(componentToAdd, constraints)
            DebugLog.info("ChatToolWindow", "addMessageBubbleToActiveTab [EDT]: bubble added, componentCount(afterAdd)=${activeConv.messageContainer.componentCount}, lastComponent=${if (activeConv.messageContainer.componentCount > 0) activeConv.messageContainer.getComponent(activeConv.messageContainer.componentCount - 1).javaClass.simpleName else "N/A"}")

            activeConv.fillerGbc.gridy = activeConv.currentRow
            activeConv.messageContainer.add(activeConv.fillerComponent, activeConv.fillerGbc)
            DebugLog.info("ChatToolWindow", "addMessageBubbleToActiveTab [EDT]: filler re-added at gridy=${activeConv.currentRow}, componentCount(final)=${activeConv.messageContainer.componentCount}")

            activeConv.messageContainer.revalidate()
            DebugLog.info("ChatToolWindow", "addMessageBubbleToActiveTab [EDT]: revalidate() called")
            activeConv.messageContainer.repaint()
            DebugLog.info("ChatToolWindow", "addMessageBubbleToActiveTab [EDT]: repaint() called, messageContainer size now=${activeConv.messageContainer.width}x${activeConv.messageContainer.height}")

            SwingUtilities.invokeLater {
                val scrollBar = activeConv.scrollPane.verticalScrollBar
                scrollBar.value = scrollBar.maximum
                DebugLog.info("ChatToolWindow", "addMessageBubbleToActiveTab [EDT]: scrollBar.value set to ${scrollBar.value} (max=${scrollBar.maximum}), isShowing now=${componentToAdd.isShowing}, bubble bounds=${componentToAdd.bounds}")
            }

            DebugLog.info("ChatToolWindow", "addMessageBubbleToActiveTab [EDT]: component added, size=${componentToAdd.width}x${componentToAdd.height}, container size=${activeConv.messageContainer.width}x${activeConv.messageContainer.height}")
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
            border = JBUI.Borders.emptyTop(4)
        }
        card.add(titleLabel, BorderLayout.NORTH)
        card.add(body, BorderLayout.CENTER)
        return card
    }

    /**
     * Debug-only entry point: creates a "Hello World" bubble through the exact
     * same pipeline as a regular assistant message and logs every single step.
     * Does NOT change any production logic — purely diagnostic.
     */
    private fun addDebugHelloWorldBubble() {
        DebugLog.info("DebugBubble", "==================== DEBUG BUBBLE TRIGGERED ====================")
        DebugLog.info("DebugBubble", "isEventDispatchThread=${SwingUtilities.isEventDispatchThread()}")
        DebugLog.info("DebugBubble", "activeConversationId=$activeConversationId, currentPhase=$currentPhase")

        val activeConv = conversationTabPanel.getActiveConversation()
        DebugLog.info("DebugBubble", "activeConv (pre-lookup)=$activeConv")
        if (activeConv == null) {
            DebugLog.error("DebugBubble", "NO ACTIVE CONVERSATION FOUND - aborting before bubble creation")
            return
        }
        DebugLog.info("DebugBubble", "conv.id=${activeConv.id}")
        DebugLog.info("DebugBubble", "conv.messageContainer class=${activeConv.messageContainer.javaClass.name}")
        DebugLog.info("DebugBubble", "conv.messageContainer size=${activeConv.messageContainer.width}x${activeConv.messageContainer.height}")
        DebugLog.info("DebugBubble", "conv.messageContainer isDisplayable=${activeConv.messageContainer.isDisplayable}, isShowing=${activeConv.messageContainer.isShowing}, isVisible=${activeConv.messageContainer.isVisible}")
        DebugLog.info("DebugBubble", "conv.currentRow=${activeConv.currentRow}, componentCount(before)=${activeConv.messageContainer.componentCount}")
        DebugLog.info("DebugBubble", "conv.scrollPane=${activeConv.scrollPane.javaClass.simpleName}, viewportSize=${activeConv.scrollPane.viewport.width}x${activeConv.scrollPane.viewport.height}")
        DebugLog.info("DebugBubble", "conv.history.size=${activeConv.history.size}, uiLog.size=${activeConv.uiLog.size}")

        val helloText = "Hello World! Debug bubble ${System.currentTimeMillis()}\n" +
            "Second line of the debug message to force multi-line HTML layout."
        DebugLog.info("DebugBubble", "helloText.length=${helloText.length}")
        DebugLog.info("DebugBubble", "calling addMessageBubbleToActiveTab(role='assistant', recordUiLog=false) on EDT=${SwingUtilities.isEventDispatchThread()}")

        addMessageBubbleToActiveTab("assistant", helloText, recordUiLog = false)

        DebugLog.info("DebugBubble", "addMessageBubbleToActiveTab returned synchronously")
        DebugLog.info("DebugBubble", "componentCount(after)=${activeConv.messageContainer.componentCount} (bubble body may still be queued in invokeLater)")

        // Step 1: log as soon as the invokeLater body queued by
        // addMessageBubbleToActiveTab has actually run.
        SwingUtilities.invokeLater {
            DebugLog.info("DebugBubble", "EDT-after-add step: isEDT=${SwingUtilities.isEventDispatchThread()}, componentCount=${activeConv.messageContainer.componentCount}")
            logComponentTree(activeConv.messageContainer, indent = "  ")
        }

        // Step 2: dump the full component tree again once layout has settled,
        // so we can see the final bounds/font/colors of the bubble and its text.
        javax.swing.Timer(1200) {
            DebugLog.info("DebugBubble", "TIMER 1200ms: componentCount=${activeConv.messageContainer.componentCount}")
            logComponentTree(activeConv.messageContainer, indent = "  ")
        }.apply { isRepeats = false }.start()
    }

    /**
     * Recursively logs the class, bounds, visibility, font and foreground of a
     * component and all of its children. Used to spot the exact node where the
     * text becomes invisible (zero size, hidden, wrong color, wrong font).
     */
    private fun logComponentTree(component: javax.swing.JComponent, indent: String) {
        val fontInfo = component.font?.let { "font='${it.fontName}' size=${it.size} style=${it.style}" } ?: "font=null"
        val fg = component.foreground
        val fgInfo = if (fg != null) "#%06X".format(fg.rgb and 0xFFFFFF) else "null"
        val bg = component.background
        val bgInfo = if (bg != null) "#%06X".format(bg.rgb and 0xFFFFFF) else "null"
        val peerInfo = if (component is javax.swing.JTextPane) {
            " docLen=${component.document.length} textLen=${component.text?.length} contentType=${component.contentType}"
        } else {
            ""
        }
        DebugLog.info(
            "DebugBubble",
            "$indent${component.javaClass.simpleName} bounds=${component.x},${component.y} ${component.width}x${component.height} " +
                "pref=${component.preferredSize.width}x${component.preferredSize.height} " +
                "visible=${component.isVisible} showing=${component.isShowing} opaque=${component.isOpaque} " +
                "fg=$fgInfo bg=$bgInfo $fontInfo$peerInfo"
        )
        for (child in component.components) {
            if (child is javax.swing.JComponent) {
                logComponentTree(child, indent + "    ")
            } else {
                DebugLog.info("DebugBubble", "$indent    ${child.javaClass.simpleName} bounds=${child.bounds} visible=${child.isVisible}")
            }
        }
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
        activeConversationId = null
        SwingUtilities.invokeLater {
            statusLabel.text = "Stopped"
            enhancedInputPanel.updateRunningState(false)
        }
    }

    private fun executePrompt(promptText: String, displayText: String = promptText, referencedFiles: List<String> = emptyList()) {
        DebugLog.info("ChatToolWindow", "=== EXECUTE PROMPT ===")
        DebugLog.info("ChatToolWindow", "Prompt: ${promptText.take(200)}")
        DebugLog.info("ChatToolWindow", "Settings - baseUrl: ${settings.state.baseUrl}, model: ${settings.state.model}, apiKey set: ${settings.isApiKeySet()}")
        cardLayout.show(this, CHAT_CARD)

        activeConversationId = conversationTabPanel.getActiveTabId()
        DebugLog.info("ChatToolWindow", "Active conversation locked for run: $activeConversationId")
        addMessageBubbleToActiveTab("user", displayText, referencedFiles)

        if (promptText.startsWith("/")) {
            val res = SlashCommands.processCommand(promptText, project.basePath ?: "")
            if (res != null) {
                DebugLog.info("ChatToolWindow", "Slash command handled locally, returning response length=${res.length}")
                addMessageBubbleToActiveTab("assistant", res)
                activeConversationId = null
                return
            }
        }

        val userMsg = ChatMessage(MessageRole.USER, promptText)
        DebugLog.info("ChatToolWindow", "User message created for LLM request, contentLength=${promptText.length}")
        statusLabel.text = "Agent running..."
        enhancedInputPanel.updateRunningState(true)

        activeEngineJob = scope.launch {
            DebugLog.info("ChatToolWindow", "Creating ApiClient with baseUrl=${settings.state.baseUrl}, model=${settings.state.model}")
            val client = ApiClient(
                baseUrl = settings.state.baseUrl,
                apiKey = settings.getApiKey() ?: "",
                model = settings.state.model
            )
            DebugLog.info("ChatToolWindow", "ApiClient created (non-streaming mode), no streaming panel needed")

            // Note: In non-streaming mode, we don't create a streaming placeholder panel.
            // The actual response will be added when AgentDelta.Assistant arrives.
            // This avoids the issue of leftover streaming panels with empty content.

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
                            DebugLog.info("AgentEngine", "Status: ${delta.text}")
                            SwingUtilities.invokeLater { statusLabel.text = delta.text }
                        }
                        is AgentDelta.Assistant -> {
                            DebugLog.info("AgentEngine", "Assistant response received, length=${delta.text.length}")
                            // Remove the streaming placeholder and add the actual response
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
                            DebugLog.info("AgentEngine", "Rendering assistant response into conversation $activeConversationId")
                            addMessageBubbleToActiveTab("assistant", delta.text)
                        }
                        is AgentDelta.ToolOutput -> {
                            DebugLog.info("AgentEngine", "Tool output: ${delta.name} - ${delta.text.take(80)}...")
                            activeStreamingPanel?.let { panel ->
                                panel.finalize()
                                activeStreamingPanel = null
                            }
                            addMessageBubbleToActiveTab("tool: ${delta.name}", delta.text)
                        }
                        is AgentDelta.StreamingStart -> {
                            DebugLog.info("AgentEngine", "Streaming started")
                        }
                        is AgentDelta.StreamingContent -> {
                            DebugLog.info("AgentEngine", "Streaming content: ${delta.text.take(50)}...")
                            activeStreamingPanel?.appendText(delta.text)
                        }
                        is AgentDelta.StreamingEnd -> {
                            DebugLog.info("AgentEngine", "Streaming ended, fullText length: ${delta.fullText.length}")
                            if (delta.fullText.isNotBlank()) {
                                activeStreamingPanel?.let { panel ->
                                    panel.finalize()
                                    activeStreamingPanel = null
                                }
                            } else {
                                DebugLog.warn("AgentEngine", "Streaming ended with empty content!")
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
                DebugLog.info("AgentEngine", "Starting agent loop, phase: $currentPhase")
                val activeConv = conversationTabPanel.getActiveConversation()
                val targetConv = activeConversationId?.let { conversationTabPanel.getConversation(it) } ?: activeConv
                val tabHistory = targetConv?.history ?: mutableListOf()
                val tabUiLog = targetConv?.uiLog ?: mutableListOf()
                DebugLog.info("AgentEngine", "Conversation state before run: historySize=${tabHistory.size}, uiLogSize=${tabUiLog.size}, targetConv=${targetConv?.id}")

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
                DebugLog.info("AgentEngine", "Agent loop finished: newMessages=${newMsgs.size}")
                tabHistory.addAll(newMsgs)
                persistence.saveSession(
                    SessionState(
                        history = tabHistory,
                        uiLog = tabUiLog.toList(),
                        todoList = todoList,
                        savedAt = System.currentTimeMillis()
                    )
                )
                DebugLog.info("AgentEngine", "Session saved: historySize=${tabHistory.size}, uiLogSize=${tabUiLog.size}")
            } catch (e: Exception) {
                val errorMsg = e.message ?: e::class.simpleName ?: "Unknown error"
                DebugLog.error("AgentEngine", "Agent loop failed: $errorMsg", e)
                activeStreamingPanel?.let { panel ->
                    panel.finalize()
                    activeStreamingPanel = null
                }
                addMessageBubbleToActiveTab("error", errorMsg)
            } finally {
                // Ensure streaming panel is cleaned up
                activeStreamingPanel?.let { panel ->
                    panel.finalize()
                    activeStreamingPanel = null
                }
                activeConversationId = null
                SwingUtilities.invokeLater {
                    statusLabel.text = "Ready"
                    enhancedInputPanel.updateRunningState(false)
                }
                DebugLog.info("AgentEngine", "Prompt execution finished and UI reset")
            }
        }
    }
}
