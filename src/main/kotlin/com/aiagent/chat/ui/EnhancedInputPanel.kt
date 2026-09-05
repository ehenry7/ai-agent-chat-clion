package com.aiagent.chat.ui

import com.aiagent.chat.debug.DebugLog
import com.aiagent.chat.tools.SlashCommands
import com.intellij.icons.AllIcons
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.geom.Area
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import javax.swing.*

/**
 * Enhanced input panel with rounded corners, file mention tags, and model selector.
 *
 * Phase 4: Enhanced Input Panel with @ lookup and tags.
 * Inspired by ProxyAI's UserInputPanel and PromptTextField architecture.
 */
class EnhancedInputPanel(
    private val project: Project,
    private val onSubmit: (String, List<FileTag>) -> Unit,
    private val onSteer: (String) -> Unit,
    private val onStop: () -> Unit = {},
    private val isRunning: () -> Boolean,
    private val currentModel: () -> String,
    private val onModelChange: (String) -> Unit
) : JBPanel<EnhancedInputPanel>(BorderLayout()) {

    companion object {
        private const val CORNER_RADIUS = 12
    }

    private val tagsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
        isOpaque = false
    }

    private val fileTags = mutableListOf<FileTag>()

    private val inputArea = JBTextArea(3, 40).apply {
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(6, 10)
        background = JBColor.PanelBackground
    }

    private val sendBtn = JButton(AllIcons.Actions.Forward).apply {
        toolTipText = "Send message"
        preferredSize = Dimension(28, 28)
    }

    private val stopBtn = JButton(AllIcons.Actions.Suspend).apply {
        toolTipText = "Stop Agent"
        isEnabled = false
        isVisible = false
        preferredSize = Dimension(28, 28)
    }

    private val modelSelector = ComboBox<String>().apply {
        toolTipText = "Select model"
        preferredSize = Dimension(150, 28)
    }

    private var promptHistory = mutableListOf<String>()
    private var historyIndex = -1

    private var slashPopup: com.intellij.openapi.ui.popup.JBPopup? = null

    init {
        isOpaque = false
        border = JBUI.Borders.empty(4, 4, 4, 4)

        setupLayout()
        setupListeners()

        modelSelector.addActionListener {
            val selected = modelSelector.selectedItem as? String ?: return@addActionListener
            if (selected != currentModel()) {
                DebugLog.info("EnhancedInputPanel", "Model selector changed to '$selected'")
                onModelChange(selected)
            }
        }
    }

    private fun setupLayout() {
        // Tags panel (above text area)
        val tagsWrapper = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(2, 4, 0, 4)
            add(tagsPanel, BorderLayout.CENTER)
        }

        // Text area in a scroll pane
        val scrollPane = JBScrollPane(inputArea).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            preferredSize = Dimension(0, 60)
        }

        // Center: tags + text area
        val centerPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(tagsWrapper, BorderLayout.NORTH)
            add(scrollPane, BorderLayout.CENTER)
        }

        // Footer: model selector + buttons
        val footerPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 2)).apply {
            isOpaque = false
            add(modelSelector)
            add(Box.createHorizontalStrut(8))
            add(stopBtn)
            add(sendBtn)
        }

        add(centerPanel, BorderLayout.CENTER)

        val southWrapper = JPanel(BorderLayout())
        southWrapper.isOpaque = false

        southWrapper.add(footerPanel, BorderLayout.NORTH)

        // Gradient highlight - thin centered bar
        val gradientBar = object : JComponent() {
            override fun getPreferredSize(): Dimension = Dimension(0, 4)
            
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    val barWidth = 120
                    val barHeight = 3
                    val x = (width - barWidth) / 2
                    val y = 0
                    val gradient = GradientPaint(
                        x.toFloat(), 0f, JBColor(0x6BA6E6, 0x3A6FA8),
                        (x + barWidth).toFloat(), 0f, JBColor(0x1A4A8A, 0x0D2A55)
                    )
                    g2.paint = gradient
                    g2.fillRoundRect(x, y, barWidth, barHeight, barHeight, barHeight)
                } finally {
                    g2.dispose()
                }
            }
            override fun isOpaque(): Boolean = false
        }
        gradientBar.isOpaque = false
        val gradientPanel = JPanel(FlowLayout(FlowLayout.CENTER, 0, 1)).apply {
            isOpaque = false
            add(gradientBar)
        }
        southWrapper.add(gradientPanel, BorderLayout.CENTER)

        add(southWrapper, BorderLayout.SOUTH)
    }

    private fun setupListeners() {
        inputArea.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                // --- Slash command popup navigation ---
                if (slashPopup?.isDisposed == false) {
                    when (e.keyCode) {
                        KeyEvent.VK_ESCAPE -> {
                            e.consume()
                            slashPopup?.cancel()
                            slashPopup = null
                            return
                        }
                        KeyEvent.VK_ENTER -> {
                            e.consume()
                            // Let the popup handle the selection
                            return
                        }
                        KeyEvent.VK_UP, KeyEvent.VK_DOWN -> {
                            // Let the popup handle arrow navigation
                            return
                        }
                    }
                }

                when {
                    e.keyCode == KeyEvent.VK_ENTER && !e.isShiftDown -> {
                        e.consume()
                        handleSubmit()
                    }
                    e.keyCode == KeyEvent.VK_UP -> {
                        if (historyIndex > 0) {
                            historyIndex--
                            inputArea.text = promptHistory[historyIndex]
                        }
                    }
                    e.keyCode == KeyEvent.VK_DOWN -> {
                        if (historyIndex < promptHistory.size - 1) {
                            historyIndex++
                            inputArea.text = promptHistory[historyIndex]
                        } else {
                            historyIndex = promptHistory.size
                            inputArea.text = ""
                        }
                    }
                }
            }

            override fun keyTyped(e: KeyEvent) {
                if (e.keyChar == '@') {
                    SwingUtilities.invokeLater { showFileMentionPopup() }
                }
            }

            override fun keyReleased(e: KeyEvent) {
                // Show or update slash popup based on current text
                val text = inputArea.text
                if (text.startsWith("/") && !text.contains(" ") && !text.contains("\n")) {
                    SwingUtilities.invokeLater { showOrUpdateSlashPopup(text) }
                } else if (slashPopup?.isDisposed == false) {
                    slashPopup?.cancel()
                    slashPopup = null
                }
            }
        })

        sendBtn.addActionListener { handleSubmit() }

        stopBtn.addActionListener {
            onStop()
        }
    }

    private fun handleSubmit() {
        val text = inputArea.text.trim()
        if (text.isEmpty()) return

        DebugLog.info(
            "EnhancedInputPanel",
            "handleSubmit: running=${isRunning()}, textLength=${text.length}, fileTags=${fileTags.size}"
        )

        if (isRunning()) {
            // Steer mode
            inputArea.text = ""
            DebugLog.info("EnhancedInputPanel", "Submitting steer text to active agent, textLength=${text.length}")
            onSteer(text)
        } else {
            promptHistory.add(text)
            historyIndex = promptHistory.size
            inputArea.text = ""
            DebugLog.info("EnhancedInputPanel", "Submitting new prompt to chat handler, textLength=${text.length}")
            onSubmit(text, fileTags.toList())
            clearTags()
        }
    }

    private fun showFileMentionPopup() {
        val openFiles = FileEditorManager.getInstance(project).openFiles
        if (openFiles.isEmpty()) return

        val fileNames = openFiles.map { it.name }

        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(fileNames)
            .setTitle("Mention File")
            .setItemChosenCallback { selectedName ->
                val file = openFiles.find { it.name == selectedName }
                if (file != null) {
                    addFileTag(file)
                    // Remove the trailing @
                    val caretPos = inputArea.caretPosition
                    val currentText = inputArea.text
                    if (caretPos > 0 && currentText[caretPos - 1] == '@') {
                        inputArea.text = currentText.removeRange(caretPos - 1, caretPos)
                    }
                }
            }
            .createPopup()
            .showUnderneathOf(inputArea)
    }

    private fun showOrUpdateSlashPopup(typedText: String) {
        // Build list of matching commands from SlashCommands.BUILT_IN
        val query = typedText.removePrefix("/").lowercase()
        val allCommands = SlashCommands.BUILT_IN.values.map { cmd ->
            "/${cmd.name} - ${cmd.description}"
        }
        val filtered = if (query.isEmpty()) {
            allCommands
        } else {
            allCommands.filter { it.substringAfter("/").startsWith(query) }
        }

        // If no matches, close any existing popup
        if (filtered.isEmpty()) {
            if (slashPopup?.isDisposed == false) {
                slashPopup?.cancel()
                slashPopup = null
            }
            return
        }

        // If popup is already showing, just update the list
        if (slashPopup?.isDisposed == false) {
            // Close and recreate to update the filtered list
            slashPopup?.cancel()
            slashPopup = null
        }

        // Create new popup with filtered commands
        val popup = JBPopupFactory.getInstance()
            .createPopupChooserBuilder(filtered)
            .setTitle("Slash Commands")
            .setItemChosenCallback { selectedItem ->
                // Extract the command name (e.g. "/help - Show..." -> "/help")
                val cmdName = selectedItem.substringBefore(" -")
                inputArea.text = cmdName
                inputArea.caretPosition = cmdName.length
                inputArea.requestFocusInWindow()
            }
            .setResizable(false)
            .setMovable(false)
            .createPopup()

        popup.showUnderneathOf(inputArea)
        slashPopup = popup
    }

    private fun addFileTag(file: VirtualFile) {
        val tag = FileTag(file.name, file.path)
        if (fileTags.any { it.path == tag.path }) return

        fileTags.add(tag)

        val chip = FileTagChip(tag) {
            fileTags.remove(tag)
            tagsPanel.remove(it)
            tagsPanel.revalidate()
            tagsPanel.repaint()
        }

        tagsPanel.add(chip)
        tagsPanel.revalidate()
        tagsPanel.repaint()
    }

    private fun clearTags() {
        fileTags.clear()
        tagsPanel.removeAll()
        tagsPanel.revalidate()
        tagsPanel.repaint()
    }

    fun updateRunningState(running: Boolean) {
        SwingUtilities.invokeLater {
            if (running) {
                sendBtn.toolTipText = "Steer agent"
                stopBtn.isEnabled = true
                stopBtn.isVisible = true
            } else {
                sendBtn.toolTipText = "Send message"
                stopBtn.isEnabled = false
                stopBtn.isVisible = false
            }
        }
    }

    fun updateModelList(models: List<String>) {
        SwingUtilities.invokeLater {
            modelSelector.removeAllItems()
            models.forEach { modelSelector.addItem(it) }
            val current = currentModel()
            if (models.contains(current)) {
                modelSelector.selectedItem = current
            }
        }
    }

    override fun requestFocus() {
        inputArea.requestFocusInWindow()
    }

    fun setText(text: String) {
        inputArea.text = text
        inputArea.requestFocusInWindow()
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val area = createRoundedArea()
            g2.clip = area
            g2.color = JBColor.PanelBackground
            g2.fill(area)
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }

    override fun paintBorder(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = if (inputArea.isFocusOwner) {
                JBUI.CurrentTheme.Focus.defaultButtonColor()
            } else {
                JBColor.border()
            }
            g2.stroke = if (inputArea.isFocusOwner) BasicStroke(1.5f) else BasicStroke(1f)
            g2.drawRoundRect(0, 0, width - 1, height - 1, CORNER_RADIUS, CORNER_RADIUS)
        } finally {
            g2.dispose()
        }
    }

    private fun createRoundedArea(): Area {
        val bounds = Rectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat())
        val roundedRect = RoundRectangle2D.Float(
            0f, 0f, width.toFloat(), height.toFloat(),
            CORNER_RADIUS.toFloat(), CORNER_RADIUS.toFloat()
        )
        val area = Area(bounds)
        area.intersect(Area(roundedRect))
        return area
    }

    data class FileTag(val name: String, val path: String)

    /**
     * A removable chip component for displaying a referenced file tag.
     */
    private class FileTagChip(
        tag: FileTag,
        val onRemove: (FileTagChip) -> Unit
    ) : JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)) {

        init {
            isOpaque = false
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor(0x555555, 0x666666), 1),
                JBUI.Borders.empty(1, 4)
            )
            background = JBColor(0xE8EAF0, 0x333740)

            val icon = JBLabel(AllIcons.FileTypes.Any_type).apply {
                isOpaque = false
            }
            val name = JBLabel(tag.name).apply {
                isOpaque = false
            }
            val removeBtn = JButton(AllIcons.Actions.Close).apply {
                isContentAreaFilled = false
                isBorderPainted = false
                isFocusPainted = false
                preferredSize = Dimension(14, 14)
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                toolTipText = "Remove"
                addActionListener { onRemove(this@FileTagChip) }
            }

            add(icon)
            add(name)
            add(removeBtn)
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = JBColor(0xE8EAF0, 0x333740)
                g2.fillRoundRect(0, 0, width - 1, height - 1, 6, 6)
            } finally {
                g2.dispose()
            }
            super.paintComponent(g)
        }
    }
}
