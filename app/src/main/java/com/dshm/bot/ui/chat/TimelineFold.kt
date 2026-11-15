package com.dshm.bot.ui.chat

import com.dshm.bot.data.remote.SessionEvent
import com.dshm.bot.data.remote.TimelineFile
import com.dshm.bot.data.remote.TimelineRow
import com.dshm.bot.data.remote.TimelineRowPayload
import org.json.JSONArray
import org.json.JSONObject

/**
 * 四期 4b：客户端折叠——把**原始 durable 事件**折成客户端已消费的 [TimelineRow]。
 *
 * 这是宿主 `projection.js` 折叠逻辑的客户端实现（参照实现仍是宿主的
 * `displayItems` + `composeTimelineRows`）。为什么最终要搬过来：
 * dsh-Go 的模型是「事件日志 » 纯函数折叠」，显示永远由日志重算；只有把折叠放到
 * 客户端，才能做到「换会话/回前台/断线重连后同一份日志算出同一份显示」。
 *
 * 产出 [TimelineRow]（不是新类型）是刻意的：**下游（映射 → ChatEntry → 渲染）
 * 一行都不用改**，4c 只需要换「行的来源」。
 *
 * 正确性由 oracle 差分保证：`TimelineFoldTest` 读 `fixtures/timeline-fold-oracle.json`
 * （由宿主真实现 `test/gen-display-items.mjs` 生成），逐行比对 key/kind/seq/turn/
 * timeMs/payload——两个独立实现对同一批事件必须完全相等。
 *
 * 口径细节（与宿主逐字对齐，改动前先看 `docs/phase4-event-subscription.md`）：
 * - `user` 行：仅 `source.kind === "user"`；文本 = text 块 `\n` 连接后 trim；
 *   `images` = image 块数；`blocks` = 带 attachment 的 image/file 块名；
 *   `requestId` = `source.rpcId`（本地乐观行的认领键）。
 * - `bot` 行：`assistant/message` **按文本块各出一行**，空白块不出行。
 * - `tool` 行：只有 9 个社交工具成行（`present` 不成行——交付卡是它唯一的表面）。
 * - `event` 行：`llm/retry` → `retry:<seq>`；`turn/end` 且 reason.kind === 'error'
 *   → `turnfail:<seq>`。
 * - `changes` 行：`workspace/changes` → `changes:<seq>`（轻量标记，摘要客户端另外拉）。
 * - `artifact` 行：`deliverables/presented` → `present:<turn>`，贴在**该轮 turn/end**
 *   的 seq 上（轮未闭合不出卡）。
 */
internal object TimelineFold {

    /** 折叠需要的社交工具（与宿主 `TOOL_ROWS` 一致；`present` 不在其中）。 */
    private val TOOL_ROWS = setOf(
        "message_bot", "stop_bot", "create_bot", "update_bot",
        "create_group", "update_group", "delete_group", "post_to_group", "read_group_chat",
    )

    /** 群聊结果里的群名（宿主 `GROUP_RESULT_NAME`）。 */
    private val GROUP_RESULT_NAME = Regex("^(?:Created group|Updated|Nothing changed in|Deleted|Posted in) \"([^\"]+)\"")

    /**
     * 折叠一段**连续**的 durable 事件为行（升序；同 seq 时事件行在前、产物卡在后）。
     *
     * @param exchangeTurns 被隐藏的轮号（Bot 之间互答的轮，宿主 `state.exchangeTurns`）。
     * @param cwd 工作目录（产物卡的相对路径要拼它）。
     * @param botName 本对话 Bot 的名字（`update_bot` 行的兜底文案）。
     * @param resolveBot Bot id → 名字（`message_bot` 等行的目标名）。
     */
    fun fold(
        events: List<SessionEvent>,
        exchangeTurns: Set<Int> = emptySet(),
        cwd: String? = null,
        botName: String? = null,
        resolveBot: (String) -> String? = { null },
    ): List<TimelineRow> {
        val hidden = exchangeTurns
        val rows = ArrayList<TimelineRow>()
        var turn: Int? = null
        val toolCalls = LinkedHashMap<String, ToolCall>()
        val closingOf = HashMap<Int, Long>()
        val presented = presentedByTurn(events)
        val traces = turnTracesOf(events)
        // produced 口径的扫描：每轮一份（宿主 `scans`）。
        val scans = HashMap<Int, TurnScan>()

        for (event in events) {
            val data = event.data
            // 扫描只统计**已闭合且未隐藏**的轮（宿主 `eligible`）。
            val eventTurn = data?.optInt("turn", 0) ?: 0
            val eligible = traces.any { it.turn == eventTurn && it.endSeq != null && it.turn !in hidden }
            when (event.type) {
                "turn/start" -> {
                    val next = data?.optInt("turn", 0) ?: 0
                    if (next > 0) turn = next
                }

                "user/message" -> {
                    val source = data?.optJSONObject("source")
                    if (source?.optString("kind") != "user") continue
                    val content = data?.optJSONArray("content")
                    rows += userRow(event, source, content, turn)
                }

                "assistant/message" -> {
                    val own = data?.optInt("turn", 0) ?: 0
                    if (own > 0 && own in hidden) continue
                    // 闭合那一句：text 非空的 assistant 消息里 seq 最大的一条。
                    if (textContentOf(data?.optJSONObject("message")?.optJSONArray("content")).isNotEmpty()) {
                        closingOf[own] = maxOf(closingOf[own] ?: Long.MIN_VALUE, event.seq)
                    }
                    val content = data?.optJSONObject("message")?.optJSONArray("content") ?: continue
                    for (index in 0 until content.length()) {
                        val block = content.optJSONObject(index) ?: continue
                        if (block.optString("type") != "text") continue
                        val text = block.optString("text")
                        if (text.isBlank()) continue
                        rows += TimelineRow(
                            key = "bot:${event.seq}",
                            kind = TimelineRow.KIND_BOT,
                            seq = event.seq,
                            turn = turn,
                            timeMs = event.timeMs,
                            payload = TimelineRowPayload.Bot(
                                text = text,
                                author = null,
                                botId = null,
                                durationMs = null,
                                whole = null,
                                live = false,
                            ),
                        )
                    }
                }

                "tool/call" -> {
                    val name = data?.optString("name") ?: ""
                    val callId = data?.optString("callId") ?: ""
                    val callTurn = data?.optInt("turn", 0) ?: 0
                    // produced 口径的扫描：**不受工具白名单限制**（宿主 `artifactRowsOf`
                    // 独立扫所有 tool/call；`write`/`edit` 不成工具行，但写的文件上卡）。
                    if (eligible && callId.isNotEmpty()) {
                        val scan = scans.getOrPut(eventTurn) { TurnScan() }
                        scan.calls[callId] = ScannedCall(
                            name = name,
                            argumentPath = argumentPathOf(name, data?.optJSONObject("arguments")),
                        )
                    }
                    if (name !in TOOL_ROWS) continue
                    if (callId.isEmpty()) continue
                    if (callTurn > 0 && callTurn in hidden) continue
                    toolCalls[callId] = ToolCall(
                        name = name,
                        args = data?.optJSONObject("arguments"),
                        seq = event.seq,
                        turn = if (callTurn > 0) callTurn else null,
                    )
                }

                "tool/result" -> {
                    val message = data?.optJSONObject("message")
                    val callId = message?.optString("toolCallId")?.takeIf { it.isNotEmpty() }
                        ?: data?.optString("callId").orEmpty()
                    val isError = data?.optBoolean("isError", false) == true ||
                        message?.optBoolean("isError", false) == true ||
                        data?.has("error") == true
                    // produced 口径：结果里的绝对路径优先（宿主 `resultPathOf`）。
                    // 同样**不受工具白名单限制**。
                    if (eligible && !isError) {
                        val scan = scans.getOrPut(eventTurn) { TurnScan() }
                        val scanned = scan.calls[callId]
                        val path = if (scanned?.argumentPath != null) {
                            resultPathOf(scanned.argumentPath, data?.optJSONObject("meta"), cwd)
                        } else {
                            null
                        }
                        if (path != null) scan.written += Written(path = path, seq = event.seq)
                    }
                    val pending = toolCalls[callId]
                    if (pending == null) continue
                    if (pending.turn != null && pending.turn in hidden) continue
                    val raw = firstNonBlank(pending.args, "to", "bot", "name", "group")
                    val result = textContentOf(message?.optJSONArray("content"))
                    val label = toolLabel(pending.name, raw, result, isError, botName, resolveBot)
                    val target = toolTarget(pending.name, raw, result, isError, botName, resolveBot)
                    rows += TimelineRow(
                        key = "tool:${event.seq}",
                        kind = TimelineRow.KIND_TOOL,
                        seq = event.seq,
                        turn = pending.turn,
                        timeMs = event.timeMs,
                        payload = TimelineRowPayload.Tool(
                            tool = pending.name,
                            state = if (isError) "error" else "ok",
                            label = label,
                            target = target,
                            callId = callId,
                        ),
                    )
                }

                "llm/retry" -> {
                    val eventTurn = data?.optInt("turn", 0) ?: 0
                    if (eventTurn > 0 && eventTurn in hidden) continue
                    rows += retryRow(event, data, eventTurn, botName)
                }

                "turn/end" -> {
                    val eventTurn = data?.optInt("turn", 0) ?: 0
                    if (eventTurn > 0 && eventTurn in hidden) continue
                    val reason = data?.optJSONObject("reason")
                    if (reason?.optString("kind") != "error") continue
                    rows += turnFailRow(event, reason, eventTurn, botName)
                }

                "workspace/changes" -> {
                    val eventTurn = data?.optInt("turn", 0) ?: 0
                    if (eventTurn > 0 && eventTurn in hidden) continue
                    rows += TimelineRow(
                        key = "changes:${event.seq}",
                        kind = TimelineRow.KIND_CHANGES,
                        seq = event.seq,
                        turn = eventTurn,
                        timeMs = event.timeMs,
                        // 载荷是**空**的（摘要不在事件里，客户端按 (sessionId, seq)
                        // 另外拉 `/api/changes.summary`）——与宿主同口径。
                        payload = TimelineRowPayload.Changes(turn = eventTurn, raw = JSONObject()),
                    )
                }
            }
        }

        // 交付卡：贴在**该轮 turn/end** 的 seq 上（轮没闭合不出卡）。
        //
        // 两种口径（宿主 `artifactRowsOf`）：
        // - **declared**（该轮有 `deliverables/presented`）：只取声明的文件，
        //   且**闭合那一句之后**的声明不上卡（`file.seq >= closingSeq` 跳过）；
        // - **produced**（该轮没有声明）：扫工具写过的文件（write/edit/str_replace_editor），
        //   **闭合那一句之后**写的不上卡（`written.seq > closingSeq` 跳过）。
        //   ⚠️ 两个口径的边界符号不同（`>=` vs `>`），是宿主的原样，逐字保持一致。
        for (trace in traces) {
            if (trace.endSeq == null) continue
            if (trace.turn in hidden) continue
            val closingSeq = closingOf[trace.turn] ?: trace.endSeq
            val declared = presented[trace.turn]
            val files = LinkedHashMap<String, TimelineFile>()
            if (declared != null) {
                for (file in declared.values) {
                    if (file.seq >= closingSeq) continue
                    val path = resolveSessionPath(file.path, cwd)
                    files[path] = TimelineFile(path = path, description = file.description)
                }
            } else {
                val scan = scans[trace.turn]
                if (scan != null) {
                    for (written in scan.written) {
                        if (written.seq > closingSeq || files.containsKey(written.path)) continue
                        files[written.path] = TimelineFile(path = written.path, description = null)
                    }
                    // 宿主还有一条「从回复里的 markdown 链接补文件」的分支，但它读的
                    // `scan.reply` **从未被赋值**（恒为空串），实际是死分支 —— 逐字保持
                    // 一致（不补链接文件），否则与宿主的 oracle 差分会失真。
                }
            }
            if (files.isEmpty()) continue
            rows += TimelineRow(
                key = "present:${trace.turn}",
                kind = TimelineRow.KIND_ARTIFACT,
                seq = trace.endSeq,
                turn = trace.turn,
                timeMs = null,
                payload = TimelineRowPayload.Artifact(
                    files = files.values.toList(),
                    delivered = declared != null,
                    turn = trace.turn,
                ),
            )
        }

        // 排序：有 seq 的行按 seq 升序；同 seq 时事件行在前、产物卡在后（宿主同口径）。
        return rows
            .mapIndexed { index, entry -> index to entry }
            .sortedWith(
                compareBy<Pair<Int, TimelineRow>> { it.second.seq ?: Long.MAX_VALUE }
                    .thenBy { if (it.second.kind == TimelineRow.KIND_ARTIFACT) 1 else 0 }
                    .thenBy { it.first },
            )
            .map { it.second }
    }

    private data class ToolCall(
        val name: String,
        val args: JSONObject?,
        val seq: Long,
        val turn: Int?,
    )

    /** produced 口径的每轮扫描（宿主 `scans`）。 */
    private class TurnScan {
        val calls = LinkedHashMap<String, ScannedCall>()
        val written = ArrayList<Written>()
    }

    private data class ScannedCall(
        val name: String,
        /** 工具参数里的文件路径（write/edit/str_replace_editor 才有）。 */
        val argumentPath: String?,
    )

    private data class Written(val path: String, val seq: Long)

    /**
     * 宿主 `argumentPathOf`：写文件类工具的参数里的路径。
     * 只有 `write`/`edit`/`str_replace_editor` 产出路径，其它工具为 null。
     */
    private fun argumentPathOf(name: String, args: JSONObject?): String? {
        if (args == null) return null
        return when (name) {
            "write", "edit" -> args.optString("file_path", "").takeIf { it.isNotBlank() }
            "str_replace_editor" -> when (args.optString("command", "")) {
                "create" -> if (args.has("file_text")) args.optString("path", "") else null
                "str_replace" -> if (args.optString("old_str", "").isNotEmpty()) args.optString("path", "") else null
                "insert" -> {
                    val line = args.opt("insert_line")
                    if (line is Number && line.toDouble() == Math.floor(line.toDouble()) && line.toDouble() >= 0) {
                        args.optString("path", "")
                    } else {
                        null
                    }
                }

                else -> null
            }

            else -> null
        }
    }

    /**
     * 宿主 `resultPathOf`：结果里的绝对 `meta.path` 优先；否则用参数路径按 cwd 解析，
     * 解析后必须仍是绝对路径。
     */
    private fun resultPathOf(argumentPath: String?, meta: JSONObject?, cwd: String?): String? {
        val metaPath = meta?.optString("path", "") ?: ""
        if (metaPath.startsWith("/")) return metaPath
        if (argumentPath == null) return null
        val resolved = resolveSessionPath(argumentPath, cwd)
        return if (resolved.startsWith("/")) resolved else null
    }

    private data class PresentedFile(
        val path: String,
        val description: String?,
        val seq: Long,
    )

    /** 宿主 `presentedByTurn`：按轮收集声明，按路径去重（首次位置、末次描述）。 */
    private fun presentedByTurn(events: List<SessionEvent>): Map<Int, Map<String, PresentedFile>> {
        val byTurn = LinkedHashMap<Int, LinkedHashMap<String, PresentedFile>>()
        for (event in events) {
            if (event.type != "deliverables/presented") continue
            val data = event.data ?: continue
            val turn = data.optInt("turn", 0)
            if (turn <= 0) continue
            val files = data.optJSONArray("files") ?: continue
            val map = byTurn.getOrPut(turn) { LinkedHashMap() }
            for (index in 0 until files.length()) {
                val file = files.optJSONObject(index) ?: continue
                val path = file.optString("path")
                if (path.isEmpty()) continue
                map[path] = PresentedFile(
                    path = path,
                    // 宿主：描述 trim 后非空才留，否则 null。
                    description = file.optString("description", "").trim().takeIf { it.isNotEmpty() },
                    seq = event.seq,
                )
            }
        }
        return byTurn
    }

    /** 宿主 `turnTracesOf`：`turn/start` / `turn/end` 给出的每轮边界（按开始序）。 */
    private data class TurnTrace(val turn: Int, val startSeq: Long?, val endSeq: Long?)

    private fun turnTracesOf(events: List<SessionEvent>): List<TurnTrace> {
        val order = LinkedHashMap<Int, TurnTrace>()
        for (event in events) {
            val turn = event.data?.optInt("turn", 0) ?: 0
            if (turn <= 0) continue
            val previous = order[turn]
            when (event.type) {
                "turn/start" -> order[turn] = TurnTrace(
                    turn = turn,
                    startSeq = previous?.startSeq?.takeIf { it < event.seq } ?: event.seq,
                    endSeq = previous?.endSeq,
                )

                "turn/end" -> order[turn] = TurnTrace(
                    turn = turn,
                    startSeq = previous?.startSeq ?: event.seq,
                    endSeq = event.seq,
                )
            }
        }
        return order.values.sortedBy { it.startSeq ?: Long.MAX_VALUE }
    }

    private fun userRow(
        event: SessionEvent,
        source: JSONObject,
        content: JSONArray?,
        turn: Int?,
    ): TimelineRow {
        var images = 0
        val blocks = ArrayList<JSONObject>()
        if (content != null) {
            for (index in 0 until content.length()) {
                val block = content.optJSONObject(index) ?: continue
                when (block.optString("type")) {
                    "image" -> images += 1
                    "file" -> Unit
                }
                if ((block.optString("type") == "image" || block.optString("type") == "file") && block.has("attachment")) {
                    blocks += JSONObject()
                        .put("type", block.optString("type"))
                        .put("name", block.optJSONObject("attachment")?.optString("name"))
                }
            }
        }
        val requestId = source.optString("rpcId").takeIf { it.isNotEmpty() }
        return TimelineRow(
            key = "user:${event.seq}",
            kind = TimelineRow.KIND_USER,
            seq = event.seq,
            turn = turn,
            timeMs = event.timeMs,
            payload = TimelineRowPayload.User(
                text = foldTextOf(content),
                blocks = blocks,
                images = images,
                requestId = requestId,
                live = false,
            ),
        )
    }

    private fun retryRow(event: SessionEvent, data: JSONObject?, turn: Int, botName: String?): TimelineRow {
        val retry = maxOf(data?.optInt("retry", 0) ?: 0, 1)
        val max = data?.optInt("maxRetries", 0) ?: 0
        val failure = data?.optJSONObject("failure")
        val reason = failure?.optString("message")?.takeIf { it.isNotBlank() }
            ?: failure?.optString("code")?.takeIf { it.isNotEmpty() }
        val retrySuffix = if (max > 0) "（$retry/$max）" else "（$retry）"
        val reasonSuffix = if (reason != null) "：$reason" else ""
        return TimelineRow(
            key = "retry:${event.seq}",
            kind = TimelineRow.KIND_EVENT,
            seq = event.seq,
            turn = turn,
            timeMs = event.timeMs,
            payload = TimelineRowPayload.Event(
                eventKind = "retry",
                code = failure?.optString("code")?.takeIf { it.isNotEmpty() },
                botId = null,
                name = botName,
                raw = JSONObject()
                    .put("eventKind", "retry")
                    .put("code", failure?.optString("code") ?: JSONObject.NULL)
                    .put("text", "模型请求失败，正在重试$retrySuffix$reasonSuffix"),
            ),
        )
    }

    private fun turnFailRow(event: SessionEvent, reason: JSONObject, turn: Int, botName: String?): TimelineRow {
        val error = reason.optJSONObject("error")
        val code = error?.optString("code")?.takeIf { it.isNotEmpty() } ?: "UNKNOWN"
        val message = if (code == "AUTH" || error?.optString("message") == null) {
            ""
        } else {
            error?.optString("message")?.trim()?.take(500) ?: ""
        }
        return TimelineRow(
            key = "turnfail:${event.seq}",
            kind = TimelineRow.KIND_EVENT,
            seq = event.seq,
            turn = turn,
            timeMs = event.timeMs,
            payload = TimelineRowPayload.Event(
                eventKind = "failed",
                code = code,
                botId = null,
                name = botName,
                raw = JSONObject()
                    .put("eventKind", "failed")
                    .put("code", code)
                    .put("text", message),
            ),
        )
    }

    private fun toolLabel(
        name: String,
        raw: String,
        result: String,
        isError: Boolean,
        botName: String?,
        resolveBot: (String) -> String?,
    ): String {
        val nameOf = { value: String -> resolveBot(value) ?: value }
        return when (name) {
            "message_bot" -> if (isError) {
                if (raw.isNotEmpty()) "没能给 $raw 发消息" else "没能给其他 Bot 发消息"
            } else {
                if (raw.isNotEmpty()) "消息发给了 ${nameOf(raw)}" else "消息发给了其他 Bot"
            }

            "stop_bot" -> if (isError) {
                if (raw.isNotEmpty()) "没能叫停 $raw" else "没能叫停其他 Bot"
            } else {
                if (raw.isNotEmpty()) "叫停了 ${nameOf(raw)}" else "叫停了其他 Bot"
            }

            "create_bot" -> if (isError) "创建 Bot 失败" else "创建了 ${if (raw.isNotEmpty()) nameOf(raw) else "Bot"}"

            "update_bot" -> {
                val self = if (raw.isNotEmpty()) nameOf(raw) else (botName ?: "")
                if (isError) "更新 Bot 失败" else "更新了 $self"
            }

            "create_group", "update_group", "delete_group", "post_to_group" -> {
                val named = GROUP_RESULT_NAME.find(result.trim())?.groupValues?.getOrNull(1)
                    ?: raw.takeIf { it.isNotEmpty() }
                val verbs = when (name) {
                    "create_group" -> listOf("创建了群聊", "创建群聊失败")
                    "update_group" -> listOf("更新了群聊", "更新群聊失败")
                    "delete_group" -> listOf("删除了群聊", "删除群聊失败")
                    else -> listOf("在", "没能在群里发言")
                }
                when {
                    isError -> verbs[1]
                    name == "post_to_group" -> if (named != null) "在 $named 发言" else "在群里发言"
                    else -> if (named != null) "${verbs[0]}：$named" else verbs[0]
                }
            }

            "read_group_chat" -> if (isError) {
                "没能读群聊"
            } else {
                if (raw.isNotEmpty()) "阅读了群聊「$raw」" else "阅读了群聊"
            }

            else -> name
        }
    }

    private fun toolTarget(
        name: String,
        raw: String,
        result: String,
        isError: Boolean,
        botName: String?,
        resolveBot: (String) -> String?,
    ): String? {
        val nameOf = { value: String -> resolveBot(value) ?: value }
        return when (name) {
            "message_bot", "stop_bot", "read_group_chat" -> raw.takeIf { it.isNotEmpty() }
            "update_bot" -> (if (raw.isNotEmpty()) nameOf(raw) else (botName ?: "")).takeIf { it.isNotEmpty() }
            "create_group", "update_group", "delete_group", "post_to_group" -> {
                val named = GROUP_RESULT_NAME.find(result.trim())?.groupValues?.getOrNull(1)
                    ?: raw.takeIf { it.isNotEmpty() }
                named ?: ""
            }

            else -> null
        }
    }

    /** 宿主 `textContentOf`：text 块直接相连（不加分隔）后 trim。 */
    private fun textContentOf(content: JSONArray?): String {
        if (content == null) return ""
        val out = StringBuilder()
        for (index in 0 until content.length()) {
            val block = content.optJSONObject(index) ?: continue
            if (block.optString("type") == "text") out.append(block.optString("text"))
        }
        return out.toString().trim()
    }

    /** 宿主 `firstNonBlank` 语义：按 key 顺序取第一个 trim 后非空的值。 */
    private fun firstNonBlank(args: JSONObject?, vararg keys: String): String {
        if (args == null) return ""
        for (key in keys) {
            val value = args.optString(key, "").trim()
            if (value.isNotEmpty()) return value
        }
        return ""
    }

    /** 宿主 `resolveSessionPath`：相对路径拼 cwd；绝对路径原样（去 file:// 与 ..）。 */
    internal fun resolveSessionPath(path: String, cwd: String?): String {
        var value = path.trim()
        if (value.startsWith("file://")) {
            value = runCatching { java.net.URLDecoder.decode(value.removePrefix("file://"), "UTF-8") }
                .getOrDefault(value.removePrefix("file://"))
        }
        if (Regex("^[A-Za-z]:[/\\\\]").containsMatchIn(value) || value.startsWith("\\\\")) return value
        if (!value.startsWith("/")) {
            if (cwd == null || !cwd.startsWith("/")) return value
            value = "${cwd.trimEnd('/')}/$value"
        }
        val out = ArrayList<String>()
        for (segment in value.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
                else -> out += segment
            }
        }
        return "/" + out.joinToString("/")
    }
}
