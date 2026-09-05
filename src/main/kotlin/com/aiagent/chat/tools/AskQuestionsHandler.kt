package com.aiagent.chat.tools

import com.intellij.openapi.project.Project
import java.awt.GridBagConstraints
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Structured user question tool.
 *
 * Inspired by refact-main's ask_questions tool.
 * Presents structured questions to the user (yes_no, single_select, multi_select, free_text)
 * and blocks until answered. Returns the answer as a string for the LLM.
 *
 * All interactions are rendered inline within the chat window via [AskQuestionPanel],
 * never using external CLion/Swing dialogs.
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
     * Callback for adding an inline component to the chat message area.
     * Set by the UI layer (ChatToolWindowPanel) so questions render inside the chat.
     */
    var inlineComponentAdder: ((JPanel, GridBagConstraints) -> Unit)? = null

    /**
     * Ask a single question and block until the user answers.
     * Renders an inline [AskQuestionPanel] in the chat message area.
     */
    fun ask(question: Question): Answer {
        // If no inline adder is wired, return a default — never fall back to external dialogs
        if (inlineComponentAdder == null) {
            return Answer(question.question, "(no answer: UI not available)")
        }

        val latch = java.util.concurrent.CountDownLatch(1)
        var answerText = "(no answer)"

        SwingUtilities.invokeLater {
            val panel = com.aiagent.chat.ui.AskQuestionPanel(question) { answer ->
                answerText = answer
                latch.countDown()
            }

            val constraints = GridBagConstraints().apply {
                gridx = 0
                gridy = GridBagConstraints.RELATIVE
                weightx = 1.0
                weighty = 0.0
                fill = GridBagConstraints.HORIZONTAL
                anchor = GridBagConstraints.NORTH
            }
            inlineComponentAdder?.invoke(panel, constraints)
        }

        try {
            latch.await(120, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            // Timeout - default to no answer
        }

        return Answer(question.question, answerText)
    }

    /**
     * Ask multiple questions in sequence.
     */
    fun askAll(questions: List<Question>): List<Answer> {
        return questions.map { ask(it) }
    }
}
