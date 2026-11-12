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

/**
 * 产物的类型（dsh-Go `ArtifactKind`）：图标、标签、MIME 都按它走。
 */
enum class ArtifactKind {
    IMAGE, PDF, DOCUMENT, SPREADSHEET, SLIDES, ARCHIVE, AUDIO, VIDEO, WEB, MARKDOWN, TEXT, OTHER
}

private val KIND_BY_EXTENSION: Map<String, ArtifactKind> = buildMap {
    listOf(
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "avif", "tif", "tiff", "ico", "svg",
    ).forEach { put(it, ArtifactKind.IMAGE) }
    put("pdf", ArtifactKind.PDF)
    listOf("doc", "docx", "odt", "rtf", "pages", "wps", "epub")
        .forEach { put(it, ArtifactKind.DOCUMENT) }
    listOf("xls", "xlsx", "ods", "csv", "tsv", "numbers", "et")
        .forEach { put(it, ArtifactKind.SPREADSHEET) }
    listOf("ppt", "pptx", "odp", "key", "dps").forEach { put(it, ArtifactKind.SLIDES) }
    listOf("zip", "tar", "gz", "tgz", "bz2", "xz", "7z", "rar", "zst", "apk")
        .forEach { put(it, ArtifactKind.ARCHIVE) }
    listOf("mp3", "wav", "m4a", "aac", "flac", "ogg", "opus", "amr", "mid", "midi")
        .forEach { put(it, ArtifactKind.AUDIO) }
    listOf("mp4", "mov", "webm", "mkv", "avi", "m4v", "3gp").forEach { put(it, ArtifactKind.VIDEO) }
    listOf("html", "htm", "mhtml").forEach { put(it, ArtifactKind.WEB) }
    listOf("md", "markdown").forEach { put(it, ArtifactKind.MARKDOWN) }
    listOf("txt", "log").forEach { put(it, ArtifactKind.TEXT) }
}

private val MIME_BY_EXTENSION: Map<String, String> = mapOf(
    "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif",
    "webp" to "image/webp", "bmp" to "image/bmp", "heic" to "image/heic", "heif" to "image/heif",
    "avif" to "image/avif", "tif" to "image/tiff", "tiff" to "image/tiff", "ico" to "image/x-icon",
    "svg" to "image/svg+xml",
    "pdf" to "application/pdf",
    "doc" to "application/msword",
    "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "odt" to "application/vnd.oasis.opendocument.text",
    "rtf" to "application/rtf", "epub" to "application/epub+zip",
    "xls" to "application/vnd.ms-excel",
    "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "ods" to "application/vnd.oasis.opendocument.spreadsheet",
    "csv" to "text/csv", "tsv" to "text/tab-separated-values",
    "ppt" to "application/vnd.ms-powerpoint",
    "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "odp" to "application/vnd.oasis.opendocument.presentation",
    "zip" to "application/zip", "gz" to "application/gzip", "tgz" to "application/gzip",
    "tar" to "application/x-tar", "bz2" to "application/x-bzip2", "xz" to "application/x-xz",
    "7z" to "application/x-7z-compressed", "rar" to "application/vnd.rar",
    "apk" to "application/vnd.android.package-archive",
    "mp3" to "audio/mpeg", "wav" to "audio/wav", "m4a" to "audio/mp4", "aac" to "audio/aac",
    "flac" to "audio/flac", "ogg" to "audio/ogg", "opus" to "audio/opus", "amr" to "audio/amr",
    "mid" to "audio/midi", "midi" to "audio/midi",
    "mp4" to "video/mp4", "mov" to "video/quicktime", "webm" to "video/webm",
    "mkv" to "video/x-matroska", "avi" to "video/x-msvideo", "m4v" to "video/x-m4v",
    "3gp" to "video/3gpp",
    "html" to "text/html", "htm" to "text/html", "md" to "text/markdown",
    "markdown" to "text/markdown", "txt" to "text/plain", "log" to "text/plain",
    "json" to "application/json", "xml" to "text/xml",
    "ics" to "text/calendar", "vcf" to "text/x-vcard",
)

fun artifactKindOf(path: String): ArtifactKind =
    KIND_BY_EXTENSION[fileExtensionOf(path)] ?: ArtifactKind.OTHER

/** 扩展名 → MIME；认不出来按二进制流（对齐 dsh-Go `mimeTypeOf`）。 */
fun mimeTypeOf(path: String): String =
    MIME_BY_EXTENSION[fileExtensionOf(path)] ?: "application/octet-stream"

/**
 * 只有这些算「给用户做的产物」；代码 / 配置 / Markdown / 纯文本不算
 * （改动卡已经在展示代码，README 不算交付物）—— 对齐 dsh-Go `isArtifactPath`。
 */
fun isArtifactKindPath(path: String): Boolean = when (artifactKindOf(path)) {
    ArtifactKind.MARKDOWN, ArtifactKind.TEXT, ArtifactKind.OTHER -> false
    else -> true
} || fileExtensionOf(path) in setOf("ics", "vcf")

/** 旧调用点沿用这个名字。 */
fun isArtifactPath(path: String): Boolean = isArtifactKindPath(path)

/** `PDF`、`表格 · XLSX`：类型标签，扩展名不重复时不追加。 */
fun artifactTypeLabel(path: String, kindLabel: String): String {
    val extension = fileExtensionOf(path).uppercase()
    if (extension.isEmpty()) return kindLabel
    if (kindLabel.equals(extension, ignoreCase = true) || kindLabel.isEmpty()) return extension
    return "$kindLabel · $extension"
}

/**
 * [path] 规整成绝对 POSIX 路径（对齐 dsh-Go `resolveSessionPath`）：
 * 相对路径按 [cwd] 拼接，`.` / `..` / 重复斜杠折叠；
 * Windows 路径或没有 [cwd] 的相对路径原样返回。
 */
fun resolveSessionPath(path: String, cwd: String? = null): String {
    var value = path
    if (value.startsWith("file://")) {
        value = runCatching { java.net.URLDecoder.decode(value.removePrefix("file://"), "UTF-8") }
            .getOrDefault(value.removePrefix("file://"))
    }
    if (Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(value)) return value
    if (!value.startsWith("/")) {
        if (cwd == null || !cwd.startsWith("/")) return value
        value = "$cwd/$value"
    }
    val parts = mutableListOf<String>()
    for (segment in value.split('/')) {
        when {
            segment.isEmpty() || segment == "." -> Unit
            segment == ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
            else -> parts.add(segment)
        }
    }
    return "/" + parts.joinToString("/")
}

/**
 * 每个 turn 的交付声明（对齐 dsh-Go `presentedByTurn`）：
 * 同一 turn 内同一路径只出现一次，保留**最后一次**声明（描述与坐标更新），
 * 位置仍在第一次声明处。
 */
fun presentedByTurn(events: List<org.json.JSONObject>): Map<Int, List<PresentedFile>> {
    val byTurn = LinkedHashMap<Int, LinkedHashMap<String, PresentedFile>>()
    for (event in events) {
        val files = presentedFilesOf(event) ?: continue
        for (file in files) {
            val slot = byTurn.getOrPut(file.turn) { LinkedHashMap() }
            slot[file.path] = file
        }
    }
    return byTurn.filterValues { it.isNotEmpty() }.mapValues { it.value.values.toList() }
}
