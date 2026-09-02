package com.aiagent.chat.tools

import kotlin.math.max
import kotlin.math.min

object DiffEngine {

    fun levenshteinDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        var strA = a
        var strB = b
        if (strB.length > strA.length) {
            strA = b
            strB = a
        }

        val prev = IntArray(strB.length + 1) { it }
        val curr = IntArray(strB.length + 1)

        for (i in 1..strA.length) {
            curr[0] = i
            for (j in 1..strB.length) {
                val cost = if (strA[i - 1] == strB[j - 1]) 0 else 1
                curr[j] = min(
                    min(curr[j - 1] + 1, prev[j] + 1),
                    prev[j - 1] + cost
                )
            }
            System.arraycopy(curr, 0, prev, 0, curr.size)
        }
        return prev[strB.length]
    }

    fun normalizeString(str: String): String {
        var s = str
            .replace("\u201C", "\"").replace("\u201D", "\"")
            .replace("\u2018", "'").replace("\u2019", "'")
            .replace("\u2026", "...").replace("\u2014", "-")
            .replace("\u2013", "-").replace("\u00A0", " ")
        s = s.replace(Regex("\\s+"), " ")
        return s.trim()
    }

    fun getSimilarity(original: String, search: String): Double {
        if (search.isEmpty()) return 0.0
        val normOrig = normalizeString(original)
        val normSearch = normalizeString(search)
        if (normOrig == normSearch) return 1.0
        val dist = levenshteinDistance(normOrig, normSearch)
        val maxLen = max(normOrig.length, normSearch.length)
        return 1.0 - (dist.toDouble() / maxLen)
    }

    fun fuzzySearch(lines: List<String>, searchChunk: String, startIndex: Int, endIndex: Int): Triple<Double, Int, String> {
        var bestScore = 0.0
        var bestMatchIndex = -1
        var bestMatchContent = ""
        val searchLen = searchChunk.split(Regex("\\r?\\n")).size

        val midPoint = (startIndex + endIndex) / 2
        var leftIndex = midPoint
        var rightIndex = midPoint + 1

        while (leftIndex >= startIndex || rightIndex <= endIndex - searchLen) {
            if (leftIndex >= startIndex && leftIndex + searchLen <= lines.size) {
                val chunk = lines.subList(leftIndex, leftIndex + searchLen).joinToString("\n")
                val sim = getSimilarity(chunk, searchChunk)
                if (sim > bestScore) {
                    bestScore = sim
                    bestMatchIndex = leftIndex
                    bestMatchContent = chunk
                }
            }
            if (rightIndex <= endIndex - searchLen && rightIndex + searchLen <= lines.size) {
                val chunk = lines.subList(rightIndex, rightIndex + searchLen).joinToString("\n")
                val sim = getSimilarity(chunk, searchChunk)
                if (sim > bestScore) {
                    bestScore = sim
                    bestMatchIndex = rightIndex
                    bestMatchContent = chunk
                }
                rightIndex++
            }
            // Always advance leftIndex to prevent infinite loop when leftIndex + searchLen > lines.size
            if (leftIndex >= startIndex) {
                leftIndex--
            }
            // Break if both pointers are exhausted
            if (leftIndex < startIndex && rightIndex > endIndex - searchLen) {
                break
            }
        }
        return Triple(bestScore, bestMatchIndex, bestMatchContent)
    }

    data class DiffBlock(
        val startLine: Int,
        val searchContent: String,
        val replaceContent: String
    )

    data class DiffResult(
        val success: Boolean,
        val content: String? = null,
        val error: String? = null
    )

    fun applyDiff(originalContent: String, diffContent: String, fuzzyThreshold: Double = 1.0): DiffResult {
        val regex = Regex("(?m)^<<<<<<< SEARCH(?:>)?\\s*\\n(?:(?::start_line:\\s*(\\d+)\\s*\\n))?(?:(?::end_line:\\s*\\d+\\s*\\n))?(?:-------[\\s]*\\n)?([\\s\\S]*?)(?:\\n)?^=======[\\s]*\\n([\\s\\S]*?)(?:\\n)?^>>>>>>> REPLACE")
        val matches = regex.findAll(diffContent).toList()

        if (matches.isEmpty()) {
            return DiffResult(false, error = "Invalid diff format - missing required SEARCH/REPLACE sections.")
        }

        val lineEnding = if (originalContent.contains("\r\n")) "\r\n" else "\n"
        var resultLines = originalContent.split(Regex("\\r?\\n")).toMutableList()

        val replacements = matches.map { m ->
            val startLine = m.groups[1]?.value?.toIntOrNull() ?: 0
            val search = m.groups[2]?.value ?: ""
            val replace = m.groups[3]?.value ?: ""
            DiffBlock(startLine, search, replace)
        }.sortedBy { it.startLine }

        var applied = 0
        for (rep in replacements) {
            val searchLines = rep.searchContent.split(Regex("\\r?\\n"))
            val replaceLines = rep.replaceContent.split(Regex("\\r?\\n"))
            val searchChunk = searchLines.joinToString("\n")

            val searchStart = if (rep.startLine > 0) max(0, rep.startLine - 20) else 0
            val searchEnd = if (rep.startLine > 0) min(resultLines.size, rep.startLine + searchLines.size + 20) else resultLines.size

            val (score, matchIdx, _) = fuzzySearch(resultLines, searchChunk, searchStart, searchEnd)
            if (matchIdx == -1 || score < fuzzyThreshold) {
                return DiffResult(false, error = "No sufficiently similar match found for search block (${(score * 100).toInt()}% similar).")
            }

            val before = resultLines.subList(0, matchIdx)
            val after = resultLines.subList(matchIdx + searchLines.size, resultLines.size)
            resultLines = (before + replaceLines + after).toMutableList()
            applied++
        }

        return DiffResult(true, content = resultLines.joinToString(lineEnding))
    }
}
