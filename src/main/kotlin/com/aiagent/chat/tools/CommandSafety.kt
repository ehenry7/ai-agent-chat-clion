package com.aiagent.chat.tools

/**
 * Fine-grained command safety checker using glob patterns.
 *
 * Inspired by refact-main's confirm/deny glob rule system.
 * - Deny patterns hard-block execution entirely (even in AUTOPILOT mode).
 * - Confirm patterns force user confirmation even in AUTOPILOT mode.
 * - All other commands pass through to the normal approval flow.
 *
 * This adds a third safety layer on top of the category-based ApprovalMode system:
 *   Layer 1: ToolCategory (READ_ONLY / MUTATING / DANGEROUS)
 *   Layer 2: ApprovalMode (STRICT / BALANCED / PERMISSIVE / AUTOPILOT)
 *   Layer 3: CommandSafety deny/confirm glob patterns (this class)
 */
class CommandSafety(
    private val denyPatterns: List<String> = DEFAULT_DENY_PATTERNS,
    private val confirmPatterns: List<String> = DEFAULT_CONFIRM_PATTERNS
) {
    enum class Decision { ALLOW, CONFIRM, DENY }

    /**
     * Evaluate a command string against deny and confirm patterns.
     * Returns DENY if any deny pattern matches, CONFIRM if any confirm pattern matches,
     * or ALLOW if no patterns match.
     */
    fun evaluate(command: String): Decision {
        for (pattern in denyPatterns) {
            if (matchGlob(pattern, command)) return Decision.DENY
        }
        for (pattern in confirmPatterns) {
            if (matchGlob(pattern, command)) return Decision.CONFIRM
        }
        return Decision.ALLOW
    }

    /** Check if a command would be denied. */
    fun isDenied(command: String): Boolean = evaluate(command) == Decision.DENY

    /** Check if a command requires confirmation. */
    fun needsConfirmation(command: String): Boolean = evaluate(command) == Decision.CONFIRM

    /**
     * Match a glob pattern against a command string.
     * Supports * (any sequence) and ? (single character).
     * Matching is case-insensitive and searches anywhere in the string (like refact-main).
     */
    private fun matchGlob(pattern: String, text: String): Boolean {
        val regexStr = pattern
            .replace("\\", "\\\\")
            .replace(".", "\\.")
            .replace("+", "\\+")
            .replace("(", "\\(")
            .replace(")", "\\)")
            .replace("[", "\\[")
            .replace("]", "\\]")
            .replace("{", "\\{")
            .replace("}", "\\}")
            .replace("^", "\\^")
            .replace("$", "\\$")
            .replace("|", "\\|")
            .replace("*", ".*")
            .replace("?", ".")
        return Regex(regexStr, RegexOption.IGNORE_CASE).containsMatchIn(text)
    }

    companion object {
        /**
         * Default deny patterns — these commands are ALWAYS blocked, regardless of approval mode.
         * Inspired by refact-main's deny_rules.
         */
        val DEFAULT_DENY_PATTERNS = listOf(
            "*rm -rf /*",
            "*rm -rf /",
            "*mkfs*",
            "*dd if=*",
            "*shutdown*",
            "*reboot*",
            "*halt*",
            "*:(){ :|:& };:*",
            "*curl*|*sh*",
            "*curl*|*bash*",
            "*wget*|*sh*",
            "*wget*|*bash*",
            "*git push --force*",
            "*git push -f *",
            "*chmod -R 777*",
            "*>/dev/sda*",
            "*mv /*",
            "*cp /dev/zero*",
            "*fork bomb*"
        )

        /**
         * Default confirm patterns — these commands require confirmation even in AUTOPILOT mode.
         * Inspired by refact-main's ask_user_rules.
         */
        val DEFAULT_CONFIRM_PATTERNS = listOf(
            "*rm *",
            "*rmdir *",
            "*git push*",
            "*git reset --hard*",
            "*git clean*",
            "*chmod*",
            "*chown*",
            "*sudo *",
            "*kill *",
            "*pkill*",
            "*killall*",
            "*npm publish*",
            "*pip install*",
            "*apt install*",
            "*apt-get install*",
            "*brew install*",
            "*del *",
            "*Remove-Item*",
            "*Format-Volume*"
        )
    }
}
