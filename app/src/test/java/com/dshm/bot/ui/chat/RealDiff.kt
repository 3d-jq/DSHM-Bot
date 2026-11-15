package com.dshm.bot.ui.chat

import com.dshm.bot.data.remote.SessionEvent
import com.dshm.bot.data.remote.TimelineRow
import com.dshm.bot.data.remote.TimelineRowPayload
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 真实数据差分器（开发期工具，不参与打包）：
 *
 * 读 `real-diff.json`（宿主 JS 折叠的真机真实会话结果 + 同一份事件），
 * 用**客户端**折叠一遍，逐行比对并打印差异。
 *
 * 跑法（JVM，不经 Gradle 测试执行器）：
 * ```
 * java -cp "<test-cp>" com.dshm.bot.ui.chat.RealDiffKt <real-diff.json>
 * ```
 */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("用法：RealDiff <real-diff.json>")
        return
    }
    val root = JSONObject(File(args[0]).readText())
    val events = ArrayList<SessionEvent>()
    val array = root.optJSONArray("events") ?: JSONArray()
    for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        events += SessionEvent(
            seq = item.optLong("seq"),
            type = item.optString("type"),
            timeMs = if (item.isNull("time")) null else item.optLong("time"),
            data = if (item.isNull("data")) null else item.optJSONObject("data"),
        )
    }
    val exchangeTurns = buildSet {
        val turns = root.optJSONArray("exchangeTurns") ?: JSONArray()
        for (index in 0 until turns.length()) add(turns.optInt(index))
    }
    val clientRows = TimelineFold.fold(
        events = events,
        exchangeTurns = exchangeTurns,
        cwd = root.optString("cwd").takeIf { it.isNotEmpty() },
        botName = "大总管",
        resolveBot = { it },
    )

    val hostRows = ArrayList<TimelineRow>()
    val hostArray = root.optJSONArray("hostRows") ?: JSONArray()
    for (index in 0 until hostArray.length()) {
        val item = hostArray.optJSONObject(index) ?: continue
        hostRows += TimelineRow(
            key = item.optString("key"),
            kind = item.optString("kind"),
            seq = if (item.isNull("seq")) null else item.optLong("seq"),
            turn = if (item.isNull("turn")) null else item.optInt("turn"),
            timeMs = if (item.isNull("timeMs")) null else item.optLong("timeMs"),
            payload = parsePayload(item),
        )
    }

    println("事件 ${events.size} 条 → 宿主 ${hostRows.size} 行 / 客户端 ${clientRows.size} 行")
    val result = ShadowDiff.compare(hostRows, clientRows)
    if (result.identical) {
        println("✅ 逐行完全一致（真实会话数据）")
        return
    }
    println("❌ 有差异：")
    result.missingInClient.forEach { println("  宿主有客户端无：$it") }
    result.extraInClient.forEach { println("  客户端有宿主无：$it") }
    result.payloadMismatch.forEach { (key, why) -> println("  $key → $why") }
    // 把两边的行都打出来，便于人工核对第一处差异。
    println("--- 宿主行 ---")
    hostRows.forEach { println("  ${it.key} ${it.kind} seq=${it.seq} turn=${it.turn}") }
    println("--- 客户端行 ---")
    clientRows.forEach { println("  ${it.key} ${it.kind} seq=${it.seq} turn=${it.turn}") }
}

private fun parsePayload(item: JSONObject): TimelineRowPayload {
    val payload = item.optJSONObject("payload") ?: JSONObject()
    return when (item.optString("kind")) {
        "user" -> TimelineRowPayload.User(
            text = payload.optString("text"),
            blocks = buildList {
                val blocks = payload.optJSONArray("blocks") ?: JSONArray()
                for (index in 0 until blocks.length()) add(blocks.optJSONObject(index) ?: JSONObject())
            },
            images = payload.optInt("images", 0),
            requestId = payload.optString("requestId").takeIf { it.isNotEmpty() },
            live = payload.optBoolean("live", false),
        )

        "bot" -> TimelineRowPayload.Bot(
            text = payload.optString("text"),
            author = payload.optString("name").takeIf { it.isNotEmpty() },
            botId = payload.optString("botId").takeIf { it.isNotEmpty() },
            durationMs = if (payload.has("durationMs")) payload.optLong("durationMs") else null,
            whole = payload.optJSONObject("whole"),
            live = payload.optBoolean("live", false),
        )

        "tool" -> TimelineRowPayload.Tool(
            tool = payload.optString("tool"),
            state = payload.optString("state", "ok"),
            label = payload.optString("label"),
            target = payload.optString("target").takeIf { it.isNotEmpty() },
            callId = payload.optString("callId").takeIf { it.isNotEmpty() },
        )

        "event" -> TimelineRowPayload.Event(
            eventKind = payload.optString("eventKind"),
            code = payload.optString("code").takeIf { it.isNotEmpty() },
            botId = payload.optString("botId").takeIf { it.isNotEmpty() },
            name = payload.optString("name").takeIf { it.isNotEmpty() },
            raw = payload,
        )

        "changes" -> TimelineRowPayload.Changes(
            turn = if (payload.has("turn")) payload.optInt("turn") else null,
            raw = payload,
        )

        "artifact" -> TimelineRowPayload.Artifact(
            files = buildList {
                val files = payload.optJSONArray("files") ?: JSONArray()
                for (index in 0 until files.length()) {
                    val file = files.optJSONObject(index) ?: continue
                    add(
                        com.dshm.bot.data.remote.TimelineFile(
                            path = file.optString("path"),
                            description = if (file.isNull("description")) null else file.optString("description"),
                        ),
                    )
                }
            },
            delivered = payload.optBoolean("delivered", false),
            turn = if (payload.has("turn")) payload.optInt("turn") else null,
        )

        "divider" -> TimelineRowPayload.Divider(if (payload.has("timeMs")) payload.optLong("timeMs") else null)
        "inbox" -> TimelineRowPayload.Inbox(
            text = payload.optString("text"),
            slot = payload.optString("slot", "next-turn"),
            requestId = payload.optString("requestId").takeIf { it.isNotEmpty() },
            itemId = payload.optString("itemId"),
            sourceKind = payload.optString("sourceKind").takeIf { it.isNotEmpty() },
            botId = payload.optString("botId").takeIf { it.isNotEmpty() },
            name = payload.optString("name").takeIf { it.isNotEmpty() },
        )

        else -> TimelineRowPayload.Raw(payload)
    }
}
