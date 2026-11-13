package com.dshm.bot.ui.chat

import com.dshm.bot.data.remote.isArtifactKindPath
import com.dshm.bot.data.remote.presentedByTurn
import com.dshm.bot.data.remote.resolveSessionPath
import com.dshm.bot.data.remote.fileNameOf
import com.dshm.bot.data.remote.fileExtensionOf
import org.json.JSONObject

/**
 * 一轮产物的来源（对齐 dsh-Go `ArtifactOrigin`）。
 */
enum class ArtifactOrigin {
    /** `present` 工具声明的交付物（官方 deliverables）。 */
    DELIVERED,

    /** 本轮 `write` / `str_replace_editor create` 建的文件。 */
    WRITTEN,

    /** 本轮最终回复里链接的、且被本轮工具调用/结果提到过的文件。 */
    LINKED,

    /** 本轮时间窗内出现在输出目录里的文件（我们这里是会话工作区）。 */
    OUTPUT,
}

/** 一个「给用户做的文件」。 */
data class DerivedArtifact(
    val path: String,
    val origin: ArtifactOrigin,
    val turn: Int,
    val description: String? = null,
    val seq: Long? = null,
    val bytes: Long? = null,
)

/**
 * 一轮的产物，卡贴在 [seq] 处。
 *
 * [seq] = 轮已结束就是 `turn/end` 的 seq（收尾文字排在卡上面，对齐网页版）；
 * 轮还在跑但本轮已经声明过交付时，先取 `present` 的 seq —— 卡片因此**不必等整轮跑完**，
 * `turn/end` 到了再前进到轮末。
 */
data class TurnArtifacts(
    val turn: Int,
    val seq: Long,
    val files: List<DerivedArtifact>,
) {
    val delivered: Boolean get() = files.isNotEmpty() && files.first().origin == ArtifactOrigin.DELIVERED
}

/** 一轮的边界（`turn/start` → `turn/end`）。 */
data class TurnTrace(
    val turn: Int,
    val startSeq: Long,
    val startMs: Long,
    val endSeq: Long? = null,
    val endMs: Long? = null,
    val running: Boolean = false,
)

/** 输出目录里扫到的文件（工作区兜底用）。 */
data class OutputFile(
    val path: String,
    val modifiedMs: Long,
    val bytes: Long? = null,
)

private class ToolCall(
    val name: String,
    val arguments: JSONObject?,
)

private class TurnScan {
    val calls = HashMap<String, ToolCall>()
    val results = ArrayList<String>()
    val written = ArrayList<Pair<String, Long>>()
    var reply: String? = null

    /** 本轮的调用参数或结果里有没有提到这个文件名（只对回复里链接的文件查）。 */
    fun names(name: String): Boolean =
        calls.values.any { it.arguments?.toString()?.contains(name) == true } ||
            results.any { it.contains(name) }
}

/** 从会话事件里还原每一轮的边界。 */
fun turnTraces(events: List<JSONObject>): List<TurnTrace> {
    val order = LinkedHashMap<Int, TurnTrace>()
    for (event in events) {
        val data = event.optJSONObject("data") ?: continue
        val seq = event.optLong("seq", -1L).takeIf { it >= 0L } ?: continue
        val time = event.optLong("time", 0L)
        when (event.optString("type")) {
            "turn/start" -> {
                val turn = data.optInt("turn", -1)
                if (turn <= 0) continue
                val previous = order[turn]
                order[turn] = TurnTrace(
                    turn = turn,
                    startSeq = previous?.startSeq?.takeIf { it < seq } ?: seq,
                    startMs = previous?.startMs?.takeIf { it > 0L } ?: time,
                    endSeq = previous?.endSeq,
                    endMs = previous?.endMs,
                    running = true,
                )
            }

            "turn/end" -> {
                val turn = data.optInt("turn", -1)
                if (turn <= 0) continue
                val previous = order[turn]
                order[turn] = TurnTrace(
                    turn = turn,
                    startSeq = previous?.startSeq ?: seq,
                    startMs = previous?.startMs ?: time,
                    endSeq = seq,
                    endMs = time,
                    running = false,
                )
            }
        }
    }
    return order.values.sortedBy { it.startSeq }
}

/**
 * 一轮的产物（对齐 dsh-Go `deriveArtifacts`）。
 *
 * 出卡条件：**已完成的轮**，或者**本轮已经声明过交付**（`present`）的轮 —— 后者让卡片
 * 在 `present` 一到就出现，不必等整轮跑完（模型交完文件常常还会接着写收尾文字 / 等子代理）。
 * 有 `present` 声明就只用它；否则依次看本轮新建的文件（工具结果里的 `meta.path`）、
 * 最终回复里链接且被本轮提到过的文件、以及本轮时间窗内出现在输出目录里的文件。
 * 卡的位置：轮已结束 = `turn/end` 的 seq（对齐网页版，收尾文字在卡上面）；轮还在跑 = `present` 的 seq。
 */
fun deriveArtifacts(
    events: List<JSONObject>,
    turns: List<TurnTrace>,
    cwd: String?,
    outputs: List<OutputFile> = emptyList(),
): List<TurnArtifacts> {
    val delivered = presentedByTurn(events)
    val eligible = LinkedHashMap<Int, TurnTrace>()
    for (trace in turns) {
        val finished = !trace.running && trace.endSeq != null
        val declared = delivered.containsKey(trace.turn)
        if (finished || declared) eligible[trace.turn] = trace
    }
    if (eligible.isEmpty()) return emptyList()

    val scans = HashMap<Int, TurnScan>()
    for (event in events) {
        val type = event.optString("type")
        val data = event.optJSONObject("data") ?: continue
        val seq = event.optLong("seq", -1L).takeIf { it >= 0L } ?: continue
        val turn = data.optInt("turn", -1)
        if (!eligible.containsKey(turn)) continue
        val scan = scans.getOrPut(turn) { TurnScan() }
        when (type) {
            "tool/call" -> {
                val callId = callIdOf(data) ?: continue
                scan.calls[callId] = ToolCall(
                    name = data.optString("name"),
                    arguments = toolArguments(data),
                )
            }

            "tool/result" -> {
                if (isErrorResult(data)) continue
                val callId = callIdOf(data) ?: continue
                val text = textContent(data.optJSONObject("message")?.opt("content"))
                scan.results += text
                val call = scan.calls[callId] ?: continue
                val created = createdPath(call, data.optJSONObject("meta"), cwd) ?: continue
                scan.written += created to seq
            }

            "assistant/message" -> {
                val content = data.optJSONObject("message")?.optJSONArray("content") ?: continue
                val text = buildString {
                    for (index in 0 until content.length()) {
                        val block = content.optJSONObject(index) ?: continue
                        if (block.optString("type") == "text") append(block.optString("text"))
                    }
                }.trim()
                if (text.isNotEmpty()) scan.reply = text
            }
        }
    }

    val sortedOutputs = outputs.sortedBy { it.modifiedMs }
    val out = ArrayList<TurnArtifacts>(eligible.size)
    for (trace in eligible.values.sortedBy { it.endSeq ?: Long.MAX_VALUE }) {
        val turn = trace.turn
        val files = LinkedHashMap<String, DerivedArtifact>()
        val declared = delivered[turn]
        if (declared != null) {
            for (file in declared) {
                val path = resolveSessionPath(file.path, cwd)
                files[path] = DerivedArtifact(
                    path = path,
                    origin = ArtifactOrigin.DELIVERED,
                    turn = turn,
                    description = file.description?.trim()?.takeIf { it.isNotEmpty() },
                    seq = file.seq,
                )
            }
        } else {
            val scan = scans[turn]
            if (scan != null) {
                for ((path, seq) in scan.written) {
                    if (!isArtifactKindPath(path)) continue
                    files.putIfAbsent(
                        path,
                        DerivedArtifact(path, ArtifactOrigin.WRITTEN, turn, seq = seq),
                    )
                }
                val reply = scan.reply
                if (reply != null) {
                    for (path in linkedPaths(reply, cwd)) {
                        if (!isArtifactKindPath(path) || !scan.names(fileNameOf(path))) continue
                        files.putIfAbsent(path, DerivedArtifact(path, ArtifactOrigin.LINKED, turn))
                    }
                }
            }
            // 输出目录兜底：本轮时间窗内出现的文件（前后各留一点余量，对齐 dsh-Go）。
            val from = trace.startMs.takeIf { it > 0L }?.minus(1_000L) ?: Long.MIN_VALUE
            val to = (trace.endMs ?: trace.startMs).takeIf { it > 0L }?.plus(2_000L) ?: Long.MAX_VALUE
            for (output in sortedOutputs) {
                if (output.modifiedMs < from || output.modifiedMs > to) continue
                files.putIfAbsent(
                    output.path,
                    DerivedArtifact(output.path, ArtifactOrigin.OUTPUT, turn, bytes = output.bytes),
                )
            }
        }
        if (files.isEmpty()) continue
        // 卡的位置：轮已结束 → 贴 `turn/end`（收尾文字在卡上面，对齐网页版）；
        // 轮还在跑（本轮已声明交付）→ 先贴 `present` 的 seq，`turn/end` 到了再前进。
        val seq = trace.endSeq ?: declared?.firstOrNull()?.seq ?: trace.startSeq
        out += TurnArtifacts(turn = turn, seq = seq, files = files.values.toList())
    }
    return out
}

/**
 * 成功的 `write` / `str_replace_editor create` 建出来的文件。
 *
 * 结果里的绝对 `meta.path` 优先于参数（对齐 dsh-Go `_createdPath`）；
 * 编辑 / 覆盖不算新建。
 */
private fun createdPath(call: ToolCall, meta: JSONObject?, cwd: String?): String? {
    val arguments = call.arguments
    when (call.name) {
        "write" -> if (meta?.optString("operation") != "create") return null
        "str_replace_editor" -> if (arguments?.optString("command") != "create") return null
        else -> return null
    }
    val recorded = meta?.optString("path").orEmpty()
    if (recorded.startsWith("/")) return resolveSessionPath(recorded)
    val argument = arguments?.optString("file_path").orEmpty()
        .ifEmpty { arguments?.optString("path").orEmpty() }
    if (argument.isBlank()) return null
    return resolveSessionPath(argument, cwd)
}

private val LINK_TARGET = Regex("""\]\(\s*(?:<([^>\n]+)>|([^)\s]+))""")
private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
private val WINDOWS_DRIVE = Regex("^[A-Za-z]:[\\\\/]")

/** 本地 markdown 链接目标 → 绝对路径；网络链接、锚点、scheme 一律丢掉。 */
internal fun resolveWorkspaceLink(url: String, baseDir: String? = null): String? {
    var target = url.trim().let {
        if (it.startsWith("<") && it.endsWith(">")) it.substring(1, it.length - 1).trim() else it
    }
    if (target.isEmpty() || target.startsWith("#") || target.startsWith("?") || target.startsWith("//")) {
        return null
    }
    val schemeMatch = SCHEME.find(target)
    if (schemeMatch != null) {
        val scheme = schemeMatch.value.dropLast(1).lowercase()
        if (scheme != "file") return null
        target = target.removePrefix(schemeMatch.value)
        if (target.startsWith("//")) return null
    }
    val decoded = runCatching {
        java.net.URLDecoder.decode(target.substringBefore('#').substringBefore('?'), "UTF-8")
    }.getOrNull() ?: return null
    if (decoded.isEmpty() || SCHEME.containsMatchIn(decoded)) return null
    if (!decoded.startsWith("/") && baseDir != null && baseDir.isNotEmpty() && !baseDir.startsWith("/")) {
        return "$baseDir/$decoded"
    }
    return resolveSessionPath(decoded, cwd = baseDir)
}

/** 回复里链接到的本地文件（`[a](<out/a.pdf>)`、`![b](chart.png)`），按出现顺序。 */
internal fun linkedPaths(text: String, cwd: String?): List<String> {
    val out = LinkedHashSet<String>()
    for (match in LINK_TARGET.findAll(text)) {
        var target = (match.groupValues[1].ifEmpty { match.groupValues[2] }).trim()
        if (target.isEmpty()) continue
        val path = if (WINDOWS_DRIVE.containsMatchIn(target)) {
            val hash = target.indexOf('#')
            if (hash >= 0) target = target.substring(0, hash)
            if (match.groupValues[1].isEmpty()) {
                target = runCatching { java.net.URLDecoder.decode(target, "UTF-8") }.getOrDefault(target)
            }
            resolveSessionPath(target, cwd)
        } else {
            resolveWorkspaceLink(target, baseDir = cwd) ?: continue
        }
        out += path
    }
    return out.toList()
}

private fun callIdOf(data: JSONObject): String? =
    data.optString("callId").ifEmpty {
        data.optJSONObject("message")?.optString("toolCallId").orEmpty()
    }.ifEmpty { data.optString("toolCallId") }.takeIf { it.isNotEmpty() }

private fun isErrorResult(data: JSONObject): Boolean =
    data.optBoolean("isError") ||
        data.optBoolean("error") ||
        data.optJSONObject("message")?.optBoolean("isError") == true

private fun toolArguments(data: JSONObject): JSONObject? =
    data.optJSONObject("arguments")
        ?: runCatching { JSONObject(data.optString("arguments")) }.getOrNull()

private fun textContent(value: Any?): String {
    val array = value as? org.json.JSONArray ?: return (value as? String).orEmpty()
    return buildString {
        for (index in 0 until array.length()) {
            val block = array.optJSONObject(index) ?: continue
            if (block.optString("type") == "text") append(block.optString("text"))
        }
    }
}

/** 扩展名（给 UI 的图标用）。 */
internal fun artifactExtensionOf(path: String): String = fileExtensionOf(path)
