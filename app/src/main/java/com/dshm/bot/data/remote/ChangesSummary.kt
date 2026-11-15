package com.dshm.bot.data.remote

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一轮改动的文件摘要（DSH `GET /api/changes.summary` 的响应本体）。
 *
 * 形状（实读 `dsh-client-ui-deliverables` 宿主半 `handleChangesSummary`）：
 * 该路由**直接 `Response.json({turn, files, total, added, deleted})`——是裸 JSON，
 * 不走 `/api/bot` 的 `{ok,value}` 信封**。客户端解析以裸 JSON 为准，若某变体真给
 * 了信封（`{ok,value}`）也兼容。
 *
 * `total` 是完整改动文件数（`files` 会被宿主的 `maxFiles` 截断）；[added] / [deleted]
 * 是全部改动文件的行数合计。文件级 `added`/`deleted` 在 binary/oversized 时为 0。
 */
data class ChangesSummary(
    val files: List<ChangedFile>,
    val total: Int,
    val added: Int,
    val deleted: Int,
) {
    /** 单文件（`total == 1`）时官方只画标题 + `+N −M`，不画文件行。 */
    val singleFile: ChangedFile? get() = if (total == 1) files.firstOrNull() else null
}

/** 一个改动文件（DSH `WorkspaceChangedFile` 的客户端面）。 */
data class ChangedFile(
    /** 相对会话工作目录的路径（工作目录外时可能是绝对路径）。 */
    val path: String,
    /** 排序键 / 展示名（相对路径，工作目录外为 `../` / `~` / 绝对路径）。 */
    val display: String,
    val added: Int,
    val deleted: Int,
    /** git 报二进制（或捕获侧含 NUL）：无行数、无对比。 */
    val binary: Boolean = false,
    /** 某侧超过宿主 `maxFileBytes`：只列出、无行数。 */
    val oversized: Boolean = false,
)

/**
 * 解析 `changes.summary` 的响应（纯函数）。
 *
 * 裸 JSON 优先，`{ok,value}` 信封兜底。形状不对（`null`）返回 `null`；`files` 缺席
 * 或为空都按空列表（`total` 回落文件数）。单个文件条目缺非空 `path` 时丢弃该条
 * （整份摘要不因此作废）；其余字段缺失一律回落默认值（向前兼容）。
 */
fun parseChangesSummary(json: JSONObject?): ChangesSummary? {
    if (json == null) return null
    // 裸 JSON 是 DSH 的实际形状；信封只在异常/变体宿主出现，认下作兜底。
    val root = if (json.opt("value") is JSONObject && json.has("ok")) {
        json.optJSONObject("value") ?: return null
    } else {
        json
    }
    val files = root.optJSONArray("files").fileObjects().mapNotNull { file ->
        val path = file.optString("path").trim()
        if (path.isEmpty()) return@mapNotNull null
        ChangedFile(
            path = path,
            display = file.optString("display").trim().ifEmpty { path },
            added = file.optInt("added", 0),
            deleted = file.optInt("deleted", 0),
            binary = file.optBoolean("binary", false),
            oversized = file.optBoolean("oversized", false),
        )
    }
    return ChangesSummary(
        files = files,
        // total 缺席回落「列出的文件数」（宿主通常都给，但容忍旧/残缺宿主）。
        total = if (root.has("total") && !root.isNull("total")) root.optInt("total", files.size) else files.size,
        added = root.optInt("added", 0),
        deleted = root.optInt("deleted", 0),
    )
}

/** 数组里的对象条目；非对象元素跳过（不抛）。 */
private fun JSONArray?.fileObjects(): List<JSONObject> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optJSONObject(it) }
}
