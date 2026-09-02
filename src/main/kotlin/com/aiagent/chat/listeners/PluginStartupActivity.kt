package com.aiagent.chat.listeners

import com.aiagent.chat.services.ChatStateService
import com.aiagent.chat.ui.AiAgentChatConfigurable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.intellij.openapi.wm.ToolWindowManager

class PluginStartupActivity : StartupActivity.DumbAware {
    override fun runActivity(project: Project) {
        val settings = ApplicationManager.getApplication().service<ChatStateService>()
        if (!settings.isApiKeySet()) {
            ApplicationManager.getApplication().invokeLater {
                ToolWindowManager.getInstance(project).getToolWindow("AI Agent Chat")?.show()
            }
        }
    }
}
