package com.dshm.bot.data.remote

import org.json.JSONArray
import org.json.JSONObject

/**
 * DSH 会话协议的 wire 模型，对齐 dsh-Go `packages/dsh_client/lib/src/models.dart`。
 *
 * 解析原则与 dsh-Go 一致：**tolerant** —— 未知类型保留原始 JSON（[DshPart.Unknown] 等），
 * 绝不静默丢弃，UI 才有机会渲染以后新增的卡片；缺字段走默认值，不抛异常。
 */

private fun JSONObject.intOrNull(key: String): Int? = if (has(key)) optInt(key) else null
private fun JSONObject.longOrNull(key: String): Long? = if (has(key)) optLong(key) else null
private fun JSONObject.stringOrNull(key: String): String? = if (has(key)) optString(key) else null

private fun jsonArrayOf(json: Any?): JSONArray? = json as? JSONArray

/** 图片 / 文件附件的持久引用；字节数据要另走 `session/attachment`。 */
data class DshAttachment(
    val attachmentId: String?,
    val mediaType: String?,
    val name: String?,
    val bytes: Int?,
    val width: Int?,
    val height: Int?,
    val raw: JSONObject,
) {
    companion object {
        fun fromJson(json: Any?): DshAttachment {
            val obj = json as? JSONObject ?: JSONObject()
            return DshAttachment(
                attachmentId = obj.stringOrNull("attachmentId"),
                mediaType = obj.stringOrNull("mediaType"),
                name = obj.stringOrNull("name"),
                bytes = obj.intOrNull("bytes"),
                width = obj.intOrNull("width"),
                height = obj.intOrNull("height"),
                raw = obj,
            )
        }
    }
}

/** 一条消息里的一个内容块（ContentBlock）。 */
sealed interface DshPart {
    data class Text(val text: String) : DshPart
    data class Reasoning(val text: String) : DshPart
    data class Image(val attachment: DshAttachment, val offloaded: Boolean) : DshPart
    data class File(val attachment: DshAttachment) : DshPart
    data class ToolCall(val id: String, val name: String, val arguments: String) : DshPart
    data class Unknown(val type: String, val raw: JSONObject) : DshPart

    companion object {
        fun fromJson(json: Any?): DshPart {
            val obj = json as? JSONObject ?: return Unknown("", JSONObject())
            return when (obj.optString("type")) {
                "text" -> Text(obj.optString("text"))
                "reasoning" -> Reasoning(obj.optString("text"))
                "image" -> Image(
                    DshAttachment.fromJson(obj.optJSONObject("attachment")),
                    obj.optBoolean("offloaded", false),
                )
                "file" -> File(DshAttachment.fromJson(obj.optJSONObject("attachment")))
                "tool-call" -> ToolCall(
                    id = obj.optString("id"),
                    name = obj.optString("name"),
                    arguments = obj.optString("arguments"),
                )
                else -> Unknown(obj.optString("type"), obj)
            }
        }

        fun listFromJson(json: Any?): List<DshPart> {
            val array = jsonArrayOf(json) ?: return emptyList()
            return List(array.length()) { fromJson(array.opt(it)) }
        }
    }
}

/** 模型流式输出的一个增量块（StreamChunk）。 */
sealed interface DshStreamChunk {
    data class BlockStart(val index: Int, val blockType: String) : DshStreamChunk
    data class TextDelta(val index: Int, val text: String) : DshStreamChunk
    data class ReasoningDelta(val index: Int, val text: String) : DshStreamChunk
    data class ToolCallDelta(
        val index: Int,
        val id: String,
        val name: String?,
        val argumentsDelta: String,
    ) : DshStreamChunk
    data class BlockEnd(val index: Int, val block: DshPart) : DshStreamChunk
    data class Usage(val raw: JSONObject) : DshStreamChunk
    data class Finish(val kind: String, val raw: JSONObject) : DshStreamChunk
    data class Unknown(val raw: JSONObject) : DshStreamChunk

    companion object {
        fun fromJson(json: Any?): DshStreamChunk {
            val obj = json as? JSONObject ?: return Unknown(JSONObject())
            val index = obj.optInt("index", 0)
            return when (obj.optString("type")) {
                "block-start" -> BlockStart(index, obj.optString("blockType"))
                "text-delta" -> TextDelta(index, obj.optString("text"))
                "reasoning-delta" -> ReasoningDelta(index, obj.optString("text"))
                "tool-call-delta" -> ToolCallDelta(
                    index,
                    id = obj.optString("id"),
                    name = obj.stringOrNull("name"),
                    argumentsDelta = obj.optString("argumentsDelta"),
                )
                "block-end" -> BlockEnd(index, DshPart.fromJson(obj.opt("block")))
                "usage" -> Usage(obj.optJSONObject("usage") ?: JSONObject())
                "finish" -> Finish(
                    obj.optJSONObject("reason")?.optString("kind") ?: "unknown",
                    obj.optJSONObject("reason") ?: JSONObject(),
                )
                else -> Unknown(obj)
            }
        }
    }
}

/** `session/follow` 的一帧。 */
sealed interface DshFollowFrame {
    data class Snapshot(val raw: JSONObject) : DshFollowFrame
    data class Event(val event: DshLogEvent) : DshFollowFrame
    data class AssistantStream(
        val raw: JSONObject,
        val attemptId: String?,
        val phase: String,
        val chunk: DshStreamChunk?,
    ) : DshFollowFrame
    data class Unknown(val raw: JSONObject) : DshFollowFrame

    companion object {
        fun fromJson(json: Any?): DshFollowFrame {
            val obj = json as? JSONObject ?: return Unknown(JSONObject())
            return when (obj.optString("type")) {
                "snapshot" -> Snapshot(obj)
                "event" -> Event(DshLogEvent.fromJson(obj.opt("event")))
                "assistant-stream" -> {
                    val frame = obj.optJSONObject("frame") ?: JSONObject()
                    val phase = frame.optString("type")
                    AssistantStream(
                        raw = frame,
                        attemptId = frame.stringOrNull("attemptId"),
                        phase = phase,
                        chunk = if (phase == "chunk") DshStreamChunk.fromJson(frame.opt("chunk")) else null,
                    )
                }
                else -> Unknown(obj)
            }
        }
    }
}

/**
 * 持久会话日志事件（SessionWireEvent）：`seq` 连续、`time` 为 epoch 毫秒、
 * `type` 为事件名、`data` 保留原始负载。这里不把 60 多种事件拆成类，
 * 由 UI 按 [type] 分派；新事件自动落到通用分支而不会被丢掉。
 */
data class DshLogEvent(
    val seq: Long,
    val time: Long,
    val type: String,
    val data: JSONObject,
    val raw: JSONObject,
) {
    companion object {
        fun fromJson(json: Any?): DshLogEvent {
            val obj = json as? JSONObject ?: JSONObject()
            return DshLogEvent(
                seq = obj.longOrNull("seq") ?: -1L,
                time = obj.longOrNull("time") ?: 0L,
                type = obj.optString("type"),
                data = obj.optJSONObject("data") ?: JSONObject(),
                raw = obj,
            )
        }

        fun listFromJson(json: Any?): List<DshLogEvent> {
            val array = jsonArrayOf(json) ?: return emptyList()
            return List(array.length()) { fromJson(array.opt(it)) }
        }
    }
}

/** `session/list` 的条目；保留原始对象，字段由 Host 版本决定，不写死结构。 */
data class DshSessionSummary(val raw: JSONObject) {
    val id: String get() = raw.optString("sessionId").ifEmpty { raw.optString("id") }
    val title: String get() = raw.optString("title").ifEmpty { raw.optString("name") }

    companion object {
        fun listFromJson(json: Any?): List<DshSessionSummary> {
            val array = jsonArrayOf(json) ?: return emptyList()
            return List(array.length()) {
                DshSessionSummary(array.optJSONObject(it) ?: JSONObject())
            }
        }
    }
}

data class DshSearchHit(val sessionId: String, val snippet: String)
data class DshSearchPage(val items: List<DshSearchHit>, val hasMore: Boolean)
data class DshProvider(val id: String, val name: String)
