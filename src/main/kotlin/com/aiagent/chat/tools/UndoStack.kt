package com.aiagent.chat.tools

/**
 * In-memory undo stack for file edits.
 *
 * Before any file-writing tool (write_file, edit_file, update_textdoc_by_lines, apply_diff)
 * modifies a file, the previous content is pushed onto this stack.
 * The undo_textdoc tool pops the stack and restores the previous content.
 *
 * Inspired by refact-main's undo_textdoc tool with per-file undo history.
 */
class UndoStack {
    data class Snapshot(val path: String, val content: String)

    private val stack = mutableListOf<Snapshot>()

    /**
     * Push a snapshot of file content before it gets modified.
     * If the stack exceeds MAX_STACK_SIZE, the oldest entry is dropped.
     */
    fun push(path: String, content: String) {
        stack.add(Snapshot(path, content))
        if (stack.size > MAX_STACK_SIZE) {
            stack.removeAt(0)
        }
    }

    /** Pop and return the most recent snapshot, or null if the stack is empty. */
    fun pop(): Snapshot? = if (stack.isNotEmpty()) stack.removeLast() else null

    /** Peek at the most recent snapshot without removing it. */
    fun peek(): Snapshot? = stack.lastOrNull()

    /** Pop the most recent snapshot for a specific file path, or null if none exists. */
    fun popForPath(path: String): Snapshot? {
        val idx = stack.indexOfLast { it.path == path }
        return if (idx >= 0) stack.removeAt(idx) else null
    }

    fun isEmpty(): Boolean = stack.isEmpty()
    fun size(): Int = stack.size
    fun clear() = stack.clear()

    companion object {
        const val MAX_STACK_SIZE = 50
    }
}
