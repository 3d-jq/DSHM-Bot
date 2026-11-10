package com.dshm.bot.data.remote

import org.json.JSONObject

/**
 * `present` 工具留下的交付声明（`deliverables/presented` 事件）。
 *
 * 对齐 dsh-Go `packages/dsh_client/lib/src/deliverables.dart`：每次成功调用追加一条持久事件，
 * `data = {turn, callId, files: [{path, description?}]}`；只存声明，不存文件内容。
 * 新声明的路径是绝对路径，历史声明可能是相对 Session 工作目录的路径。
 */
data class PresentedFile(
    val path: String,
    val description: String?,
    val seq: Long,
    val index: Int,
    val turn: Int,
    val callId: String,
)

/** `present` 工具写入的持久事件类型。 */
const val DELIVERABLES_PRESENTED_EVENT = "deliverables/presented"

/**
 * 一条事件的交付声明，按原顺序返回；不是该事件、或事件畸形时返回 null。
 *
 * 畸形判定照 dsh-Go：`turn` 必须是 >= 1 的整数、`callId` 非空、`files` 是数组。
 * 单条文件缺 `path` 或 `description` 类型不对时跳过，其余条目保留自己的下标。
 */
fun presentedFilesOf(event: JSONObject): List<PresentedFile>? {
    if (event.optString("type") != DELIVERABLES_PRESENTED_EVENT) return null
    val data = event.optJSONObject("data") ?: return null
    val turnValue = (data.opt("turn") as? Number)?.toDouble() ?: return null
    if (turnValue < 1.0 || turnValue != Math.floor(turnValue)) return null
    val callId = data.optString("callId")
    if (data.isNull("callId") || callId.isEmpty()) return null
    val files = data.optJSONArray("files") ?: return null
    val seq = event.optLong("seq", -1L)
    val out = ArrayList<PresentedFile>(files.length())
    for (index in 0 until files.length()) {
        val file = files.optJSONObject(index) ?: continue
        if (file.isNull("path")) continue
        val path = file.optString("path").trim()
        if (path.isEmpty()) continue
        val description = when {
            !file.has("description") || file.isNull("description") -> null
            else -> file.optString("description")
        }
        out += PresentedFile(
            path = path,
            description = description,
            seq = seq,
            index = index,
            turn = turnValue.toInt(),
            callId = callId,
        )
    }
    return out
}

/** 路径的最后一段；`/` 与 `\` 都算分隔符。 */
fun fileNameOf(path: String): String {
    val trimmed = path.trim().trimEnd('/', '\\')
    val cut = trimmed.lastIndexOfAny(charArrayOf('/', '\\'))
    val name = if (cut >= 0) trimmed.substring(cut + 1) else trimmed
    return name.ifEmpty { path.trim() }
}

/** 小写扩展名（最多 8 位）；`Foo.PPTX` → `pptx`，没有扩展名时返回空串。 */
fun fileExtensionOf(path: String): String {
    val name = fileNameOf(path)
    val dot = name.lastIndexOf('.')
    if (dot <= 0 || dot == name.length - 1) return ""
    return name.substring(dot + 1).lowercase().take(8)
}

/** 只有这些扩展名算「给用户做的产物」；代码 / 配置 / 纯文本不算。 */
fun isArtifactPath(path: String): Boolean = fileExtensionOf(path) in setOf(
    "html", "htm", "mhtml",
    "pdf", "doc", "docx", "odt", "rtf", "pages", "wps", "epub",
    "xls", "xlsx", "ods", "csv", "tsv", "numbers", "et",
    "ppt", "pptx", "odp", "key", "dps",
    "zip", "tar", "gz", "tgz", "bz2", "xz", "7z", "rar", "zst", "apk",
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "avif", "tif", "tiff", "ico", "svg",
    "mp3", "wav", "m4a", "aac", "flac", "ogg", "opus", "amr", "mid", "midi",
    "mp4", "mov", "webm", "mkv", "avi", "m4v", "3gp",
    "ics", "vcf",
)
