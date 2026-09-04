package com.aiagent.chat.agent

import com.aiagent.chat.debug.DebugLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Priority command queue for the agent loop.
 *
 * Inspired by refact-main's VecDeque<CommandRequest> with priority insertion.
 *
 * Features:
 *  - Priority insertion: Abort/Steer/ToolDecision jump to front
 *  - Abort flag: AtomicBoolean checked between SSE lines and tool calls
 *  - Active job tracking: cancel the active coroutine when Abort is received
 *  - Pending tool decision: CompletableDeferred that suspends tool execution until user responds
 *  - Queue visibility: listeners can observe queue changes
 */
class CommandQueue {
    private val queue = ConcurrentLinkedDeque<AgentCommand>()
    private val aborted = AtomicBoolean(false)
    private val activeJob = AtomicReference<Job?>(null)

    // Pending tool approval: the agent loop suspends on this deferred until the user decides
    private val pendingToolDecision = AtomicReference<CompletableDeferred<AgentCommand.ToolDecision>?>(null)

    // Queue change listeners for UI visibility
    private val queueListeners = java.util.concurrent.CopyOnWriteArrayList<(List<AgentCommand>) -> Unit>()

    fun addListener(listener: (List<AgentCommand>) -> Unit) {
        queueListeners.add(listener)
    }

    fun removeListener(listener: (List<AgentCommand>) -> Unit) {
        queueListeners.remove(listener)
    }

    private fun notifyListeners() {
        val snapshot = queue.toList()
        queueListeners.forEach { it(snapshot) }
    }

    /**
     * Enqueue a command. High-priority commands (Abort, Steer, ToolDecision)
     * are inserted at the front; normal commands at the back.
     */
    fun enqueue(command: AgentCommand) {
        DebugLog.info("CommandQueue", "Enqueue: ${command::class.simpleName} (priority=${command.priority})")

        if (command is AgentCommand.Abort) {
            // Set abort flag immediately
            aborted.set(true)
            // Cancel active job if any
            activeJob.get()?.cancel()
            // Clear pending tool decision
            pendingToolDecision.get()?.complete(
                AgentCommand.ToolDecision(deniedToolCallIds = mapOf("" to "Aborted by user"))
            )
            // Clear the queue — abort supersedes everything
            queue.clear()
            queue.add(command)
        } else if (command.priority <= 5) {
            // High priority: add to front
            queue.addFirst(command)
        } else {
            // Normal priority: add to back
            queue.addLast(command)
        }

        // If this is a ToolDecision, complete the pending deferred
        if (command is AgentCommand.ToolDecision) {
            pendingToolDecision.get()?.let { deferred ->
                if (deferred.isActive) {
                    deferred.complete(command)
                }
            }
        }

        notifyListeners()
    }

    /**
     * Dequeue the next command. Returns null if queue is empty.
     */
    fun dequeue(): AgentCommand? {
        val cmd = queue.poll()
        if (cmd != null) notifyListeners()
        return cmd
    }

    /**
     * Peek at the next command without removing it.
     */
    fun peek(): AgentCommand? = queue.peek()

    /**
     * Check if the abort flag has been set.
     * The agent loop should call this between SSE lines and before tool calls.
     */
    fun isAborted(): Boolean = aborted.get()

    /**
     * Reset the abort flag. Called when starting a new agent loop.
     */
    fun resetAbort() {
        aborted.set(false)
    }

    /**
     * Register the active coroutine job so Abort can cancel it.
     */
    fun setActiveJob(job: Job) {
        activeJob.set(job)
    }

    fun clearActiveJob() {
        activeJob.set(null)
    }

    /**
     * Create a CompletableDeferred for tool approval.
     * The agent loop will suspend on this until the user provides a ToolDecision.
     */
    fun createToolDecisionPending(): CompletableDeferred<AgentCommand.ToolDecision> {
        val deferred = CompletableDeferred<AgentCommand.ToolDecision>()
        pendingToolDecision.set(deferred)
        return deferred
    }

    /**
     * Clear the pending tool decision (after it has been resolved).
     */
    fun clearToolDecisionPending() {
        pendingToolDecision.set(null)
    }

    /**
     * Check if there is a pending tool decision awaiting user input.
     */
    fun hasPendingToolDecision(): Boolean {
        val deferred = pendingToolDecision.get()
        return deferred != null && !deferred.isCompleted
    }

    /**
     * Get the current queue size.
     */
    fun size(): Int = queue.size

    /**
     * Check if the queue is empty.
     */
    fun isEmpty(): Boolean = queue.isEmpty()

    /**
     * Clear all commands from the queue.
     */
    fun clear() {
        queue.clear()
        aborted.set(false)
        pendingToolDecision.set(null)
        activeJob.set(null)
        notifyListeners()
    }

    /**
     * Get a snapshot of the current queue contents (for UI display).
     */
    fun snapshot(): List<AgentCommand> = queue.toList()
}
