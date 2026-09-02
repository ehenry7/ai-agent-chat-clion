package com.aiagent.chat.tools

object PatchEngine {

    data class UpdateFileChunk(
        val changeContext: String?,
        val oldLines: List<String>,
        val newLines: List<String>,
        val isEndOfFile: Boolean
    )

    sealed interface Hunk {
        data class AddFile(val path: String, val contents: String) : Hunk
        data class DeleteFile(val path: String) : Hunk
        data class UpdateFile(val path: String, val movePath: String?, val chunks: List<UpdateFileChunk>) : Hunk
    }

    data class FileChange(
        val type: String, // "add", "delete", "update"
        val path: String,
        val movePath: String? = null,
        val newContent: String? = null
    )

    fun parsePatch(patchText: String): List<Hunk> {
        val lines = patchText.trim().split(Regex("\\r?\\n"))
        val hunks = mutableListOf<Hunk>()
        var i = 0

        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("*** Add File: ")) {
                val path = line.substring("*** Add File: ".length).trim()
                i++
                val contentLines = mutableListOf<String>()
                while (i < lines.size && !lines[i].startsWith("***")) {
                    if (lines[i].startsWith("+")) {
                        contentLines.add(lines[i].substring(1))
                    }
                    i++
                }
                hunks.add(Hunk.AddFile(path, contentLines.joinToString("\n")))
            } else if (line.startsWith("*** Delete File: ")) {
                val path = line.substring("*** Delete File: ".length).trim()
                hunks.add(Hunk.DeleteFile(path))
                i++
            } else if (line.startsWith("*** Update File: ")) {
                val path = line.substring("*** Update File: ".length).trim()
                i++
                var movePath: String? = null
                if (i < lines.size && lines[i].startsWith("*** Move to: ")) {
                    movePath = lines[i].substring("*** Move to: ".length).trim()
                    i++
                }
                val chunks = mutableListOf<UpdateFileChunk>()
                while (i < lines.size && !lines[i].startsWith("*** Add File:") && !lines[i].startsWith("*** Delete File:") && !lines[i].startsWith("*** Update File:")) {
                    if (lines[i].startsWith("@@")) {
                        val ctx = lines[i].substring(2).trim().ifEmpty { null }
                        i++
                        val oldL = mutableListOf<String>()
                        val newL = mutableListOf<String>()
                        var eof = false
                        while (i < lines.size && !lines[i].startsWith("@@") && !lines[i].startsWith("***")) {
                            val l = lines[i]
                            if (l.startsWith(" ")) {
                                oldL.add(l.substring(1))
                                newL.add(l.substring(1))
                            } else if (l.startsWith("-")) {
                                oldL.add(l.substring(1))
                            } else if (l.startsWith("+")) {
                                newL.add(l.substring(1))
                            } else if (l == "*** End of File") {
                                eof = true
                            }
                            i++
                        }
                        chunks.add(UpdateFileChunk(ctx, oldL, newL, eof))
                    } else {
                        i++
                    }
                }
                hunks.add(Hunk.UpdateFile(path, movePath, chunks))
            } else {
                i++
            }
        }
        return hunks
    }

    fun applyChunksToContent(original: String, chunks: List<UpdateFileChunk>): String {
        val lines = original.split(Regex("\\r?\\n")).toMutableList()
        for (chunk in chunks) {
            val searchBlock = chunk.oldLines.joinToString("\n")
            val replaceBlock = chunk.newLines.joinToString("\n")
            val text = lines.joinToString("\n")
            if (searchBlock.isNotEmpty() && text.contains(searchBlock)) {
                val newText = text.replaceFirst(searchBlock, replaceBlock)
                lines.clear()
                lines.addAll(newText.split("\n"))
            } else if (chunk.oldLines.isEmpty()) {
                lines.addAll(chunk.newLines)
            }
        }
        return lines.joinToString("\n")
    }
}
