package com.aiagent.chat.services

import com.aiagent.chat.model.ApprovalMode
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*

@State(
    name = "AIAgentChatSettings",
    storages = [Storage("ai-agent-chat.xml")]
)
@Service(Service.Level.APP)
class ChatStateService : PersistentStateComponent<ChatStateService.State> {

    class State {
        var baseUrl: String = "http://techdev.hicomputing.huawei.com:18000"
        var model: String = "GLM-5.2-1"
        var maxSteps: Int = 25
        var apiKey: String = ""
        var approvalMode: String = ApprovalMode.BALANCED.name
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    fun getApiKey(): String? = myState.apiKey.ifBlank { null }
    fun setApiKey(key: String?) { myState.apiKey = key ?: "" }
    fun isApiKeySet(): Boolean = myState.apiKey.isNotBlank()
}
