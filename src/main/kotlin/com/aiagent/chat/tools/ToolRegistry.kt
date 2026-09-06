package com.aiagent.chat.tools

import com.aiagent.chat.model.ToolCategory
import com.aiagent.chat.model.ToolDeclaration
import com.aiagent.chat.model.ToolDefinition
import com.aiagent.chat.model.ToolFunctionDef
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Central registry for all agent tools.
 *
 * This is the single source of truth for tool definitions AND their safety categories.
 * Previously, category mappings were duplicated in PlatformToolHandler.toolCategories
 * and AgentEngine.getToolCategoryForApproval() — both could get out of sync.
 *
 * Inspired by refact-main's tool registry with mode-based sets and dependency declarations.
 *
 * Usage:
 *   ToolRegistry.all()                          // all declarations
 *   ToolRegistry.definitions()                  // all ToolDefinitions (for API calls)
 *   ToolRegistry.definitionsForPhase("discovery") // read-only tools only
 *   ToolRegistry.getCategory("write_file")      // MUTATING
 *   ToolRegistry.getDeclaration("run_command")  // ToolDeclaration?
 */
object ToolRegistry {

    // --- Tool parameter builder helper ---

    private fun paramsObject(block: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject(block)

    // --- All tool declarations ---

    private val declarations: List<ToolDeclaration> = listOf(
        // === READ_ONLY tools ===
        ToolDeclaration(
            definition = ToolDefinition(function = ToolFunctionDef(
                name = "read_file",
                description = "Read file content in workspace",
                parameters = paramsObject {
                    put("type", "object")
                    putJsonObject("properties") { putJsonObject("path") { put("type", "string") } }
                    putJsonArray("required") { add("path") }
                }
            )),
            category = ToolCategory.READ_ONLY
        ),
        ToolDeclaration(
            definition = ToolDefinition(function = ToolFunctionDef(
                name = "read_file_lines",
                description = "Read specific line range from a file",
                parameters = paramsObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("path") { put("type", "string") }
                        putJsonObject("startLine") { put("type", "integer") }
                        putJsonObject("endLine") { put("type", "integer") }
                    }
                    putJsonArray("required") { add("path"); add("startLine"); add("endLine") }
                }
            )),
            category = ToolCategory.READ_ONLY
        ),
        ToolDeclaration(
            definition = ToolDefinition(function = ToolFunctionDef(
                name = "list_directory",
                description = "List files in directory",
                parameters = paramsObject {
                    put("type", "object")
                    putJsonObject("properties") { putJsonObject("path") { put("type", "string") } }
                }
            )),
            category = ToolCategory.READ_ONLY
        ),
        ToolDeclaration(
            definition = ToolDefinition(function = ToolFunctionDef(
                name = "find_files",
                description = "Find files matching a glob pattern in the workspace",
                parameters = paramsObject {
                    put("type", "object")
                    putJsonObject("properties") { putJsonObject("glob") { put("type", "string") } }
                    putJsonArray("required") { add("glob") }
                }
            )),
            category = ToolCategory.READ_ONLY
        ),
        ToolDeclaration(
            definition = ToolDefinition(function = ToolFunctionDef(
                name = "search_in_files",
                description = "Search for text or regex pattern in workspace files",
                parameters = paramsObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("query") { put("type", "string") }
                        putJsonObject("isRegex") { put("type", "boolean") }
                    }
                    putJsonArray("required") { add("query") }
                }
            )),
            category = ToolCategory.READ_ONLY
        ),
        ToolDeclaration(
            definition = ToolDefinition(function = ToolFunctionDef(
                name = "get_active_editor",
                description = "Retrieve the currently focused file path and user text selection in the IDE",
                parameters = paramsObject { put("type", "object") }
            )),
            category = ToolCategory.READ_ONLY
        ),
        ToolDeclaration(
            definition = ToolDefinition(function = ToolFunctionDef(
                name = "fetch_url",
                description = "Fetch content from a URL via HTTP GET (max 100KB response)",
                parameters = paramsObject {
                    put("type", "object")
                    putJsonObject("properties") { putJsonObject("url") { put("type", "string") } }
                    putJsonArray("required") { add("url") }
                }
            )),
            category = ToolCategory.READ_ONLY
        ),
        ToolDeclaration(
            definition = ToolDefinition(function = ToolFunctionDef(
                name = "web_search",
                description = "Search the web and return results",
                parameters = paramsObject {
                    put("type", "object")
                    putJsonObject("properties") { putJsonObject("query") { put("type", "string") } }
                    putJsonArray("required") { add("query") }
                }
            )),
            category = ToolCategory.READ_ONLY
        ),
        ToolDeclaration(
            definition = ToolDefinition(function = ToolFunctionDef(
                name = "git_status",
                description = "Show git working tree status (porcelain format)",
                parameters = paramsObject { put("type", "object") }
            )),
            category = ToolCategory.READ_ONLY
        ),
        ToolDeclaration(
            definition = ToolDefinition(function = ToolFunctionDef(
                name = "git_diff",
                description = "Show unstaged changes in the working tree",
                parameters = paramsObject { put("type", "object") }
            )),
            category = ToolCategory.READ_ONLY
        ),
        ToolDeclaration(
            definition = ToolDefinition(function = ToolFunctionDef(
                name = "git_log",
                description = "Show recent git commit history (last 5 commits)",
                parameters = paramsObject { put("type", "object") }
            )),
            category = ToolCategory.READ_ONLY
        ),
        ToolDeclaration(
            definition = ToolDefinition(function = ToolFunctionDef(
                name = "format_document",
                description = "Run IntelliJ native code formatter on a file",
                parameters = paramsObject {
                    put("type", "object")
                    putJsonObject("properties") { putJsonObject("path") { put("type", "string") } }
                    putJsonArray("required") { add("path") }
                }
            )),
            category = ToolCategory.READ_ONLY
        ),
        ToolDeclaration(
            definition = ToolDefinition(function = ToolFunctionDef(
                name = "update_todo_list",
                description = "Update the active task todo checklist",
                parameters = paramsObject {
                    put("type", "object")
                    putJsonObject("properties") { putJsonObject("todos") { put("type", "string") } }
                    putJsonArray("required") { add("todos") }
                }
            )),
            category = ToolCategory.READ_ONLY
        ),
        ToolDeclaration(
            definition = ToolDefinition(function = ToolFunctionDef(
                name = "request_phase_change",
                description = "Change the agent's phase to 'execution' to unlock mutation tools.",
                parameters = paramsObject {
                    put("type", "object")
                    putJsonObject("properties") { putJsonObject("target_phase") { put("type", "string") } }
                    putJsonArray("required") { add("target_phase") }
                }
            )),
            category = ToolCategory.READ_ONLY
        ),

        // === MUTATING tools ===
        ToolDeclaration(
            definition = ToolDefinition(function = ToolFunctionDef(
                name = "write_file",
                description = "Write full content to a file",
                parameters = paramsObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("path") { put("type", "string") }
                        putJsonObject("content") { put("type", "string") }
                    }
                    putJsonArray("required") { add("path"); add("content") }
                }
            )),
            category = ToolCategory.MUTATING
        ),
        ToolDeclaration(
            definition = ToolDefinition(function = ToolFunctionDef(
                name = "edit_file",
                description = "Replace unique text snippet in a file",
                parameters = paramsObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("path") { put("type", "string") }
                        putJsonObject("search") { put("type", "string") }
                        putJsonObject("replace") { put("type", "string") }
                        putJsonObject("replaceAll") { put("type", "boolean") }
                    }
                    putJsonArray("required") { add("path"); add("search"); add("replace") }
                }
            )),
            category = ToolCategory.MUTATING
        ),
        ToolDeclaration(
            definition = ToolDefinition(function = ToolFunctionDef(
                name = "apply_diff",
                description = "Apply SEARCH/REPLACE diff blocks to a file",
                parameters = paramsObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("path") { put("type", "string") }
                        putJsonObject("diff") { put("type", "string") }
                    }
                    putJsonArray("required") { add("path"); add("diff") }
                }
            )),
            category = ToolCategory.MUTATING
        ),
        ToolDeclaration(
            definition = ToolDefinition(function = ToolFunctionDef(
                name = "apply_patch",
                description = "Apply a multi-file patch (add/delete/update files)",
                parameters = paramsObject {
                    put("type", "object")
                    putJsonObject("properties") { putJsonObject("patch") { put("type", "string") } }
                    putJsonArray("required") { add("patch") }
                }
            )),
            category = ToolCategory.MUTATING
        ),
        ToolDeclaration(
            definition = ToolDefinition(function = ToolFunctionDef(
                name = "update_memory",
                description = "Update persistent agent memory (folder or global scope)",
                parameters = paramsObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("content") { put("type", "string") }
                        putJsonObject("scope") { put("type", "string") }
                    }
                    putJsonArray("required") { add("content") }
                }
            )),
            category = ToolCategory.MUTATING
        ),
        ToolDeclaration(
            definition = ToolDefinition(function = ToolFunctionDef(
                name = "git_commit",
                description = "Stage all changes and create a git commit",
                parameters = paramsObject {
                    put("type", "object")
                    putJsonObject("properties") { putJsonObject("message") { put("type", "string") } }
                    putJsonArray("required") { add("message") }
                }
            )),
            category = ToolCategory.MUTATING
        ),

        // === Additional READ_ONLY tools (tool-expansion) ===
        ToolDeclaration(
            definition = ToolDefinition(function = ToolFunctionDef(
                name = "tree",
                description = "Show project file tree with file sizes and line counts. Excludes build artifacts, .git, node_modules, etc.",
                parameters = paramsObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("path") { put("type", "string") }
                        putJsonObject("maxDepth") { put("type", "integer") }
                        putJsonObject("includeHidden") { put("type", "boolean") }
                    }
                }
            )),
            category = ToolCategory.READ_ONLY
        ),
        ToolDeclaration(
            definition = ToolDefinition(function = ToolFunctionDef(
                name = "ask_questions",
                description = "Ask the user structured questions (yes_no, single_select, multi_select, free_text). Blocks until the user answers.",
                parameters = paramsObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("question") { put("type", "string") }
                        putJsonObject("question_type") { put("type", "string") }
                        putJsonObject("options") {
                            put("type", "array")
                            putJsonObject("items") { put("type", "string") }
                        }
                    }
                    putJsonArray("required") { add("question"); add("question_type") }
                }
            )),
            category = ToolCategory.READ_ONLY
        ),
        ToolDeclaration(
            definition = ToolDefinition(function = ToolFunctionDef(
                name = "sleep",
                description = "Pause execution for a specified number of seconds (useful when waiting for builds or external processes).",
                parameters = paramsObject {
                    put("type", "object")
                    putJsonObject("properties") { putJsonObject("seconds") { put("type", "number") } }
                    putJsonArray("required") { add("seconds") }
                }
            )),
            category = ToolCategory.READ_ONLY
        ),
        ToolDeclaration(
            definition = ToolDefinition(function = ToolFunctionDef(
                name = "compress_chat_probe",
                description = "Check if context compaction is needed. Returns current message count, estimated tokens, and a recommendation.",
                parameters = paramsObject { put("type", "object") }
            )),
            category = ToolCategory.READ_ONLY
        ),
        ToolDeclaration(
            definition = ToolDefinition(function = ToolFunctionDef(
                name = "compress_chat_apply",
                description = "Trigger context compaction now. Summarizes old messages into a compact summary, keeping recent messages intact.",
                parameters = paramsObject { put("type", "object") }
            )),
            category = ToolCategory.READ_ONLY
        ),
        ToolDeclaration(
            definition = ToolDefinition(function = ToolFunctionDef(
                name = "set_plan",
                description = "Create or replace the current task plan from a markdown checklist. The plan is injected into the system prompt each turn to keep the agent focused.",
                parameters = paramsObject {
                    put("type", "object")
                    putJsonObject("properties") { putJsonObject("plan") { put("type", "string") } }
                    putJsonArray("required") { add("plan") }
                }
            )),
            category = ToolCategory.READ_ONLY
        ),
        ToolDeclaration(
            definition = ToolDefinition(function = ToolFunctionDef(
                name = "get_plan",
                description = "Retrieve the current task plan as a markdown checklist.",
                parameters = paramsObject { put("type", "object") }
            )),
            category = ToolCategory.READ_ONLY
        ),
        ToolDeclaration(
            definition = ToolDefinition(function = ToolFunctionDef(
                name = "update_plan",
                description = "Update the status of a plan step. Use step IDs from get_plan. " +
                    "Mark a step as 'in_progress' BEFORE you start working on it, and 'completed' immediately AFTER you finish it. " +
                    "Update steps in real time — do NOT batch all updates at the end.",
                parameters = paramsObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("step_id") { put("type", "string") }
                        putJsonObject("status") { put("type", "string") }
                    }
                    putJsonArray("required") { add("step_id"); add("status") }
                }
            )),
            category = ToolCategory.READ_ONLY
        ),

        // === Additional MUTATING tools (tool-expansion) ===
        ToolDeclaration(
            definition = ToolDefinition(function = ToolFunctionDef(
                name = "update_textdoc_by_lines",
                description = "Replace a specific line range in a file with new content. More deterministic than edit_file when line numbers are known.",
                parameters = paramsObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("path") { put("type", "string") }
                        putJsonObject("startLine") { put("type", "integer") }
                        putJsonObject("endLine") { put("type", "integer") }
                        putJsonObject("content") { put("type", "string") }
                    }
                    putJsonArray("required") { add("path"); add("startLine"); add("endLine"); add("content") }
                }
            )),
            category = ToolCategory.MUTATING
        ),
        ToolDeclaration(
            definition = ToolDefinition(function = ToolFunctionDef(
                name = "undo_textdoc",
                description = "Undo the last file modification made by any editing tool (write_file, edit_file, update_textdoc_by_lines, apply_diff). Restores the previous content.",
                parameters = paramsObject {
                    put("type", "object")
                    putJsonObject("properties") { putJsonObject("path") { put("type", "string") } }
                }
            )),
            category = ToolCategory.MUTATING
        ),
        ToolDeclaration(
            definition = ToolDefinition(function = ToolFunctionDef(
                name = "mv",
                description = "Move or rename a file/directory. Supports overwrite flag.",
                parameters = paramsObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("source") { put("type", "string") }
                        putJsonObject("destination") { put("type", "string") }
                        putJsonObject("overwrite") { put("type", "boolean") }
                    }
                    putJsonArray("required") { add("source"); add("destination") }
                }
            )),
            category = ToolCategory.MUTATING
        ),

        // === DANGEROUS tools ===
        ToolDeclaration(
            definition = ToolDefinition(function = ToolFunctionDef(
                name = "run_command",
                description = "Run a shell command in the project root",
                parameters = paramsObject {
                    put("type", "object")
                    putJsonObject("properties") { putJsonObject("command") { put("type", "string") } }
                    putJsonArray("required") { add("command") }
                }
            )),
            category = ToolCategory.DANGEROUS
        ),
        ToolDeclaration(
            definition = ToolDefinition(function = ToolFunctionDef(
                name = "run_python",
                description = "Execute python code snippet",
                parameters = paramsObject {
                    put("type", "object")
                    putJsonObject("properties") { putJsonObject("code") { put("type", "string") } }
                    putJsonArray("required") { add("code") }
                }
            )),
            category = ToolCategory.DANGEROUS
        ),
        ToolDeclaration(
            definition = ToolDefinition(function = ToolFunctionDef(
                name = "rm",
                description = "Delete a file or directory. Supports recursive deletion and dry_run preview.",
                parameters = paramsObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("path") { put("type", "string") }
                        putJsonObject("recursive") { put("type", "boolean") }
                        putJsonObject("dry_run") { put("type", "boolean") }
                    }
                    putJsonArray("required") { add("path") }
                }
            )),
            category = ToolCategory.DANGEROUS
        )
    )

    // --- Lookup indexes (built once at init) ---

    private val byName: Map<String, ToolDeclaration> = declarations.associateBy { it.name }

    private val byCategory: Map<ToolCategory, List<ToolDeclaration>> =
        declarations.groupBy { it.category }

    // --- Public API ---

    /** All tool declarations. */
    fun all(): List<ToolDeclaration> = declarations

    /** All tool definitions (for passing to the LLM API). */
    fun definitions(): List<ToolDefinition> = declarations.map { it.definition }

    /** Tool definitions filtered by phase: "discovery" = read-only only, "execution" = all. */
    fun definitionsForPhase(phase: String): List<ToolDefinition> {
        return if (phase == "execution") {
            definitions()
        } else {
            byCategory[ToolCategory.READ_ONLY]?.map { it.definition } ?: emptyList()
        }
    }

    /** Get the category for a tool by name. Defaults to MUTATING if unknown (safe default). */
    fun getCategory(name: String): ToolCategory {
        return byName[name]?.category ?: ToolCategory.MUTATING
    }

    /** Get the full declaration for a tool by name. */
    fun getDeclaration(name: String): ToolDeclaration? = byName[name]

    /** All tool names in a given category. */
    fun namesByCategory(category: ToolCategory): Set<String> {
        return (byCategory[category] ?: emptyList()).map { it.name }.toSet()
    }

    /** All mutating tool names (MUTATING + DANGEROUS). */
    fun mutatingToolNames(): Set<String> {
        return namesByCategory(ToolCategory.MUTATING) + namesByCategory(ToolCategory.DANGEROUS)
    }
}
