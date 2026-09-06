package com.aiagent.chat.ui

import com.aiagent.chat.debug.DebugLog
import com.aiagent.chat.tools.SlashCommands
import com.intellij.icons.AllIcons
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
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
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Area
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.TreeSelectionModel

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

    private val modelSelector = JButton("Select Model").apply {
        toolTipText = "Select model"
        isContentAreaFilled = true
        isBorderPainted = true
        isFocusPainted = false
        horizontalTextPosition = SwingConstants.LEADING
        margin = JBUI.insets(6, 12)
        font = font.deriveFont(Font.PLAIN, 14f)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        // No fixed width — let it size to fit the full model name
        // Only set a minimum so it doesn't collapse when text is short
        minimumSize = Dimension(120, 32)
        preferredSize = Dimension(200, 32)
    }

    /** Data for the model tree popup: provider -> models with timing. */
    data class ModelTreeEntry(
        val providerName: String,
        val providerId: String,
        val modelName: String,
        val modelId: String,
        val rawModelId: String,
        val latencyMs: Long,
        val measured: Boolean
    )

    private var modelTreeData: List<ModelTreeEntry> = emptyList()

    private var promptHistory = mutableListOf<String>()
    private var historyIndex = -1

    private var slashPopup: JWindow? = null
    private var slashList: JList<String>? = null
    private val slashListModel = DefaultListModel<String>()

    init {
        isOpaque = false
        border = JBUI.Borders.empty(4, 4, 4, 4)

        setupLayout()
        setupListeners()

        modelSelector.addActionListener {
            showModelTreePopup()
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
                if (slashPopup != null && slashPopup!!.isDisplayable) {
                    when (e.keyCode) {
                        KeyEvent.VK_ESCAPE -> {
                            e.consume()
                            closeSlashPopup()
                            return
                        }
                        KeyEvent.VK_ENTER -> {
                            e.consume()
                            val list = slashList
                            if (list != null && list.selectedIndex >= 0) {
                                val selected = list.selectedValue
                                closeSlashPopup()
                                inputArea.text = selected.substringBefore(" -")
                                inputArea.caretPosition = inputArea.text.length
                                handleSubmit()
                            } else {
                                closeSlashPopup()
                                handleSubmit()
                            }
                            return
                        }
                        KeyEvent.VK_UP -> {
                            e.consume()
                            val list = slashList
                            if (list != null && list.model.size > 0) {
                                val idx = list.selectedIndex
                                if (idx > 0) list.selectedIndex = idx - 1
                                else list.selectedIndex = list.model.size - 1
                                list.ensureIndexIsVisible(list.selectedIndex)
                            }
                            return
                        }
                        KeyEvent.VK_DOWN -> {
                            e.consume()
                            val list = slashList
                            if (list != null && list.model.size > 0) {
                                val idx = list.selectedIndex
                                if (idx < list.model.size - 1) list.selectedIndex = idx + 1
                                else list.selectedIndex = 0
                                list.ensureIndexIsVisible(list.selectedIndex)
                            }
                            return
                        }
                        KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT -> {
                            // Let cursor movement propagate; keyReleased will update/close popup
                        }
                    }
                }

                when {
                    e.keyCode == KeyEvent.VK_ENTER && e.isControlDown -> {
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
                } else {
                    closeSlashPopup()
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

        // Slash commands are always processed immediately, even when agent is running
        if (text.startsWith("/") && !text.contains(" ")) {
            inputArea.text = ""
            DebugLog.info("EnhancedInputPanel", "Processing slash command immediately: $text")
            onSubmit(text, fileTags.toList())
            clearTags()
            return
        }

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

    private fun closeSlashPopup() {
        slashPopup?.dispose()
        slashPopup = null
        slashList = null
    }

    private fun showOrUpdateSlashPopup(typedText: String) {
        val query = typedText.removePrefix("/").lowercase()
        val allCommands = SlashCommands.BUILT_IN.values.map { cmd ->
            "/${cmd.name} - ${cmd.description}"
        }
        val filtered = if (query.isEmpty()) {
            allCommands
        } else {
            allCommands.filter { it.substringAfter("/").startsWith(query) }
        }

        if (filtered.isEmpty()) {
            closeSlashPopup()
            return
        }

        // Update the list model in-place if popup is already visible
        if (slashPopup != null && slashPopup!!.isDisplayable && slashList != null) {
            // Preserve current selection so arrow-key navigation isn't reset
            val prevIndex = slashList!!.selectedIndex
            slashListModel.clear()
            filtered.forEach { slashListModel.addElement(it) }
            // Clamp to valid range (list may have shrunk due to filtering)
            val newIndex = if (prevIndex in 0 until filtered.size) prevIndex else 0
            slashList!!.selectedIndex = newIndex
            slashList!!.ensureIndexIsVisible(newIndex)
            return
        }

        // Create new popup
        slashListModel.clear()
        filtered.forEach { slashListModel.addElement(it) }

        val list = JList(slashListModel).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            selectedIndex = 0
            cellRenderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
                ): java.awt.Component {
                    super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                    border = JBUI.Borders.empty(4, 8)
                    if (isSelected) {
                        background = ThemeUtils.ACCENT
                        foreground = JBColor.WHITE
                    }
                    return this
                }
            }
        }
        slashList = list

        val scrollPane = JBScrollPane(list).apply {
            border = JBUI.Borders.empty()
            preferredSize = Dimension(320, minOf(filtered.size * 28 + 2, 200))
        }

        val popup = JWindow(SwingUtilities.getWindowAncestor(inputArea)).apply {
            contentPane.add(scrollPane, BorderLayout.CENTER)
            setFocusableWindowState(false)
            isAlwaysOnTop = true
            type = java.awt.Window.Type.POPUP
        }

        // Mouse click selection
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val idx = list.locationToIndex(e.point)
                if (idx >= 0) {
                    list.selectedIndex = idx
                    val selected = list.selectedValue
                    closeSlashPopup()
                    inputArea.text = selected.substringBefore(" -")
                    inputArea.caretPosition = inputArea.text.length
                    inputArea.requestFocusInWindow()
                    handleSubmit()
                }
            }
        })

        // Position popup below the text area
        popup.pack()
        val textLoc = inputArea.locationOnScreen
        val popupX = textLoc.x
        val popupY = textLoc.y + inputArea.height
        popup.setLocation(popupX, popupY)
        popup.isVisible = true
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
        // Legacy flat-list API: convert to tree entries without timing
        val entries = models.map { name ->
            val parts = name.split("/", limit = 2)
            if (parts.size == 2) {
                ModelTreeEntry(parts[0], "", parts[1], name, parts[1], 0, false)
            } else {
                ModelTreeEntry("Default", "", name, name, name, 0, false)
            }
        }
        updateModelTree(entries)
    }

    fun updateModelTree(entries: List<ModelTreeEntry>) {
        SwingUtilities.invokeLater {
            modelTreeData = entries
            // Update button text to show full current model name with dropdown arrow
            val current = currentModel()
            val match = entries.find { it.rawModelId == current }
            val displayName = match?.let { "${it.providerName}/${it.modelName}" } ?: current
            modelSelector.text = "$displayName  \u25BE"
            // Resize button to fit the full text
            val fm = modelSelector.getFontMetrics(modelSelector.font)
            val textWidth = fm.stringWidth(modelSelector.text)
            val newWidth = (textWidth + 40).coerceAtLeast(120)  // 40px for margins + padding
            modelSelector.preferredSize = Dimension(newWidth, 32)
            modelSelector.revalidate()
            modelSelector.repaint()
        }
    }

    /**
     * Public method to programmatically trigger the model tree popup.
     * Used by the More dropdown in ConversationTabPanel.
     */
    fun triggerModelTreePopup() {
        SwingUtilities.invokeLater { showModelTreePopup() }
    }

    private fun showModelTreePopup() {
        if (modelTreeData.isEmpty()) return

        // Group by provider
        val byProvider = modelTreeData.groupBy { it.providerName }

        // Build tree model
        val root = DefaultMutableTreeNode("Models")
        for ((providerName, models) in byProvider) {
            val providerNode = DefaultMutableTreeNode(providerName)
            for (m in models) {
                providerNode.add(DefaultMutableTreeNode(m))
            }
            root.add(providerNode)
        }

        // Create popup dialog first so selectModel can reference it
        val popup = JDialog().apply {
            title = "Select Model"
            isModal = false
            isAlwaysOnTop = true
            defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE
        }

        // Define selectModel before tree listeners
        fun selectModel(entry: ModelTreeEntry) {
            // Use rawModelId (without provider prefix) for the API call
            val rawId = entry.rawModelId
            if (rawId != currentModel()) {
                DebugLog.info("EnhancedInputPanel", "Model tree selected: provider=${entry.providerName}, rawModelId='$rawId'")
                onModelChange(rawId)
            }
            modelSelector.text = "${entry.providerName}/${entry.modelName}"
            popup.dispose()
        }

        val tree = JTree(root).apply {
            isRootVisible = false
            showsRootHandles = true
            selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
            rowHeight = 24

            // Custom renderer: show model name + timing for leaves, bold for providers
            setCellRenderer(object : DefaultTreeCellRenderer() {
                override fun getTreeCellRendererComponent(
                    tree: JTree, value: Any, sel: Boolean, expanded: Boolean,
                    leaf: Boolean, row: Int, hasFocus: Boolean
                ): java.awt.Component {
                    super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)
                    val userObj = (value as? DefaultMutableTreeNode)?.userObject
                    when (userObj) {
                        is ModelTreeEntry -> {
                            val latencyStr = if (userObj.measured && userObj.latencyMs > 0) {
                                if (userObj.latencyMs < 1000) "${userObj.latencyMs}ms" else "%.2fs".format(userObj.latencyMs / 1000.0)
                            } else {
                                "not measured"
                            }
                            text = "${userObj.modelName}  ($latencyStr)"
                            icon = AllIcons.General.Balloon
                            font = font.deriveFont(Font.PLAIN, 12f)
                        }
                        is String -> {
                            text = userObj
                            icon = AllIcons.Nodes.Folder
                            font = font.deriveFont(Font.BOLD, 12f)
                        }
                    }
                    return this
                }
            })

            // Click selects a leaf
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val path = getPathForLocation(e.x, e.y)
                    if (path != null) {
                        val node = path.lastPathComponent as? DefaultMutableTreeNode
                        val entry = node?.userObject as? ModelTreeEntry
                        if (entry != null) {
                            selectModel(entry)
                        }
                    }
                }
            })

            // Enter key selects a leaf
            addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    if (e.keyCode == KeyEvent.VK_ENTER) {
                        val path = selectionPath
                        if (path != null) {
                            val node = path.lastPathComponent as? DefaultMutableTreeNode
                            val entry = node?.userObject as? ModelTreeEntry
                            if (entry != null) {
                                selectModel(entry)
                            }
                        }
                    }
                }
            })
        }

        // Expand all provider nodes by default
        for (i in 0 until tree.rowCount) {
            tree.expandRow(i)
        }

        // Wrap tree in scroll pane with adequate size
        val scrollPane = JBScrollPane(tree).apply {
            preferredSize = Dimension(350, 400)
            border = JBUI.Borders.empty()
        }

        // Status label at bottom
        val statusLabel = JBLabel("Click a model to select it").apply {
            border = JBUI.Borders.empty(4, 8)
            foreground = ThemeUtils.SECONDARY_TEXT
        }

        popup.contentPane.add(scrollPane, BorderLayout.CENTER)
        popup.contentPane.add(statusLabel, BorderLayout.SOUTH)
        popup.pack()

        // Position below the model selector button, clamped to screen bounds
        val btnLoc = modelSelector.locationOnScreen
        val popupWidth = popup.width
        val popupHeight = popup.height
        val screenBounds = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
            .defaultScreenDevice.defaultConfiguration.bounds
        val screenInsets = java.awt.Toolkit.getDefaultToolkit()
            .getScreenInsets(java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration)

        // Calculate X: try to align with button, but clamp to screen
        var popupX = btnLoc.x
        if (popupX + popupWidth > screenBounds.x + screenBounds.width - screenInsets.right) {
            popupX = screenBounds.x + screenBounds.width - screenInsets.right - popupWidth
        }
        if (popupX < screenInsets.left) {
            popupX = screenInsets.left
        }

        // Calculate Y: try below button, but if it would go off-screen, try above
        var popupY = btnLoc.y + modelSelector.height
        if (popupY + popupHeight > screenBounds.y + screenBounds.height - screenInsets.bottom) {
            // Try placing above the button
            popupY = btnLoc.y - popupHeight
            if (popupY < screenInsets.top) {
                // Still doesn't fit — clamp to top of usable area
                popupY = screenInsets.top
            }
        }

        popup.setLocation(popupX, popupY)

        // Close on Escape
        val escapeStroke = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0)
        popup.rootPane.registerKeyboardAction(
            { popup.dispose() },
            escapeStroke,
            JComponent.WHEN_IN_FOCUSED_WINDOW
        )

        // Close when focus is lost
        popup.addWindowFocusListener(object : java.awt.event.WindowAdapter() {
            override fun windowLostFocus(e: java.awt.event.WindowEvent) {
                popup.dispose()
            }
        })

        popup.isVisible = true
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
                g2.color = JBColor.namedColor("TextField.background", JBColor(0xE8EAF0, 0x333740))
                g2.fillRoundRect(0, 0, width - 1, height - 1, 6, 6)
            } finally {
                g2.dispose()
            }
            super.paintComponent(g)
        }
    }
}
