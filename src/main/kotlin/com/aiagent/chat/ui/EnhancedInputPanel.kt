package com.aiagent.chat.ui

import com.aiagent.chat.debug.DebugLog
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

    private val sendBtn = JButton("Send", AllIcons.Actions.Forward).apply {
        toolTipText = "Send message"
    }

    private val steerBtn = JButton(AllIcons.Actions.Execute).apply {
        toolTipText = "Steer Agent"
        isEnabled = false
    }

    private val stopBtn = JButton(AllIcons.Actions.Suspend).apply {
        toolTipText = "Stop Agent"
        isEnabled = false
        isVisible = false
    }

    private val modelSelector = ComboBox<String>().apply {
        toolTipText = "Select model"
        preferredSize = Dimension(150, 28)
    }

    private var promptHistory = mutableListOf<String>()
    private var historyIndex = -1

    init {
        isOpaque = false
        border = JBUI.Borders.empty(4, 8)

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
            add(steerBtn)
            add(stopBtn)
            add(sendBtn)
        }

        add(centerPanel, BorderLayout.CENTER)
        add(footerPanel, BorderLayout.SOUTH)
    }

    private fun setupListeners() {
        inputArea.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
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
        })

        sendBtn.addActionListener { handleSubmit() }

        steerBtn.addActionListener {
            val text = inputArea.text.trim()
            if (isRunning() && text.isNotEmpty()) {
                inputArea.text = ""
                onSteer(text)
            }
        }

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
                sendBtn.text = "Steer"
                steerBtn.isEnabled = true
                stopBtn.isEnabled = true
                stopBtn.isVisible = true
            } else {
                sendBtn.text = "Send"
                steerBtn.isEnabled = false
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
