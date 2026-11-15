package com.dshm.bot.ui.chat

import com.dshm.bot.data.remote.SessionEvent
import org.json.JSONArray
import org.json.JSONObject

/**
 * 四期 4b-2：客户端从**事件日志**算出「这一轮改了哪些文件、改成什么样」。
 *
 * 数据源与 dsh-go 完全一致（`dsh-go/app/lib/ui/chat/tool_view.dart` +
 * `turn_summary.dart`），三级降级：
 * 1. 工具结果的 `meta.diffs` —— DSH 自己算好的 old/new 文本块（最准）；
 * 2. 工具参数里的 old/new（`edit` 的 old_string/new_string、
 *    `str_replace_editor` 的 old_str/new_str）；
 * 3. `write`/`str_replace_editor create` 的正文 —— 整篇算新增。
 *
 * 我们的 4a 事件日志**完整转发了 tool/call 与 tool/result 的 data（含 meta）**，
 * 所以这一切都在客户端算，不需要宿主再开端点 —— 事件日志仍是唯一事实源。
 */
enum class DiffLineKind { CONTEXT, ADD, DELETE }

/** diff 的一行（文本自带前导符号，与 dsh-go 同形）。 */
data class DiffLine(val kind: DiffLineKind, val text: String)

/** 一处改动：行级 diff + 新旧文件里的起始行号（算不出时为 null）。 */
data class DiffHunk(val lines: List<DiffLine>, val oldStart: Int?, val newStart: Int?)

/** 一个文件的改动（一个文件一次改动合并成一条，可有多个 hunk）。 */
data class FileChange(
    val path: String,
    /** 这次改动是新建（整篇都是新增行）。 */
    val created: Boolean,
    val added: Int,
    val removed: Int,
    val hunks: List<DiffHunk>,
)

/** 行级 diff（dsh-go `computeLineDiff` 的 Kotlin 版：LCS DP，超大块整体删+增）。 */
fun computeLineDiff(oldText: String, newText: String): List<DiffLine> {
    val a = if (oldText.isEmpty()) emptyList() else oldText.split("\n")
    val b = if (newText.isEmpty()) emptyList() else newText.split("\n")
    if (a.size * b.size > 400 * 400) {
        // 太大就不算了：整篇删掉再整篇加上（与 dsh-go 同一取舍）。
        return a.map { DiffLine(DiffLineKind.DELETE, "-$it") } + b.map { DiffLine(DiffLineKind.ADD, "+$it") }
    }
    // dp[i][j] = a[i..] 与 b[j..] 的最长公共子序列长度。
    val dp = Array(a.size + 1) { IntArray(b.size + 1) }
    for (i in a.indices.reversed()) {
        for (j in b.indices.reversed()) {
            dp[i][j] = if (a[i] == b[j]) {
                dp[i + 1][j + 1] + 1
            } else {
                maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }
    }
    val lines = ArrayList<DiffLine>(a.size + b.size)
    var i = 0
    var j = 0
    while (i < a.size && j < b.size) {
        when {
            a[i] == b[j] -> {
                lines += DiffLine(DiffLineKind.CONTEXT, " ${a[i]}")
                i++
                j++
            }

            dp[i + 1][j] >= dp[i][j + 1] -> {
                lines += DiffLine(DiffLineKind.DELETE, "-${a[i]}")
                i++
            }

            else -> {
                lines += DiffLine(DiffLineKind.ADD, "+${b[j]}")
                j++
            }
        }
    }
    while (i < a.size) {
        lines += DiffLine(DiffLineKind.DELETE, "-${a[i]}")
        i++
    }
    while (j < b.size) {
        lines += DiffLine(DiffLineKind.ADD, "+${b[j]}")
        j++
    }
    return lines
}

/** 整篇算新增（dsh-go `_allAdded`）。 */
private fun allAdded(text: String): List<DiffLine> {
    if (text.isEmpty()) return emptyList()
    val body = if (text.endsWith("\n")) text.dropLast(1) else text
    return body.split("\n").map { DiffLine(DiffLineKind.ADD, "+$it") }
}

/**
 * 文件的「已知内容」：用来给改动定位行号（dsh-go `_KnownLines`）。
 * 只在**能确认**文件当前内容时才有（整篇写入之后）。
 */
private class KnownLines(val start: Int, val lines: MutableList<String>) {
    /** [needle] 在已知内容里唯一出现的下标；不唯一或找不到返回 null。 */
    fun find(needle: List<String>, from: Int = 0): Int? {
        if (needle.isEmpty() || needle.size > lines.size) return null
        var found: Int? = null
        var index = from
        while (index + needle.size <= lines.size) {
            var matched = true
            for (offset in needle.indices) {
                if (lines[index + offset] != needle[offset]) {
                    matched = false
                    break
                }
            }
            if (matched) {
                if (found != null) return null
                found = index
            }
            index++
        }
        return found
    }
}

/** 一处 old→new 文本块。 */
private data class ChangeBlock(val oldText: String?, val newText: String)

/**
 * 从一段**连续**的 durable 事件里，算出这一轮改了哪些文件。
 *
 * 口径与 dsh-go 一致：只认 `write` / `edit` / `str_replace_editor` 三个工具，
 * 同一文件的多次改动合并成一条 [FileChange]。
 */
fun changesFromEvents(events: List<SessionEvent>): List<FileChange> {
    // 工具调用（按 callId 索引）与其结果。
    val calls = LinkedHashMap<String, Pair<String, JSONObject>>()
    val results = LinkedHashMap<String, JSONObject>()
    for (event in events) {
        val data = event.data ?: continue
        when (event.type) {
            "tool/call" -> {
                val callId = data.optString("callId")
                if (callId.isEmpty()) continue
                calls[callId] = data.optString("name") to data
            }

            "tool/result" -> {
                val message = data.optJSONObject("message") ?: continue
                val callId = message.optString("toolCallId").ifEmpty { data.optString("callId") }
                if (callId.isEmpty()) continue
                results[callId] = data
            }
        }
    }
    if (calls.isEmpty()) return emptyList()

    // 已知内容（文件 → 行号与行），用来给改动算行号。
    val known = LinkedHashMap<String, KnownLines>()
    // 每个文件的改动块。
    val byFile = LinkedHashMap<String, MutableList<DiffHunk>>()
    val createdFiles = LinkedHashSet<String>()

    for ((callId, call) in calls) {
        val name = call.first
        val args = argumentsOf(call.second)
        val meta = results[callId]?.optJSONObject("meta")
        val path = pathOf(name, args, meta) ?: continue
        if (name !in DIFF_TOOLS) continue
        val result = results[callId]
        // 失败的结果没有改动。
        val failed = result?.optBoolean("isError", false) == true ||
            result?.optJSONObject("message")?.optBoolean("isError", false) == true
        if (failed) continue

        val created = meta?.optString("operation") == "create" ||
            (name == "str_replace_editor" && args.optString("command") == "create")
        if (created) createdFiles += path

        val hunks = hunksOf(name, args, meta, path, created, known)
        if (hunks.isEmpty()) continue
        byFile.getOrPut(path) { ArrayList() } += hunks
    }

    return byFile.map { (path, hunks) ->
        var added = 0
        var removed = 0
        for (hunk in hunks) {
            for (line in hunk.lines) {
                when (line.kind) {
                    DiffLineKind.ADD -> added++
                    DiffLineKind.DELETE -> removed++
                    DiffLineKind.CONTEXT -> Unit
                }
            }
        }
        FileChange(
            path = path,
            created = path in createdFiles,
            added = added,
            removed = removed,
            hunks = hunks,
        )
    }
}

/** 三个会改文件的工具。 */
private val DIFF_TOOLS = setOf("write", "edit", "str_replace_editor")

/** 工具参数：可能是 JSON 对象，也可能是**字符串**（部分模型把 arguments 序列化成字符串）。 */
private fun argumentsOf(call: JSONObject): JSONObject {
    val raw = call.opt("arguments")
    if (raw is JSONObject) return raw
    if (raw is String && raw.isNotBlank()) {
        return runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
    }
    return JSONObject()
}

/** 改动的文件路径。 */
private fun pathOf(name: String, args: JSONObject, meta: JSONObject?): String? {
    if (name == "str_replace_editor") return args.optString("path").takeIf { it.isNotEmpty() }
    val fromArgs = args.optString("file_path")
    if (fromArgs.isNotEmpty()) return fromArgs
    val fromMeta = meta?.optString("path") ?: ""
    return fromMeta.takeIf { it.isNotEmpty() }
}

/** 这一处改动的 hunk（行号能算就算，算不出就给 null）。 */
private fun hunksOf(
    name: String,
    args: JSONObject,
    meta: JSONObject?,
    path: String,
    created: Boolean,
    known: MutableMap<String, KnownLines>,
): List<DiffHunk> {
    // 1) DSH 算好的 diffs（最准）。
    val blocks = changeBlocksOf(name, args, meta)
    if (blocks.isNotEmpty()) {
        val out = ArrayList<DiffHunk>(blocks.size)
        val state = known[path]
        var delta = 0
        for (block in blocks) {
            val lines = computeLineDiff(block.oldText.orEmpty(), block.newText)
            val oldLines = block.oldText?.split("\n") ?: emptyList()
            val at = state?.find(oldLines)
            if (state != null && at != null) {
                val newLines = block.newText.split("\n")
                for (index in 0 until minOf(newLines.size, state.lines.size - at)) {
                    state.lines[at + index] = newLines[index]
                }
                val newStart = state.start + at
                out += DiffHunk(lines = lines, oldStart = newStart - delta, newStart = newStart)
                delta += newLines.size - oldLines.size
            } else {
                out += DiffHunk(lines = lines, oldStart = null, newStart = null)
            }
        }
        return out
    }
    // 2) str_replace_editor 的 insert：按插入位置算。
    if (name == "str_replace_editor" && args.optString("command") == "insert") {
        val text = args.optString("new_str")
        if (text.isEmpty()) return emptyList()
        val line = args.opt("insert_line") as? Int
        val added = allAdded(text)
        // 行号无条件给（insert_line 是 1-based，插入后新行落在它后面），
        // 与 dsh-go 同口径；已知内容存在时顺手把插入同步进去，后面的改动才能继续定位。
        known[path]?.let { state ->
            if (line != null) {
                val at = line - state.start + 1
                if (at in 0..state.lines.size) state.lines.addAll(at, text.split("\n"))
            }
        }
        return listOf(DiffHunk(lines = added, oldStart = null, newStart = line?.plus(1)))
    }
    // 3) 整篇写入：全是新增行。
    val content = when {
        name == "write" -> args.optString("content")
        name == "str_replace_editor" && args.optString("command") == "create" -> args.optString("file_text")
        else -> ""
    }
    if (content.isEmpty()) return emptyList()
    val lines = allAdded(content)
    // 整篇写入之后，文件内容就是这篇 → 后续改动能据此定位行号。
    known[path] = KnownLines(1, content.split("\n").toMutableList())
    return listOf(DiffHunk(lines = lines, oldStart = null, newStart = if (created) 1 else null))
}

/** old→new 文本块：优先结果 meta 里的 `diffs`，其次工具参数。 */
private fun changeBlocksOf(name: String, args: JSONObject, meta: JSONObject?): List<ChangeBlock> {
    val diffs = meta?.optJSONArray("diffs")
    if (diffs != null && diffs.length() > 0) {
        val out = ArrayList<ChangeBlock>(diffs.length())
        for (index in 0 until diffs.length()) {
            val diff = diffs.optJSONObject(index) ?: continue
            val newText = diff.optString("newText")
            val oldText = if (diff.isNull("oldText")) null else diff.optString("oldText")
            out += ChangeBlock(oldText = oldText, newText = newText)
        }
        if (out.isNotEmpty()) return out
    }
    if (name == "edit") {
        val from = args.optString("old_string")
        val to = args.optString("new_string")
        if (from.isNotEmpty() && to.isNotEmpty()) return listOf(ChangeBlock(oldText = from, newText = to))
    }
    if (name == "str_replace_editor" && args.optString("command") == "str_replace") {
        val from = args.optString("old_str")
        val to = args.optString("new_str")
        if (from.isNotEmpty() && to.isNotEmpty()) return listOf(ChangeBlock(oldText = from, newText = to))
    }
    return emptyList()
}

/** 摘要里的文件行（宿主 `/api/changes.summary` 的形状）。 */
internal fun changesSummaryFileLines(summary: com.dshm.bot.data.remote.ChangesSummary): List<String> =
    summary.files.map { it.display.ifEmpty { it.path } }
