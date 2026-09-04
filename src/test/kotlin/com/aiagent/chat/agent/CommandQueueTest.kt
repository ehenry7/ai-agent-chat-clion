package com.aiagent.chat.agent

import com.aiagent.chat.model.ChatMessage
import com.aiagent.chat.model.MessageRole
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Test

private fun testMsg() = ChatMessage(MessageRole.USER, "test")

/**
 * Unit tests for CommandQueue.
 * Verifies enqueue/dequeue ordering, abort flag, and tool decision lifecycle.
 */
class CommandQueueTest {

    @Test
    fun `new queue is empty`() {
        val q = CommandQueue()
        assertTrue(q.isEmpty())
        assertEquals(0, q.size())
        assertNull(q.peek())
        assertNull(q.dequeue())
    }

    @Test
    fun `enqueue and dequeue normal priority command`() {
        val q = CommandQueue()
        val cmd = AgentCommand.Send(testMsg())
        q.enqueue(cmd)
        assertEquals(1, q.size())
        val dequeued = q.dequeue()
        assertNotNull(dequeued)
        assertSame(cmd, dequeued)
        assertTrue(q.isEmpty())
    }

    @Test
    fun `high priority command is dequeued before normal priority`() {
        val q = CommandQueue()
        q.enqueue(AgentCommand.Send(testMsg()))  // priority 10
        q.enqueue(AgentCommand.Steer("urgent"))       // priority 1
        q.enqueue(AgentCommand.Send(testMsg()))  // priority 10

        val first = q.dequeue()
        assertTrue("First dequeued should be Steer (high priority)", first is AgentCommand.Steer)
        assertEquals("urgent", (first as AgentCommand.Steer).text)
    }

    @Test
    fun `peek does not remove from queue`() {
        val q = CommandQueue()
        q.enqueue(AgentCommand.Steer("test"))
        assertEquals(1, q.size())
        val peeked = q.peek()
        assertNotNull(peeked)
        assertTrue(peeked is AgentCommand.Steer)
        assertEquals(1, q.size())
    }

    // --- Abort ---

    @Test
    fun `abort sets isAborted flag`() {
        val q = CommandQueue()
        assertFalse(q.isAborted())
        q.enqueue(AgentCommand.Abort)
        assertTrue(q.isAborted())
    }

    @Test
    fun `resetAbort clears the abort flag`() {
        val q = CommandQueue()
        q.enqueue(AgentCommand.Abort)
        assertTrue(q.isAborted())
        q.resetAbort()
        assertFalse(q.isAborted())
    }

    @Test
    fun `abort clears the queue and leaves only itself`() {
        val q = CommandQueue()
        q.enqueue(AgentCommand.Send(testMsg()))
        q.enqueue(AgentCommand.Steer("test"))
        q.enqueue(AgentCommand.Abort)
        assertEquals(1, q.size())
        assertTrue(q.peek() is AgentCommand.Abort)
    }

    // --- Tool decision ---

    @Test
    fun `createToolDecisionPending returns incomplete deferred`() {
        val q = CommandQueue()
        val deferred = q.createToolDecisionPending()
        assertFalse(deferred.isCompleted)
        assertTrue(q.hasPendingToolDecision())
    }

    @Test
    fun `enqueue ToolDecision completes pending deferred`() {
        val q = CommandQueue()
        val deferred = q.createToolDecisionPending()
        assertTrue(q.hasPendingToolDecision())

        val decision = AgentCommand.ToolDecision(
            acceptedToolCallIds = setOf("call_1"),
            deniedToolCallIds = emptyMap()
        )
        q.enqueue(decision)

        assertTrue(deferred.isCompleted)
        assertFalse(q.hasPendingToolDecision())
    }

    @Test
    fun `clearToolDecisionPending clears the pending state`() {
        val q = CommandQueue()
        q.createToolDecisionPending()
        assertTrue(q.hasPendingToolDecision())
        q.clearToolDecisionPending()
        assertFalse(q.hasPendingToolDecision())
    }

    @Test
    fun `abort completes pending tool decision with denial`() {
        val q = CommandQueue()
        val deferred = q.createToolDecisionPending()
        q.enqueue(AgentCommand.Abort)
        assertTrue(deferred.isCompleted)
        val result = deferred.getCompleted()
        // Abort should complete with a denial
        assertTrue(result.deniedToolCallIds.isNotEmpty())
    }

    // --- clear ---

    @Test
    fun `clear empties the queue and resets state`() {
        val q = CommandQueue()
        q.enqueue(AgentCommand.Send(testMsg()))
        q.enqueue(AgentCommand.Steer("test"))
        q.enqueue(AgentCommand.Abort)
        q.clear()
        assertTrue(q.isEmpty())
        assertFalse(q.isAborted())
        assertFalse(q.hasPendingToolDecision())
    }

    // --- snapshot ---

    @Test
    fun `snapshot returns current queue contents`() {
        val q = CommandQueue()
        q.enqueue(AgentCommand.Send(testMsg()))
        q.enqueue(AgentCommand.Steer("test"))
        val snap = q.snapshot()
        assertEquals(2, snap.size)
    }

    // --- listeners ---

    @Test
    fun `listener is notified on enqueue`() {
        val q = CommandQueue()
        val snapshots = mutableListOf<List<AgentCommand>>()
        q.addListener { snapshots.add(it) }

        q.enqueue(AgentCommand.Send(testMsg()))
        assertEquals(1, snapshots.size)
        assertEquals(1, snapshots[0].size)

        q.enqueue(AgentCommand.Steer("test"))
        assertEquals(2, snapshots.size)
    }

    @Test
    fun `listener is notified on dequeue`() {
        val q = CommandQueue()
        q.enqueue(AgentCommand.Send(testMsg()))
        val snapshots = mutableListOf<List<AgentCommand>>()
        q.addListener { snapshots.add(it) }

        q.dequeue()
        assertEquals(1, snapshots.size)
        assertEquals(0, snapshots[0].size)
    }
}
