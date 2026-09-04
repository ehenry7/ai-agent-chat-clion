package com.aiagent.chat.agent

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for SessionStateMachine.
 * Verifies state transitions, validation, pause/resume, and listener notifications.
 */
class SessionStateMachineTest {

    @Test
    fun `initial state is IDLE`() {
        val sm = SessionStateMachine()
        assertEquals(AgentSessionState.IDLE, sm.state)
    }

    @Test
    fun `transition from IDLE to GENERATING succeeds`() {
        val sm = SessionStateMachine()
        val transition = sm.transitionTo(AgentSessionState.GENERATING, "Starting")
        assertNotNull(transition)
        assertEquals(AgentSessionState.IDLE, transition!!.from)
        assertEquals(AgentSessionState.GENERATING, transition.to)
        assertEquals("Starting", transition.reason)
        assertEquals(AgentSessionState.GENERATING, sm.state)
    }

    @Test
    fun `transition to same state returns null`() {
        val sm = SessionStateMachine()
        val transition = sm.transitionTo(AgentSessionState.IDLE, "No-op")
        assertNull(transition)
        assertEquals(AgentSessionState.IDLE, sm.state)
    }

    @Test
    fun `invalid transition from IDLE to EXECUTING_TOOLS returns null`() {
        val sm = SessionStateMachine()
        val transition = sm.transitionTo(AgentSessionState.EXECUTING_TOOLS, "Skip")
        assertNull(transition)
        assertEquals(AgentSessionState.IDLE, sm.state)
    }

    @Test
    fun `transition from GENERATING to EXECUTING_TOOLS succeeds`() {
        val sm = SessionStateMachine()
        sm.transitionTo(AgentSessionState.GENERATING, "Start")
        val transition = sm.transitionTo(AgentSessionState.EXECUTING_TOOLS, "Tools ready")
        assertNotNull(transition)
        assertEquals(AgentSessionState.EXECUTING_TOOLS, sm.state)
    }

    @Test
    fun `transition from GENERATING to COMPLETED succeeds`() {
        val sm = SessionStateMachine()
        sm.transitionTo(AgentSessionState.GENERATING, "Start")
        sm.transitionTo(AgentSessionState.COMPLETED, "Done")
        assertEquals(AgentSessionState.COMPLETED, sm.state)
    }

    @Test
    fun `transition from GENERATING to PAUSED succeeds`() {
        val sm = SessionStateMachine()
        sm.transitionTo(AgentSessionState.GENERATING, "Start")
        sm.transitionTo(AgentSessionState.PAUSED, "Approval needed")
        assertEquals(AgentSessionState.PAUSED, sm.state)
    }

    @Test
    fun `transition from EXECUTING_TOOLS to GENERATING succeeds`() {
        val sm = SessionStateMachine()
        sm.transitionTo(AgentSessionState.GENERATING, "Start")
        sm.transitionTo(AgentSessionState.EXECUTING_TOOLS, "Tools")
        sm.transitionTo(AgentSessionState.GENERATING, "Next step")
        assertEquals(AgentSessionState.GENERATING, sm.state)
    }

    @Test
    fun `transition from EXECUTING_TOOLS to PAUSED succeeds`() {
        val sm = SessionStateMachine()
        sm.transitionTo(AgentSessionState.GENERATING, "Start")
        sm.transitionTo(AgentSessionState.EXECUTING_TOOLS, "Tools")
        sm.transitionTo(AgentSessionState.PAUSED, "Approval")
        assertEquals(AgentSessionState.PAUSED, sm.state)
    }

    @Test
    fun `transition from PAUSED to EXECUTING_TOOLS succeeds`() {
        val sm = SessionStateMachine()
        sm.transitionTo(AgentSessionState.GENERATING, "Start")
        sm.transitionTo(AgentSessionState.EXECUTING_TOOLS, "Tools")
        sm.transitionTo(AgentSessionState.PAUSED, "Approval")
        sm.transitionTo(AgentSessionState.EXECUTING_TOOLS, "Approved")
        assertEquals(AgentSessionState.EXECUTING_TOOLS, sm.state)
    }

    @Test
    fun `ERROR can be reached from any state`() {
        val sm = SessionStateMachine()
        sm.transitionTo(AgentSessionState.GENERATING, "Start")
        sm.transitionTo(AgentSessionState.ERROR, "Crash")
        assertEquals(AgentSessionState.ERROR, sm.state)
    }

    @Test
    fun `IDLE can be reached from any state`() {
        val sm = SessionStateMachine()
        sm.transitionTo(AgentSessionState.GENERATING, "Start")
        sm.transitionTo(AgentSessionState.ERROR, "Crash")
        sm.transitionTo(AgentSessionState.IDLE, "Reset")
        assertEquals(AgentSessionState.IDLE, sm.state)
    }

    // --- pause / resume ---

    @Test
    fun `pause sets state to PAUSED and stores reason`() {
        val sm = SessionStateMachine()
        sm.transitionTo(AgentSessionState.GENERATING, "Start")
        sm.transitionTo(AgentSessionState.EXECUTING_TOOLS, "Tools")
        sm.pause("Tool approval required: write_file")
        assertEquals(AgentSessionState.PAUSED, sm.state)
        assertEquals("Tool approval required: write_file", sm.pausedReason)
    }

    @Test
    fun `resume transitions back to EXECUTING_TOOLS and clears reason`() {
        val sm = SessionStateMachine()
        sm.transitionTo(AgentSessionState.GENERATING, "Start")
        sm.transitionTo(AgentSessionState.EXECUTING_TOOLS, "Tools")
        sm.pause("Approval needed")
        sm.resume("User approved")
        assertEquals(AgentSessionState.EXECUTING_TOOLS, sm.state)
        assertNull(sm.pausedReason)
    }

    // --- isIdle / isRunning / isPaused ---

    @Test
    fun `isIdle returns true for IDLE state`() {
        val sm = SessionStateMachine()
        assertTrue(sm.isIdle())
        assertFalse(sm.isRunning())
        assertFalse(sm.isPaused())
    }

    @Test
    fun `isRunning returns true for GENERATING EXECUTING_TOOLS and PAUSED`() {
        val sm = SessionStateMachine()
        sm.transitionTo(AgentSessionState.GENERATING, "Start")
        assertTrue(sm.isRunning())

        sm.transitionTo(AgentSessionState.EXECUTING_TOOLS, "Tools")
        assertTrue(sm.isRunning())

        sm.transitionTo(AgentSessionState.PAUSED, "Approval")
        assertTrue(sm.isRunning())
        assertTrue(sm.isPaused())
    }

    @Test
    fun `isRunning returns false for COMPLETED and ERROR`() {
        val sm = SessionStateMachine()
        sm.transitionTo(AgentSessionState.GENERATING, "Start")
        sm.transitionTo(AgentSessionState.COMPLETED, "Done")
        assertFalse(sm.isRunning())

        sm.reset()
        sm.transitionTo(AgentSessionState.GENERATING, "Start")
        sm.transitionTo(AgentSessionState.ERROR, "Fail")
        assertFalse(sm.isRunning())
    }

    // --- reset ---

    @Test
    fun `reset returns to IDLE and clears pausedReason`() {
        val sm = SessionStateMachine()
        sm.transitionTo(AgentSessionState.GENERATING, "Start")
        sm.transitionTo(AgentSessionState.EXECUTING_TOOLS, "Tools")
        sm.pause("Approval")
        sm.reset()
        assertEquals(AgentSessionState.IDLE, sm.state)
        assertNull(sm.pausedReason)
    }

    // --- listeners ---

    @Test
    fun `listener receives transition events`() {
        val sm = SessionStateMachine()
        val transitions = mutableListOf<StateTransition>()
        sm.addListener { transitions.add(it) }

        sm.transitionTo(AgentSessionState.GENERATING, "Start")
        sm.transitionTo(AgentSessionState.EXECUTING_TOOLS, "Tools")

        assertEquals(2, transitions.size)
        assertEquals(AgentSessionState.IDLE, transitions[0].from)
        assertEquals(AgentSessionState.GENERATING, transitions[0].to)
        assertEquals(AgentSessionState.GENERATING, transitions[1].from)
        assertEquals(AgentSessionState.EXECUTING_TOOLS, transitions[1].to)
    }

    @Test
    fun `listener does not receive events for no-op transitions`() {
        val sm = SessionStateMachine()
        val transitions = mutableListOf<StateTransition>()
        sm.addListener { transitions.add(it) }

        sm.transitionTo(AgentSessionState.IDLE, "No-op")
        assertEquals(0, transitions.size)
    }

    @Test
    fun `removed listener does not receive events`() {
        val sm = SessionStateMachine()
        val transitions = mutableListOf<StateTransition>()
        val listener: (StateTransition) -> Unit = { transitions.add(it) }
        sm.addListener(listener)

        sm.transitionTo(AgentSessionState.GENERATING, "Start")
        assertEquals(1, transitions.size)

        sm.removeListener(listener)
        sm.transitionTo(AgentSessionState.EXECUTING_TOOLS, "Tools")
        assertEquals(1, transitions.size)
    }

    // --- isValidTransition ---

    @Test
    fun `isValidTransition allows ERROR from any state`() {
        for (from in AgentSessionState.entries) {
            assertTrue("ERROR should be reachable from $from",
                SessionStateMachine.isValidTransition(from, AgentSessionState.ERROR))
        }
    }

    @Test
    fun `isValidTransition allows IDLE from any state`() {
        for (from in AgentSessionState.entries) {
            assertTrue("IDLE should be reachable from $from",
                SessionStateMachine.isValidTransition(from, AgentSessionState.IDLE))
        }
    }

    @Test
    fun `isValidTransition rejects IDLE to EXECUTING_TOOLS`() {
        assertFalse(SessionStateMachine.isValidTransition(AgentSessionState.IDLE, AgentSessionState.EXECUTING_TOOLS))
    }

    @Test
    fun `isValidTransition rejects COMPLETED to GENERATING`() {
        assertFalse(SessionStateMachine.isValidTransition(AgentSessionState.COMPLETED, AgentSessionState.GENERATING))
    }
}
