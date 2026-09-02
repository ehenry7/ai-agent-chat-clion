package com.aiagent.chat.ui

import com.aiagent.chat.services.ChatStateService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.panel
import javax.swing.JPanel

class AiAgentChatConfigurable : Configurable {
    private val settings = ApplicationManager.getApplication().getService(ChatStateService::class.java)
    private var panel: JPanel? = null
    private var baseUrlField: Cell<JBTextField>? = null
    private var modelField: Cell<JBTextField>? = null
    private var maxStepsField: Cell<JBTextField>? = null
    private var apiKeyField: Cell<JBPasswordField>? = null

    override fun getDisplayName() = "AI Agent Chat"

    override fun createComponent(): JPanel {
        val mainPanel = panel {
            row { label("Base URL:") }
            row {
                baseUrlField = textField()
                baseUrlField!!.align(Align.FILL)
            }
            row { label("Model:") }
            row {
                modelField = textField()
                modelField!!.align(Align.FILL)
            }
            row { label("Max Steps:") }
            row {
                maxStepsField = textField()
                maxStepsField!!.align(Align.FILL)
            }
            row { label("API Key:") }
            row {
                apiKeyField = passwordField()
                apiKeyField!!.align(Align.FILL)
            }
        }
        panel = mainPanel
        return mainPanel
    }

    override fun isModified(): Boolean {
        return baseUrlField?.component?.text != settings.state.baseUrl ||
                modelField?.component?.text != settings.state.model ||
                maxStepsField?.component?.text != settings.state.maxSteps.toString() ||
                apiKeyField?.component?.password?.let { String(it) } != settings.getApiKey()
    }

    override fun apply() {
        settings.state.baseUrl = baseUrlField?.component?.text ?: settings.state.baseUrl
        settings.state.model = modelField?.component?.text ?: settings.state.model
        settings.state.maxSteps = maxStepsField?.component?.text?.toIntOrNull() ?: settings.state.maxSteps
        val password = apiKeyField?.component?.password
        if (password != null) {
            settings.setApiKey(String(password))
        }
    }

    override fun reset() {
        baseUrlField?.component?.text = settings.state.baseUrl
        modelField?.component?.text = settings.state.model
        maxStepsField?.component?.text = settings.state.maxSteps.toString()
        apiKeyField?.component?.text = settings.getApiKey() ?: ""
    }
}
