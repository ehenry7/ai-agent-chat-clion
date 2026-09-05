package com.aiagent.chat.tools

import com.aiagent.chat.debug.DebugLog
import com.aiagent.chat.model.ToolCategory
import com.aiagent.chat.model.ToolDefinition
import com.aiagent.chat.model.ToolFunctionDef
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiManager
import com.intellij.psi.codeStyle.CodeStyleManager
import kotlinx.serialization.json.*
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

class PlatformToolHandler(
    val project: Project,
    val getMemory: () -> String,
    val setMemory: (String) -> Unit,
    val getGlobalMemory: () -> String,
    val setGlobalMemory: (String) -> Unit,
    val getTodoList: () -> List<com.aiagent.chat.model.TodoItem>,
    val setTodoList: (List<com.aiagent.chat.model.TodoItem>) -> Unit,
    val approvalHandler: ApprovalHandler? = null,
    var approvalMode: com.aiagent.chat.model.ApprovalMode = com.aiagent.chat.model.ApprovalMode.BALANCED,
    val undoStack: UndoStack = UndoStack(),
    val commandSafety: CommandSafety = CommandSafety(),
    val askQuestionsHandler: AskQuestionsHandler? = null,
    val planManager: com.aiagent.chat.agent.PlanManager? = null,
    var contextCompactor: com.aiagent.chat.agent.ContextCompactor? = null,
    var getMessages: (() -> List<com.aiagent.chat.model.ChatMessage>)? = null,
    var setMessages: ((List<com.aiagent.chat.model.ChatMessage>) -> Unit)? = null
) {
    private val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()

    /** Late-bind message accessors for compress_chat tools (set after construction from the UI layer). */
    fun bindMessagesAccessor(
        getMessages: () -> List<com.aiagent.chat.model.ChatMessage>,
        setMessages: (List<com.aiagent.chat.model.ChatMessage>) -> Unit
    ) {
        this.getMessages = getMessages
        this.setMessages = setMessages
    }

    /**
     * Interface for non-blocking tool approval.
     * The UI implements this to show inline approval panels.
     * Now supports deny reasons (inspired by refact-main's ToolDecision).
     */
    interface ApprovalHandler {
        fun requestApproval(toolName: String, toolArgs: String, category: ToolCategory): ApprovalResult
    }

    data class ApprovalResult(
        val approved: Boolean,
        val autoApproveSession: Boolean = false,
        val denyReason: String? = null
    )

    /**
     * Tool category lookup — delegates to ToolRegistry (single source of truth).
     * Inspired by refact-main's per-tool PauseReason rules.
     */
    fun getToolCategory(name: String): ToolCategory {
        return ToolRegistry.getCategory(name)
    }

    private val autoApprovedTools = mutableSetOf<String>()

    fun resolveContainedFile(relPath: String): File {
        val baseDir = File(project.basePath ?: throw IllegalStateException("No project base path"))
        val target = File(baseDir, relPath).canonicalFile
        if (!target.path.startsWith(baseDir.canonicalPath)) {
            throw SecurityException("Path escapes workspace: $relPath")
        }
        return target
    }

    fun readFile(relPath: String): String {
        val file = resolveContainedFile(relPath)
        if (!file.exists()) return "Error: File not found: $relPath"
        return file.readText(StandardCharsets.UTF_8).take(12000)
    }

    fun readFileLines(relPath: String, startLine: Int, endLine: Int): String {
        val file = resolveContainedFile(relPath)
        if (!file.exists()) return "Error: File not found: $relPath"
        val lines = file.readLines(StandardCharsets.UTF_8)
        val s = (startLine - 1).coerceAtLeast(0)
        val e = endLine.coerceAtMost(lines.size)
        if (s >= e) return "Error: startLine must be <= endLine"
        return lines.subList(s, e).mapIndexed { idx, line -> "${s + idx + 1}: $line" }.joinToString("\n")
    }

    fun writeFile(relPath: String, content: String): String {
        var result = ""
        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                val file = resolveContainedFile(relPath)
                // Push undo snapshot if file already exists
                if (file.exists()) {
                    undoStack.push(relPath, file.readText(StandardCharsets.UTF_8))
                }
                file.parentFile.mkdirs()
                file.writeText(content, StandardCharsets.UTF_8)
                VfsUtil.markDirtyAndRefresh(false, true, true, VfsUtil.findFileByIoFile(file, true))
                result = "Wrote ${content.toByteArray().size} bytes to $relPath"
            }
        }
        return result
    }

    fun editFile(relPath: String, search: String, replace: String, replaceAll: Boolean = false): String {
        var result = ""
        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                val file = resolveContainedFile(relPath)
                if (!file.exists()) {
                    result = "Error: File not found: $relPath"
                    return@runWriteCommandAction
                }
                val raw = file.readText(StandardCharsets.UTF_8)
                if (!raw.contains(search)) {
                    result = "Error: Search string not found in $relPath"
                    return@runWriteCommandAction
                }
                // Push undo snapshot before editing
                undoStack.push(relPath, raw)
                val updated = if (replaceAll) raw.replace(search, replace) else raw.replaceFirst(search, replace)
                file.writeText(updated, StandardCharsets.UTF_8)
                VfsUtil.markDirtyAndRefresh(false, true, true, VfsUtil.findFileByIoFile(file, true))
                result = "Edited $relPath successfully"
            }
        }
        return result
    }

    fun listDirectory(relPath: String): String {
        val file = resolveContainedFile(relPath.ifEmpty { "." })
        if (!file.exists() || !file.isDirectory) return "Error: Directory not found: $relPath"
        return file.listFiles()?.joinToString("\n") {
            if (it.isDirectory) "${it.name}/" else it.name
        } ?: "(empty directory)"
    }

    fun findFiles(globPattern: String, maxResults: Int = 200): String {
        val root = File(project.basePath ?: return "No project root")
        val regex = Regex(globPattern.replace(".", "\\.").replace("*", ".*"))
        val matched = mutableListOf<String>()
        root.walkTopDown().filter { it.isFile && !it.path.contains(".git") }.forEach {
            val rel = it.relativeTo(root).path.replace('\\', '/')
            if (regex.containsMatchIn(rel)) {
                matched.add(rel)
                if (matched.size >= maxResults) return@forEach
            }
        }
        return matched.joinToString("\n").ifEmpty { "No files found" }
    }

    fun searchInFiles(query: String, isRegex: Boolean): String {
        val root = File(project.basePath ?: return "No project root")
        val pattern = if (isRegex) Regex(query, RegexOption.IGNORE_CASE) else Regex(Regex.escape(query), RegexOption.IGNORE_CASE)
        val matches = mutableListOf<String>()

        root.walkTopDown().filter { it.isFile && !it.path.contains(".git") }.forEach { file ->
            try {
                val rel = file.relativeTo(root).path.replace('\\', '/')
                file.useLines { lines ->
                    lines.forEachIndexed { i, line ->
                        if (pattern.containsMatchIn(line)) {
                            matches.add("$rel:${i + 1}: ${line.trim()}")
                            if (matches.size >= 100) return@useLines
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        return matches.joinToString("\n").ifEmpty { "No matches found" }
    }

    fun runCommand(command: String): String {
        // Layer 3: CommandSafety glob pattern check (deny/confirm patterns)
        val safetyDecision = commandSafety.evaluate(command)
        if (safetyDecision == CommandSafety.Decision.DENY) {
            return "Error: Command blocked by security policy (deny pattern matched). Command: $command"
        }
        if (safetyDecision == CommandSafety.Decision.CONFIRM) {
            // Force confirmation even in AUTOPILOT mode
            if (approvalHandler != null) {
                val result = approvalHandler.requestApproval("run_command (safety)", command, ToolCategory.DANGEROUS)
                if (!result.approved) {
                    val reason = result.denyReason ?: "User denied safety confirmation"
                    return "Tool call denied by user (safety confirmation). Reason: $reason"
                }
            } else {
                var approved = false
                ApplicationManager.getApplication().invokeAndWait {
                    val res = Messages.showYesNoDialog(
                        project,
                        "Safety confirmation required for command:\n$command\n\nAllow this operation?",
                        "Command Safety Confirmation",
                        Messages.getWarningIcon()
                    )
                    approved = (res == Messages.YES)
                }
                if (!approved) return "Tool call denied by user (safety confirmation)."
            }
        }

        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val cmd = if (isWindows) {
            GeneralCommandLine("powershell.exe", "-NoProfile", "-Command", command)
        } else {
            GeneralCommandLine("bash", "-lc", command)
        }
        cmd.withWorkDirectory(project.basePath)
        val output = ExecUtil.execAndGetOutput(cmd, 30_000)
        return "STDOUT:\n${output.stdout}\nSTDERR:\n${output.stderr}"
    }

    fun runPython(code: String): String {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val pyCmd = if (isWindows) "python" else "python3"
        val cmd = GeneralCommandLine(pyCmd, "-c", code)
        cmd.withWorkDirectory(project.basePath)
        val output = ExecUtil.execAndGetOutput(cmd, 60_000)
        return output.stdout.ifBlank { output.stderr.ifBlank { "Executed successfully with no output." } }
    }

    fun fetchUrl(urlStr: String): String {
        // S2: Validate URL scheme to prevent SSRF and file:// access
        val uri = try {
            URI.create(urlStr)
        } catch (_: Exception) {
            return "Error: Invalid URL format"
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return "Error: Only http and https URLs are allowed (got: $scheme)"
        }
        // Block localhost and private IP ranges to prevent SSRF
        val host = uri.host
        if (host != null) {
            val isLocalhost = host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "0.0.0.0"
            val isPrivate = host.startsWith("10.") || host.startsWith("172.16.") || host.startsWith("172.17.") ||
                    host.startsWith("172.18.") || host.startsWith("172.19.") || host.startsWith("172.2") ||
                    host.startsWith("172.3") || host.startsWith("192.168.") || host.startsWith("169.254.")
            if (isLocalhost || isPrivate) {
                return "Error: Access to localhost and private IP ranges is blocked for security"
            }
        }
        val req = HttpRequest.newBuilder().uri(uri).timeout(Duration.ofSeconds(15)).GET().build()
        val res = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
        return res.body().take(100000)
    }

    fun webSearch(query: String, count: Int = 5): String {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8)
        val url = "https://duckduckgo.com/html/?q=$encoded"
        val html = fetchUrl(url)
        val regex = Regex("<a rel=\"nofollow\" class=\"result__a\" href=\"([^\"]+)\">(.*?)</a>")
        val results = regex.findAll(html).take(count).map {
            val link = it.groupValues[1]
            val title = it.groupValues[2].replace(Regex("<[^>]+>"), "").trim()
            "$title\n$link"
        }.toList()
        return results.joinToString("\n\n").ifEmpty { "No results found." }
    }

    fun git(args: List<String>): String {
        val cmd = GeneralCommandLine("git", *args.toTypedArray())
        cmd.withWorkDirectory(project.basePath)
        val output = ExecUtil.execAndGetOutput(cmd, 30_000)
        return output.stdout.ifBlank { output.stderr.ifBlank { "(no output)" } }
    }

    private fun getActiveEditor(): String {
        var result = "No active editor found."
        ApplicationManager.getApplication().invokeAndWait {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor
            if (editor != null) {
                val file = FileDocumentManager.getInstance().getFile(editor.document)
                val selection = editor.selectionModel.selectedText
                result = "Active File: ${file?.path}\nSelection:\n${selection ?: "None"}"
            }
        }
        return result
    }

    private fun formatDocument(relPath: String): String {
        var result = ""
        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                try {
                    val file = resolveContainedFile(relPath)
                    val vFile = VfsUtil.findFileByIoFile(file, true) ?: return@runWriteCommandAction
                    val psiFile = PsiManager.getInstance(project).findFile(vFile) ?: return@runWriteCommandAction
                    
                    CodeStyleManager.getInstance(project).reformat(psiFile)
                    result = "Successfully formatted $relPath"
                } catch (e: Exception) {
                    result = "Formatting failed: ${e.message}"
                }
            }
        }
        return result.ifBlank { "Formatting failed: could not resolve file or PSI." }
    }

    // === New tool methods (tool-expansion) ===

    fun tree(relPath: String, maxDepth: Int = 3, includeHidden: Boolean = false): String {
        val baseDir = project.basePath ?: return "No project root"
        val root = if (relPath.isBlank() || relPath == ".") File(baseDir) else resolveContainedFile(relPath)
        return TreeBuilder.buildTree(root, maxDepth, includeHidden)
    }

    fun rm(relPath: String, recursive: Boolean = false, dryRun: Boolean = false): String {
        val file = resolveContainedFile(relPath)
        if (!file.exists()) return "Error: File not found: $relPath"

        if (dryRun) {
            val entries = if (file.isDirectory && recursive) {
                file.walkTopDown().count()
            } else if (file.isDirectory) {
                file.listFiles()?.size ?: 0
            } else 1
            return "[dry_run] Would delete: $relPath ($entries item(s))"
        }

        if (file.isDirectory) {
            if (!recursive && (file.listFiles()?.isNotEmpty() == true)) {
                return "Error: Directory not empty. Use recursive=true to delete non-empty directories."
            }
            val deleted = file.deleteRecursively()
            return if (deleted) "Deleted directory: $relPath" else "Error: Failed to delete directory: $relPath"
        } else {
            val deleted = file.delete()
            return if (deleted) "Deleted file: $relPath" else "Error: Failed to delete file: $relPath"
        }
    }

    fun mv(source: String, destination: String, overwrite: Boolean = false): String {
        val srcFile = resolveContainedFile(source)
        if (!srcFile.exists()) return "Error: Source not found: $source"
        val destFile = resolveContainedFile(destination)
        if (destFile.exists() && !overwrite) {
            return "Error: Destination already exists: $destination. Use overwrite=true to replace."
        }
        destFile.parentFile.mkdirs()
        val moved = srcFile.renameTo(destFile)
        return if (moved) "Moved $source -> $destination" else "Error: Failed to move $source to $destination"
    }

    fun updateTextdocByLines(relPath: String, startLine: Int, endLine: Int, content: String): String {
        var result = ""
        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                val file = resolveContainedFile(relPath)
                if (!file.exists()) {
                    result = "Error: File not found: $relPath"
                    return@runWriteCommandAction
                }
                // Push undo snapshot
                undoStack.push(relPath, file.readText(StandardCharsets.UTF_8))

                val lines = file.readLines(StandardCharsets.UTF_8).toMutableList()
                val s = (startLine - 1).coerceAtLeast(0)
                val e = endLine.coerceAtMost(lines.size)
                if (s >= e) {
                    result = "Error: startLine must be <= endLine"
                    return@runWriteCommandAction
                }
                val newLines = content.split("\n")
                lines.subList(s, e).clear()
                lines.addAll(s, newLines)
                file.writeText(lines.joinToString("\n"), StandardCharsets.UTF_8)
                VfsUtil.markDirtyAndRefresh(false, true, true, VfsUtil.findFileByIoFile(file, true))
                result = "Updated lines $startLine-$endLine in $relPath (replaced with ${newLines.size} line(s))"
            }
        }
        return result
    }

    fun undoTextdoc(relPath: String?): String {
        val snapshot = if (relPath != null && relPath.isNotBlank()) {
            undoStack.popForPath(relPath)
        } else {
            undoStack.pop()
        }
        if (snapshot == null) return "Error: No undo history available"

        var result = ""
        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                val file = resolveContainedFile(snapshot.path)
                file.writeText(snapshot.content, StandardCharsets.UTF_8)
                VfsUtil.markDirtyAndRefresh(false, true, true, VfsUtil.findFileByIoFile(file, true))
                result = "Reverted ${snapshot.path} to previous content (${snapshot.content.length} chars)"
            }
        }
        return result
    }

    fun askQuestions(question: String, questionType: String, options: List<String>): String {
        val handler = askQuestionsHandler ?: return "Error: Question handler not available"
        val q = AskQuestionsHandler.Question(question, questionType, options)
        val answer = handler.ask(q)
        return "Q: ${answer.question}\nA: ${answer.answer}"
    }

    fun sleep(seconds: Double): String {
        Thread.sleep((seconds * 1000).toLong())
        return "Slept for $seconds seconds."
    }

    fun compressChatProbe(): String {
        val compactor = contextCompactor ?: return "Error: Context compactor not available"
        val messages = getMessages?.invoke() ?: return "Error: Cannot access message history"
        return compactor.getCompactionDiagnostics(messages)
    }

    fun compressChatApply(): String {
        val compactor = contextCompactor ?: return "Error: Context compactor not available"
        val messages = getMessages?.invoke() ?: return "Error: Cannot access message history"
        if (!compactor.needsCompaction(messages)) {
            return "Compaction not needed yet (message count and token estimate below thresholds)."
        }
        val sizeBefore = messages.size
        // Use runBlocking with timeout to avoid potential deadlock
        val compacted = try {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeout(30_000) {
                    compactor.compact(messages)
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            // Fallback: use non-LLM compaction if LLM summarization times out
            DebugLog.warn("PlatformTools", "compressChatApply timed out, using fallback compaction")
            compactor.fallbackCompact(messages) ?: return "Error: Compaction timed out and fallback failed."
        }
        val sizeAfter = compacted.size
        setMessages?.invoke(compacted)
        return "Context compacted: $sizeBefore -> $sizeAfter messages."
    }

    fun setPlan(planMarkdown: String): String {
        val pm = planManager ?: return "Error: Plan manager not available"
        pm.setPlanFromMarkdown(planMarkdown)
        val plan = pm.getPlan()
        return "Plan set: '${plan?.title}' with ${plan?.steps?.size ?: 0} steps."
    }

    fun getPlan(): String {
        val pm = planManager ?: return "Error: Plan manager not available"
        val plan = pm.getPlan() ?: return "No plan set. Use set_plan to create one."
        return plan.toMarkdown()
    }

    fun updatePlan(stepId: String, status: String): String {
        val pm = planManager ?: return "Error: Plan manager not available"
        val updated = pm.updateStep(stepId, status)
        return if (updated) "Updated step '$stepId' to status '$status'." else "Error: Step '$stepId' not found in current plan."
    }

    fun execute(name: String, args: JsonObject): String {
        val category = getToolCategory(name)
        val requiresApproval = approvalMode.requiresApproval(category) && !autoApprovedTools.contains(name)

        if (requiresApproval) {
            if (approvalHandler != null) {
                val result = approvalHandler.requestApproval(name, args.toString(), category)
                if (!result.approved) {
                    // Return deny reason to the LLM so it can adjust its approach
                    val reason = result.denyReason ?: "No reason provided"
                    return "Tool call denied by user. Reason: $reason"
                }
                // DANGEROUS tools never get auto-approve session
                if (result.autoApproveSession && category != ToolCategory.DANGEROUS) {
                    autoApprovedTools.add(name)
                }
            } else {
                // Fallback: blocking dialog (legacy behavior)
                var approved = false
                ApplicationManager.getApplication().invokeAndWait {
                    val res = Messages.showYesNoDialog(
                        project,
                        "Agent wants to execute $name (category: $category).\nArgs: $args\n\nAllow this operation?",
                        "Tool Execution Request",
                        Messages.getQuestionIcon()
                    )
                    approved = (res == Messages.YES)
                }
                if (!approved) return "Tool call denied by user. Reason: User rejected in dialog."
            }
        }

        return when (name) {
            "read_file" -> readFile(args["path"]?.jsonPrimitive?.content ?: "")
            "read_file_lines" -> readFileLines(
                args["path"]?.jsonPrimitive?.content ?: "",
                args["startLine"]?.jsonPrimitive?.intOrNull ?: 1,
                args["endLine"]?.jsonPrimitive?.intOrNull ?: 100
            )
            "write_file" -> writeFile(
                args["path"]?.jsonPrimitive?.content ?: "",
                args["content"]?.jsonPrimitive?.content ?: ""
            )
            "edit_file" -> editFile(
                args["path"]?.jsonPrimitive?.content ?: "",
                args["search"]?.jsonPrimitive?.content ?: "",
                args["replace"]?.jsonPrimitive?.content ?: "",
                args["replaceAll"]?.jsonPrimitive?.booleanOrNull ?: false
            )
            "list_directory" -> listDirectory(args["path"]?.jsonPrimitive?.content ?: "")
            "find_files" -> findFiles(args["glob"]?.jsonPrimitive?.content ?: "**/*")
            "search_in_files" -> searchInFiles(
                args["query"]?.jsonPrimitive?.content ?: "",
                args["isRegex"]?.jsonPrimitive?.booleanOrNull ?: false
            )
            "run_command" -> runCommand(args["command"]?.jsonPrimitive?.content ?: "")
            "run_python" -> runPython(args["code"]?.jsonPrimitive?.content ?: "")
            "fetch_url" -> fetchUrl(args["url"]?.jsonPrimitive?.content ?: "")
            "web_search" -> webSearch(args["query"]?.jsonPrimitive?.content ?: "")
            "git_status" -> git(listOf("status", "--porcelain"))
            "git_diff" -> git(listOf("diff"))
            "git_log" -> git(listOf("log", "-n5", "--oneline"))
            "git_commit" -> {
                git(listOf("add", "-A"))
                git(listOf("commit", "-m", args["message"]?.jsonPrimitive?.content ?: "Update"))
            }
            "apply_diff" -> {
                val path = args["path"]?.jsonPrimitive?.content ?: ""
                val diff = args["diff"]?.jsonPrimitive?.content ?: ""
                val file = resolveContainedFile(path)
                val orig = file.readText(StandardCharsets.UTF_8)
                val res = DiffEngine.applyDiff(orig, diff)
                if (res.success && res.content != null) {
                    writeFile(path, res.content)
                    "Applied diff successfully to $path."
                } else {
                    "apply_diff failed: ${res.error}"
                }
            }
            "apply_patch" -> {
                val patch = args["patch"]?.jsonPrimitive?.content ?: ""
                val hunks = PatchEngine.parsePatch(patch)
                for (hunk in hunks) {
                    when (hunk) {
                        is PatchEngine.Hunk.AddFile -> writeFile(hunk.path, hunk.contents)
                        is PatchEngine.Hunk.DeleteFile -> {
                            val f = resolveContainedFile(hunk.path)
                            if (f.exists()) f.delete()
                        }
                        is PatchEngine.Hunk.UpdateFile -> {
                            val file = resolveContainedFile(hunk.path)
                            val orig = if (file.exists()) file.readText(StandardCharsets.UTF_8) else ""
                            val updated = PatchEngine.applyChunksToContent(orig, hunk.chunks)
                            writeFile(hunk.movePath ?: hunk.path, updated)
                            if (hunk.movePath != null && file.exists()) file.delete()
                        }
                    }
                }
                "Applied patch with ${hunks.size} hunk(s)."
            }
            "update_todo_list" -> {
                val todosRaw = args["todos"]?.jsonPrimitive?.content ?: ""
                val parsed = Todos.parseMarkdownChecklist(todosRaw)
                setTodoList(parsed)
                "Todo list updated successfully (${parsed.size} items)."
            }
            "update_memory" -> {
                val content = args["content"]?.jsonPrimitive?.content ?: ""
                val scope = args["scope"]?.jsonPrimitive?.content ?: "folder"
                if (scope == "global") {
                    setGlobalMemory(content)
                    "Global memory updated."
                } else {
                    setMemory(content)
                    "Folder memory updated."
                }
            }
            "get_active_editor" -> getActiveEditor()
            "format_document" -> formatDocument(args["path"]?.jsonPrimitive?.content ?: "")
            // === New tools (tool-expansion) ===
            "tree" -> tree(
                args["path"]?.jsonPrimitive?.content ?: "",
                args["maxDepth"]?.jsonPrimitive?.intOrNull ?: 3,
                args["includeHidden"]?.jsonPrimitive?.booleanOrNull ?: false
            )
            "rm" -> rm(
                args["path"]?.jsonPrimitive?.content ?: "",
                args["recursive"]?.jsonPrimitive?.booleanOrNull ?: false,
                args["dry_run"]?.jsonPrimitive?.booleanOrNull ?: false
            )
            "mv" -> mv(
                args["source"]?.jsonPrimitive?.content ?: "",
                args["destination"]?.jsonPrimitive?.content ?: "",
                args["overwrite"]?.jsonPrimitive?.booleanOrNull ?: false
            )
            "update_textdoc_by_lines" -> updateTextdocByLines(
                args["path"]?.jsonPrimitive?.content ?: "",
                args["startLine"]?.jsonPrimitive?.intOrNull ?: 1,
                args["endLine"]?.jsonPrimitive?.intOrNull ?: 1,
                args["content"]?.jsonPrimitive?.content ?: ""
            )
            "undo_textdoc" -> {
                val pathArg = args["path"]
                val pathStr = if (pathArg != null && pathArg !is kotlinx.serialization.json.JsonNull) pathArg.jsonPrimitive.content else null
                undoTextdoc(pathStr)
            }
            "ask_questions" -> askQuestions(
                args["question"]?.jsonPrimitive?.content ?: "",
                args["question_type"]?.jsonPrimitive?.content ?: "free_text",
                args["options"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
            )
            "sleep" -> sleep(args["seconds"]?.jsonPrimitive?.doubleOrNull ?: 1.0)
            "compress_chat_probe" -> compressChatProbe()
            "compress_chat_apply" -> compressChatApply()
            "set_plan" -> setPlan(args["plan"]?.jsonPrimitive?.content ?: "")
            "get_plan" -> getPlan()
            "update_plan" -> updatePlan(
                args["step_id"]?.jsonPrimitive?.content ?: "",
                args["status"]?.jsonPrimitive?.content ?: "pending"
            )
            else -> "Unknown tool: $name"
        }
    }

    companion object {
        fun getToolDefinitions(): List<ToolDefinition> {
            return ToolRegistry.definitions()
        }
    }
}
