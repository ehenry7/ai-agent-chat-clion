package com.aiagent.chat.agent

import com.aiagent.chat.debug.DebugLog
import java.util.concurrent.atomic.AtomicReference

/**
 * Explicit session state machine for the agent loop.
 *
 * States:
 *  IDLE             - No active conversation, waiting for user input
 *  GENERATING       - Waiting for LLM response (API call in flight)
 *  EXECUTING_TOOLS  - Running one or more tool calls
 *  PAUSED           - Waiting for user decision (e.g. tool approval)
 *  COMPLETED        - Agent finished its task
 *  ERROR            - An unrecoverable error occurred
 *
 * Inspired by refact-main's 7-state session state machine.
 */
enum class AgentSessionState {
    IDLE,
    GENERATING,
    EXECUTING_TOOLS,
    PAUSED,
    COMPLETED,
    ERROR
}

/**
 * Reason for a state transition, used for logging and UI feedback.
 */
data class StateTransition(
    val from: AgentSessionState,
    val to: AgentSessionState,
    val reason: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Thread-safe session state machine.
 * Emits transition events to registered listeners.
 */
class SessionStateMachine {
    private val _state = AtomicReference(AgentSessionState.IDLE)
    private val _pausedReason = AtomicReference<String?>(null)

    val state: AgentSessionState get() = _state.get()
    val pausedReason: String? get() = _pausedReason.get()

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(StateTransition) -> Unit>()

    fun addListener(listener: (StateTransition) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (StateTransition) -> Unit) {
        listeners.remove(listener)
    }

    /**
     * Transition to a new state. If the transition is valid, emits an event to all listeners.
     * Clears pausedReason when leaving PAUSED state.
     */
    @Synchronized
    fun transitionTo(newState: AgentSessionState, reason: String = ""): StateTransition? {
        val oldState = _state.get()
        if (oldState == newState) return null

        // Validate transition
        if (!isValidTransition(oldState, newState)) {
            DebugLog.warn("SessionStateMachine", "Invalid transition: $oldState -> $newState (reason: $reason)")
            return null
        }

        _state.set(newState)
        if (newState != AgentSessionState.PAUSED) {
            _pausedReason.set(null)
        }

        val transition = StateTransition(oldState, newState, reason)
        DebugLog.info("SessionStateMachine", "State transition: $oldState -> $newState ($reason)")
        listeners.forEach { it(transition) }
        return transition
    }

    /**
     * Pause the state machine with a reason (e.g. "Tool approval required for write_file").
     */
    fun pause(reason: String) {
        _pausedReason.set(reason)
        transitionTo(AgentSessionState.PAUSED, reason)
    }

    /**
     * Resume from PAUSED state back to EXECUTING_TOOLS.
     */
    fun resume(reason: String = "User decision received") {
        transitionTo(AgentSessionState.EXECUTING_TOOLS, reason)
    }

    fun isIdle(): Boolean = _state.get() == AgentSessionState.IDLE
    fun isRunning(): Boolean = _state.get() in listOf(
        AgentSessionState.GENERATING,
        AgentSessionState.EXECUTING_TOOLS,
        AgentSessionState.PAUSED
    )
    fun isPaused(): Boolean = _state.get() == AgentSessionState.PAUSED

    /**
     * Reset to IDLE. Used when a new conversation starts or after cleanup.
     */
    fun reset() {
        _pausedReason.set(null)
        _state.set(AgentSessionState.IDLE)
    }

    companion object {
        /**
         * Define valid state transitions to prevent illegal jumps.
         */
        fun isValidTransition(from: AgentSessionState, to: AgentSessionState): Boolean {
            if (to == AgentSessionState.ERROR) return true // Error can happen from any state
            if (to == AgentSessionState.IDLE) return true  // Reset can happen from any state
            return when (from) {
                AgentSessionState.IDLE -> to in listOf(AgentSessionState.GENERATING)
                AgentSessionState.GENERATING -> to in listOf(
                    AgentSessionState.EXECUTING_TOOLS,
                    AgentSessionState.COMPLETED,
                    AgentSessionState.PAUSED
                )
                AgentSessionState.EXECUTING_TOOLS -> to in listOf(
                    AgentSessionState.GENERATING,
                    AgentSessionState.PAUSED,
                    AgentSessionState.COMPLETED
                )
                AgentSessionState.PAUSED -> to in listOf(
                    AgentSessionState.EXECUTING_TOOLS,
                    AgentSessionState.GENERATING,
                    AgentSessionState.COMPLETED
                )
                AgentSessionState.COMPLETED -> to in listOf(AgentSessionState.IDLE)
                AgentSessionState.ERROR -> to in listOf(AgentSessionState.IDLE)
            }
        }
    }
}
