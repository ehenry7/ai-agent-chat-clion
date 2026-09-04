package com.aiagent.chat.tools

import java.io.File

/**
 * Project file tree builder with smart truncation.
 *
 * Inspired by refact-main's tree tool.
 * Produces an indented tree view with file sizes and line counts for text files.
 * Excludes common non-source directories (.git, node_modules, build, etc.)
 * and binary file extensions.
 */
object TreeBuilder {

    private val EXCLUDE_DIRS = setOf(
        ".git", "node_modules", "__pycache__", "build", ".gradle",
        ".idea", "dist", "target", ".vscode", "bin", "obj",
        ".cache", ".tmp", "out", "venv", ".venv", "vendor"
    )

    private val EXCLUDE_EXTENSIONS = setOf(
        "class", "jar", "war", "dll", "so", "dylib",
        "exe", "bin", "dat", "db", "sqlite", "pdb", "o", "a"
    )

    private val TEXT_EXTENSIONS = setOf(
        "kt", "java", "py", "js", "ts", "tsx", "jsx", "json", "xml", "yaml", "yml",
        "md", "txt", "sql", "sh", "bat", "ps1", "c", "cpp", "h", "hpp", "cs",
        "go", "rs", "rb", "php", "swift", "scala", "gradle", "properties", "toml",
        "html", "css", "scss", "less", "vue", "svelte", "cfg", "ini", "conf",
        "dockerfile", "makefile", "cmake", "proto", "thrift"
    )

    /**
     * Build a tree view string for the given root directory.
     *
     * @param root The root directory to start from
     * @param maxDepth Maximum depth to traverse (default 3)
     * @param includeHidden Whether to include hidden files/dirs (starting with .)
     * @param maxEntries Maximum number of entries to show before truncating (default 500)
     * @return Indented tree string with file sizes and line counts
     */
    fun buildTree(
        root: File,
        maxDepth: Int = 3,
        includeHidden: Boolean = false,
        maxEntries: Int = 500
    ): String {
        if (!root.exists()) return "Error: Directory not found: ${root.path}"
        if (!root.isDirectory) return "Error: Not a directory: ${root.path}"

        val sb = StringBuilder()
        sb.append("${root.name}/\n")
        val entryCount = intArrayOf(0)
        buildTreeRecursive(root, "  ", 1, maxDepth, includeHidden, maxEntries, sb, entryCount)
        if (entryCount[0] >= maxEntries) {
            sb.append("\n... (truncated, max $maxEntries entries shown)")
        }
        return sb.toString().trimEnd()
    }

    private fun buildTreeRecursive(
        dir: File,
        prefix: String,
        depth: Int,
        maxDepth: Int,
        includeHidden: Boolean,
        maxEntries: Int,
        sb: StringBuilder,
        entryCount: IntArray
    ) {
        if (depth > maxDepth || entryCount[0] >= maxEntries) return

        val children = dir.listFiles()?.sortedBy {
            if (it.isDirectory) "0_${it.name.lowercase()}" else "1_${it.name.lowercase()}"
        } ?: return

        for (child in children) {
            if (entryCount[0] >= maxEntries) return

            val name = child.name
            if (!includeHidden && name.startsWith(".")) continue
            if (child.isDirectory && name in EXCLUDE_DIRS) continue
            if (!child.isDirectory && child.extension.lowercase() in EXCLUDE_EXTENSIONS) continue

            entryCount[0]++

            if (child.isDirectory) {
                sb.append("$prefix$name/\n")
                buildTreeRecursive(child, "$prefix  ", depth + 1, maxDepth, includeHidden, maxEntries, sb, entryCount)
            } else {
                val size = child.length()
                val sizeStr = formatSize(size)
                val lineCount = if (isTextFile(child)) countLines(child) else -1
                val lineStr = if (lineCount >= 0) ", $lineCount lines" else ""
                sb.append("$prefix$name ($sizeStr$lineStr)\n")
            }
        }
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "${bytes}B"
            bytes < 1024 * 1024 -> "${bytes / 1024}KB"
            else -> String.format("%.1fMB", bytes / (1024.0 * 1024.0))
        }
    }

    private fun isTextFile(file: File): Boolean {
        val ext = file.extension.lowercase()
        if (ext in TEXT_EXTENSIONS) return true
        // Files with no extension but common names
        return file.name.lowercase() in setOf("dockerfile", "makefile", "rakefile", "gemfile")
    }

    private fun countLines(file: File): Int {
        return try {
            file.useLines { it.count() }
        } catch (_: Exception) { -1 }
    }
}
