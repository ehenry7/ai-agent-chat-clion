package com.aiagent.chat.tools

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.project.Project
import javax.swing.*

/**
 * Structured user question tool.
 *
 * Inspired by refact-main's ask_questions tool.
 * Presents structured questions to the user (yes_no, single_select, multi_select, free_text)
 * and blocks until answered. Returns the answer as a string for the LLM.
 *
 * The UI is presented via Swing dialogs on the EDT (Event Dispatch Thread).
 */
class AskQuestionsHandler(private val project: Project) {

    data class Question(
        val question: String,
        val type: String, // "yes_no", "single_select", "multi_select", "free_text"
        val options: List<String> = emptyList(),
        val defaultAnswer: String? = null
    )

    data class Answer(
        val question: String,
        val answer: String
    )

    /**
     * Ask a single question and block until the user answers.
     */
    fun ask(question: Question): Answer {
        val result = arrayOf<String?>(null)

        ApplicationManager.getApplication().invokeAndWait {
            result[0] = when (question.type) {
                "yes_no" -> askYesNo(question)
                "single_select" -> askSingleSelect(question)
                "multi_select" -> askMultiSelect(question)
                "free_text" -> askFreeText(question)
                else -> askFreeText(question)
            }
        }

        return Answer(question.question, result[0] ?: "(no answer)")
    }

    /**
     * Ask multiple questions in sequence.
     */
    fun askAll(questions: List<Question>): List<Answer> {
        return questions.map { ask(it) }
    }

    private fun askYesNo(q: Question): String {
        val res = Messages.showYesNoDialog(
            project,
            q.question,
            "Agent Question",
            Messages.getQuestionIcon()
        )
        return if (res == Messages.YES) "yes" else "no"
    }

    private fun askSingleSelect(q: Question): String {
        if (q.options.isEmpty()) return askFreeText(q)
        val options = q.options.toTypedArray()
        val selected = JOptionPane.showInputDialog(
            null,
            q.question,
            "Agent Question",
            JOptionPane.QUESTION_MESSAGE,
            null,
            options,
            q.defaultAnswer ?: options.firstOrNull()
        )
        return selected?.toString() ?: "(no answer)"
    }

    private fun askMultiSelect(q: Question): String {
        if (q.options.isEmpty()) return askFreeText(q)
        val checkboxes = q.options.map { JCheckBox(it) }
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(JLabel(q.question))
            checkboxes.forEach { add(it) }
        }
        val res = JOptionPane.showConfirmDialog(
            null,
            panel,
            "Agent Question (multi-select)",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.QUESTION_MESSAGE
        )
        if (res == JOptionPane.OK_OPTION) {
            return checkboxes.filter { it.isSelected }.map { it.text }.joinToString(", ")
        }
        return "(no answer)"
    }

    private fun askFreeText(q: Question): String {
        val input = JOptionPane.showInputDialog(
            null,
            q.question,
            "Agent Question",
            JOptionPane.QUESTION_MESSAGE
        )
        return input ?: "(no answer)"
    }
}
