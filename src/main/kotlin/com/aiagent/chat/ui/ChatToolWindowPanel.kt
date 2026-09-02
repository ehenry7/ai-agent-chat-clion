package com.aiagent.chat.ui

import com.aiagent.chat.agent.AgentDelta
import com.aiagent.chat.agent.AgentEngine
import com.aiagent.chat.model.ChatMessage
import com.aiagent.chat.model.MessageRole
import com.aiagent.chat.model.SessionState
import com.aiagent.chat.model.TodoItem
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
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.*
import javax.swing.text.html.HTMLEditorKit

class ChatToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ChatToolWindowPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}

class CollapsibleToolPanel(private val title: String, contentText: String, private val onToggle: () -> Unit) : JPanel(BorderLayout()) {
    private val contentPane = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        text = contentText
        background = JBColor.PanelBackground
        border = JBUI.Borders.empty(4, 8)
        font = java.awt.Font(java.awt.Font.MONOSPACED, this.font.style, this.font.size)
    }
    private var isExpanded = false

    init {
        val header = JPanel(BorderLayout()).apply {
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            background = JBColor(0xDFDFDF, 0x404245)
            border = JBUI.Borders.empty(4, 8)
        }
        val titleLabel = JBLabel("▸ $title").apply { font = font.deriveFont(java.awt.Font.BOLD) }
        header.add(titleLabel, BorderLayout.WEST)

        header.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                isExpanded = !isExpanded
                titleLabel.text = if (isExpanded) "▾ $title" else "▸ $title"
                contentPane.isVisible = isExpanded
                onToggle()
            }
        })

        contentPane.isVisible = false
        add(header, BorderLayout.NORTH)
        add(contentPane, BorderLayout.CENTER)
        border = JBUI.Borders.customLine(JBColor.border(), 1)
        alignmentX = JComponent.LEFT_ALIGNMENT
    }
}

class ChatToolWindowPanel(private val project: Project) : JBPanel<ChatToolWindowPanel>(CardLayout()) {
    private val cardLayout = layout as CardLayout
    private val CHAT_CARD = "CHAT_CARD"
    private val SETUP_CARD = "SETUP_CARD"

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val persistence = PersistenceManager(project.basePath ?: "")
    private val settings = ApplicationManager.getApplication().getService(ChatStateService::class.java)

    private val messageContainer = JPanel().apply {
        layout = java.awt.GridBagLayout()
        background = JBColor.PanelBackground
    }
    private var currentRow = 0
    private val fillerComponent = JPanel().apply { background = JBColor.PanelBackground }
    private val fillerGbc = java.awt.GridBagConstraints().apply {
        gridx = 0
        gridy = 9999
        weightx = 1.0
        weighty = 1.0
        fill = java.awt.GridBagConstraints.BOTH
    }

    private val scrollPane = JBScrollPane(messageContainer).apply {
        border = JBUI.Borders.empty()
        // Prevent horizontal scrolling completely
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
    }

    private val inputArea = JBTextArea(3, 20).apply {
        lineWrap = true
        wrapStyleWord = true
    }

    private val sendBtn = JButton("Send")
    private val steerBtn = JButton(AllIcons.Actions.Execute).apply {
        toolTipText = "Steer Agent"
        isEnabled = false
    }
    private val settingsBtn = JButton(AllIcons.General.Settings).apply {
        toolTipText = "Configure AI Agent"
    }
    private val statusLabel = JBLabel("Ready")

    private val baseUrlField = JBTextField()
    private val modelField = JBTextField()
    private val apiKeyField = JBPasswordField()

    private val promptHistory = mutableListOf<String>()
    private var historyIndex = -1
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
    private val conversationHistory = mutableListOf<ChatMessage>()

    private val toolHandler = PlatformToolHandler(
        project = project,
        getMemory = { persistence.loadFolderMemory() },
        setMemory = { persistence.saveFolderMemory(it) },
        getGlobalMemory = { persistence.loadGlobalMemory() },
        setGlobalMemory = { persistence.saveGlobalMemory(it) },
        getTodoList = { todoList },
        setTodoList = { todoList = it }
    )

    init {
        val chatPanel = JPanel(BorderLayout())
        chatPanel.add(scrollPane, BorderLayout.CENTER)
        chatPanel.add(buildBottomPanel(), BorderLayout.SOUTH)

        val setupPanel = buildSetupPanel()

        add(chatPanel, CHAT_CARD)
        add(setupPanel, SETUP_CARD)

        messageContainer.add(fillerComponent, fillerGbc)

        if (!settings.isApiKeySet()) {
            cardLayout.show(this, SETUP_CARD)
        } else {
            cardLayout.show(this, CHAT_CARD)
        }

        persistence.loadSession()?.let { state ->
            conversationHistory.addAll(state.history)
            todoList = state.todoList
            state.uiLog.forEach { addMessageBubble(it.role, it.text) }
        }

        setupInputListeners()

        sendBtn.addActionListener {
            val text = inputArea.text.trim()
            if (activeEngineJob?.isActive == true) {
                inputArea.text = ""
                addMessageBubble("user (steering)", text)
            } else if (text.isNotEmpty()) {
                inputArea.text = ""
                promptHistory.add(text)
                historyIndex = promptHistory.size
                executePrompt(text)
            }
        }

        steerBtn.addActionListener {
            val text = inputArea.text.trim()
            if (activeEngineJob?.isActive == true && text.isNotEmpty()) {
                inputArea.text = ""
                addMessageBubble("user (steering)", text)
            }
        }

        settingsBtn.addActionListener {
            baseUrlField.text = settings.state.baseUrl
            modelField.text = settings.state.model
            apiKeyField.text = settings.getApiKey() ?: ""
            cardLayout.show(this, SETUP_CARD)
        }
    }

    private fun setupInputListeners() {
        inputArea.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_UP) {
                    if (historyIndex > 0) {
                        historyIndex--
                        inputArea.text = promptHistory[historyIndex]
                    }
                } else if (e.keyCode == KeyEvent.VK_DOWN) {
                    if (historyIndex < promptHistory.size - 1) {
                        historyIndex++
                        inputArea.text = promptHistory[historyIndex]
                    } else {
                        historyIndex = promptHistory.size
                        inputArea.text = ""
                    }
                }
            }

            override fun keyTyped(e: KeyEvent) {
                if (e.keyChar == '@') {
                    SwingUtilities.invokeLater { showFileMentionPopup() }
                }
            }
        })
    }

    private fun showFileMentionPopup() {
        val openFiles = FileEditorManager.getInstance(project).openFiles
        if (openFiles.isEmpty()) return

        val fileNames = openFiles.map { it.name }.toList()

        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(fileNames)
            .setTitle("Mention File")
            .setItemChosenCallback { selectedValue ->
                if (selectedValue != null) {
                    val file = openFiles.find { it.name == selectedValue }
                    if (file != null) {
                        val path = file.path
                        val caretPos = inputArea.caretPosition
                        val currentText = inputArea.text
                        val textBefore = currentText.substring(0, caretPos)
                        val textAfter = currentText.substring(caretPos)
                        inputArea.text = "$textBefore$path$textAfter"
                        inputArea.caretPosition = caretPos + path.length + 1
                    }
                }
            }
            .createPopup()
            .showUnderneathOf(inputArea)
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
                }.align(AlignX.RIGHT)
            }
        }.apply { border = JBUI.Borders.empty(16) }
    }

    private fun buildBottomPanel(): JComponent {
        return panel {
            row {
                cell(statusLabel)
                cell(settingsBtn).align(AlignX.RIGHT)
            }
            row { cell(JBScrollPane(inputArea)).align(Align.FILL) }
            row {
                cell(sendBtn)
                cell(steerBtn)
                cell(phaseBtn)
            }
        }
    }

    private fun String.toBasicHtml(): String {
        val escaped = this.replace("<", "&lt;").replace(">", "&gt;")
        val blocks = escaped.split("```")
        val sb = java.lang.StringBuilder()

        for (i in blocks.indices) {
            if (i % 2 == 1) {
                val codeContent = blocks[i].substringAfter("\n", blocks[i])
                // Added max-width and word-wrap properties to code blocks to prevent overflow
                sb.append("<pre style='background-color: #2b2b2b; color: #a9b7c6; padding: 6px; white-space: pre-wrap; word-wrap: break-word;'><code>$codeContent</code></pre>")
            } else {
                var text = blocks[i]

                text = text.replace(Regex("\\*\\*([^*]+)\\*\\*"), "<b>$1</b>")
                text = text.replace(Regex("\\*([^*]+)\\*"), "<i>$1</i>")
                text = text.replace(Regex("`([^`]+)`"), "<code>$1</code>")

                val lines = text.split("\n")
                var inTable = false

                for (line in lines) {
                    val tLine = line.trim()

                    if (tLine.startsWith("|") && tLine.endsWith("|")) {
                        if (!inTable) {
                            sb.append("<table border='1' style='border-collapse: collapse; margin: 8px 0; width: 100%;'>")
                            inTable = true
                        }
                        if (tLine.replace(Regex("[|\\- ]"), "").isEmpty()) continue

                        sb.append("<tr>")
                        val cells = tLine.removePrefix("|").removeSuffix("|").split("|")
                        for (cell in cells) {
                            sb.append("<td style='padding: 4px; border: 1px solid #777777;'>${cell.trim()}</td>")
                        }
                        sb.append("</tr>")
                    } else {
                        if (inTable) {
                            sb.append("</table>")
                            inTable = false
                        }

                        when {
                            tLine.startsWith("### ") -> sb.append("<h3>${tLine.substring(4)}</h3>")
                            tLine.startsWith("## ") -> sb.append("<h2>${tLine.substring(3)}</h2>")
                            tLine.startsWith("# ") -> sb.append("<h1>${tLine.substring(2)}</h1>")
                            tLine.startsWith("- ") -> sb.append("&#8226; ${tLine.substring(2)}<br>")
                            else -> sb.append(line).append("<br>")
                        }
                    }
                }
                if (inTable) sb.append("</table>")
            }
        }
        return sb.toString().replace("<br><br><br>", "<br><br>")
    }

    private fun addMessageBubble(role: String, text: String) {
        if (text.isBlank() && !role.startsWith("tool")) return

        SwingUtilities.invokeLater {
            val componentToAdd: JComponent = if (role.startsWith("tool")) {
                val toolName = role.removePrefix("tool:").trim()
                CollapsibleToolPanel(toolName, text) {
                    messageContainer.revalidate()
                    messageContainer.repaint()
                }
            } else {
                val card = JPanel(BorderLayout()).apply {
                    // Added internal padding (10px left/right) and styling to ensure text wrapping and neat bounds
                    border = JBUI.Borders.compound(
                        JBUI.Borders.customLine(JBColor.border(), 1),
                        JBUI.Borders.empty(8, 12)
                    )
                    background = if (role.contains("user")) JBColor(0xEEEEEE, 0x2D2F31) else JBColor(0xFAFAFA, 0x232527)
                    alignmentX = JComponent.LEFT_ALIGNMENT
                }
                val title = JBLabel("[$role]").apply { font = font.deriveFont(java.awt.Font.BOLD) }

                val body = JEditorPane().apply {
                    contentType = "text/html"
                    editorKit = HTMLEditorKit()
                    isEditable = false
                    // Wrapped in a container with word-wrap styling to prevent horizontal overflow
                    this.text = "<html><body style='font-family: sans-serif; font-size: 12px; word-wrap: break-word;'><div style='width: 100%;'>${text.toBasicHtml()}</div></body></html>"
                    background = card.background
                    putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
                }

                card.add(title, BorderLayout.NORTH)
                card.add(body, BorderLayout.CENTER)
                card
            }

            messageContainer.remove(fillerComponent)

            val constraints = java.awt.GridBagConstraints().apply {
                gridx = 0
                gridy = currentRow++
                weightx = 1.0
                weighty = 0.0
                fill = java.awt.GridBagConstraints.HORIZONTAL
                anchor = java.awt.GridBagConstraints.NORTH
                insets = JBUI.insets(4, 8, 4, 8) // Left and right margins for the bubble rows
            }

            messageContainer.add(componentToAdd, constraints)

            fillerGbc.gridy = currentRow
            messageContainer.add(fillerComponent, fillerGbc)

            messageContainer.revalidate()
            messageContainer.repaint()

            val scrollBar = scrollPane.verticalScrollBar
            scrollBar.value = scrollBar.maximum
        }
    }

    private fun executePrompt(promptText: String) {
        addMessageBubble("user", promptText)

        if (promptText.startsWith("/")) {
            val res = SlashCommands.processCommand(promptText, project.basePath ?: "")
            if (res != null) {
                addMessageBubble("assistant", res)
                return
            }
        }

        val userMsg = ChatMessage(MessageRole.USER, promptText)
        statusLabel.text = "Agent running..."
        steerBtn.isEnabled = true
        sendBtn.text = "Steer"

        activeEngineJob = scope.launch {
            val client = ApiClient(
                baseUrl = settings.state.baseUrl,
                apiKey = settings.getApiKey() ?: "",
                model = settings.state.model
            )

            val engine = AgentEngine(
                client = client,
                toolExecutor = { name, args -> toolHandler.execute(name, args) },
                onDelta = { delta ->
                    when (delta) {
                        is AgentDelta.Status -> SwingUtilities.invokeLater { statusLabel.text = delta.text }
                        is AgentDelta.Assistant -> addMessageBubble("assistant", delta.text)
                        is AgentDelta.ToolOutput -> addMessageBubble("tool: ${delta.name}", delta.text)
                    }
                }
            )

            try {
                val newMsgs = engine.runAgentLoop(
                    initialHistory = conversationHistory,
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
                    }
                )
                conversationHistory.addAll(newMsgs)
                persistence.saveSession(
                    SessionState(
                        history = conversationHistory,
                        todoList = todoList,
                        savedAt = System.currentTimeMillis()
                    )
                )
            } catch (e: Exception) {
                addMessageBubble("error", e.message ?: "Failed")
            } finally {
                SwingUtilities.invokeLater {
                    statusLabel.text = "Ready"
                    steerBtn.isEnabled = false
                    sendBtn.text = "Send"
                }
            }
        }
    }
}
