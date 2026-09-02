package com.aiagent.chat.tools

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
    val setTodoList: (List<com.aiagent.chat.model.TodoItem>) -> Unit
) {
    private val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()

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
        val req = HttpRequest.newBuilder().uri(URI.create(urlStr)).timeout(Duration.ofSeconds(15)).GET().build()
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

    fun execute(name: String, args: JsonObject): String {
        val mutatingTools = setOf("write_file", "edit_file", "run_command", "run_python", "apply_patch", "apply_diff", "git_commit")
        if (mutatingTools.contains(name)) {
            var approved = false
            ApplicationManager.getApplication().invokeAndWait {
                val res = Messages.showYesNoDialog(
                    project,
                    "Agent wants to execute $name.\nArgs: $args\n\nAllow this operation?",
                    "Tool Execution Request",
                    Messages.getQuestionIcon()
                )
                approved = (res == Messages.YES)
            }
            if (!approved) return "Execution rejected by user."
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
                        is PatchEngine.Hunk.DeleteFile -> resolveContainedFile(hunk.path).delete()
                        is PatchEngine.Hunk.UpdateFile -> {
                            val orig = readFile(hunk.path)
                            val updated = PatchEngine.applyChunksToContent(orig, hunk.chunks)
                            writeFile(hunk.movePath ?: hunk.path, updated)
                            if (hunk.movePath != null) resolveContainedFile(hunk.path).delete()
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
            else -> "Unknown tool: $name"
        }
    }

    companion object {
        fun getToolDefinitions(): List<ToolDefinition> {
            return listOf(
                ToolDefinition(
                    function = ToolFunctionDef(
                        name = "read_file",
                        description = "Read file content in workspace",
                        parameters = buildJsonObject {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("path") { put("type", "string") }
                            }
                            putJsonArray("required") { add("path") }
                        }
                    )
                ),
                ToolDefinition(
                    function = ToolFunctionDef(
                        name = "write_file",
                        description = "Write full content to a file",
                        parameters = buildJsonObject {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("path") { put("type", "string") }
                                putJsonObject("content") { put("type", "string") }
                            }
                            putJsonArray("required") { add("path"); add("content") }
                        }
                    )
                ),
                ToolDefinition(
                    function = ToolFunctionDef(
                        name = "edit_file",
                        description = "Replace unique text snippet in a file",
                        parameters = buildJsonObject {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("path") { put("type", "string") }
                                putJsonObject("search") { put("type", "string") }
                                putJsonObject("replace") { put("type", "string") }
                            }
                            putJsonArray("required") { add("path"); add("search"); add("replace") }
                        }
                    )
                ),
                ToolDefinition(
                    function = ToolFunctionDef(
                        name = "run_command",
                        description = "Run a shell command in the project root",
                        parameters = buildJsonObject {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("command") { put("type", "string") }
                            }
                            putJsonArray("required") { add("command") }
                        }
                    )
                ),
                ToolDefinition(
                    function = ToolFunctionDef(
                        name = "run_python",
                        description = "Execute python code snippet",
                        parameters = buildJsonObject {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("code") { put("type", "string") }
                            }
                            putJsonArray("required") { add("code") }
                        }
                    )
                ),
                ToolDefinition(
                    function = ToolFunctionDef(
                        name = "list_directory",
                        description = "List files in directory",
                        parameters = buildJsonObject {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("path") { put("type", "string") }
                            }
                        }
                    )
                ),
                ToolDefinition(
                    function = ToolFunctionDef(
                        name = "apply_diff",
                        description = "Apply SEARCH/REPLACE diff blocks to a file",
                        parameters = buildJsonObject {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("path") { put("type", "string") }
                                putJsonObject("diff") { put("type", "string") }
                            }
                            putJsonArray("required") { add("path"); add("diff") }
                        }
                    )
                ),
                ToolDefinition(
                    function = ToolFunctionDef(
                        name = "update_todo_list",
                        description = "Update the active task todo checklist",
                        parameters = buildJsonObject {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("todos") { put("type", "string") }
                            }
                            putJsonArray("required") { add("todos") }
                        }
                    )
                ),
                ToolDefinition(
                    function = ToolFunctionDef(
                        name = "get_active_editor",
                        description = "Retrieve the currently focused file path and user text selection in the IDE",
                        parameters = buildJsonObject { put("type", "object") }
                    )
                ),
                ToolDefinition(
                    function = ToolFunctionDef(
                        name = "format_document",
                        description = "Run IntelliJ native code formatter on a file",
                        parameters = buildJsonObject {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("path") { put("type", "string") }
                            }
                            putJsonArray("required") { add("path") }
                        }
                    )
                ),
                ToolDefinition(
                    function = ToolFunctionDef(
                        name = "request_phase_change",
                        description = "Change the agent's phase to 'execution' to unlock mutation tools.",
                        parameters = buildJsonObject {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("target_phase") { put("type", "string") }
                            }
                            putJsonArray("required") { add("target_phase") }
                        }
                    )
                )
            )
        }
    }
}
