package com.dshm.bot.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.dshm.bot.data.remote.DshRpcException
import com.dshm.bot.data.remote.DshClients
import com.dshm.bot.data.remote.WorkspaceFilesApi
import com.dshm.bot.data.remote.fileExtensionOf
import com.dshm.bot.data.remote.fileNameOf
import com.dshm.bot.data.runtime.RuntimeEndpoint
import java.io.File

/** 对话里一轮交付的一个文件（`present` 声明）。 */
data class ChatArtifact(
    val path: String,
    val description: String?,
) {
    val name: String get() = fileNameOf(path)
    val extension: String get() = fileExtensionOf(path)

    /** 没有描述时显示的副标题：扩展名大写。 */
    val typeLabel: String get() = if (extension.isEmpty()) "" else extension.uppercase()
}

/**
 * 交付文件的下行链路：从本机 DSH 取字节 → 落到 App 缓存 → 交给手机上的应用。
 *
 * 取字节走 `workspaceFiles/stat`（拿绝对路径、解析相对路径）+ `GET /api/file`，
 * 对齐 dsh-Go `state/artifact_files.dart`；打开/分享走 FileProvider，
 * 保存走 MediaStore「下载」（API 29+），旧版本退到应用自己的外部目录。
 */
object ChatArtifactFiles {

    /** 取文件并写进 App 缓存；同名文件覆盖，避免拿到上一轮的旧内容。 */
    suspend fun fetch(
        context: Context,
        endpoint: RuntimeEndpoint,
        sessionId: String,
        artifact: ChatArtifact,
    ): File {
        val api = appCacheDir(context)
        val file = File(api, safeName(artifact.name))
        val bytes = WorkspaceFilesApi(DshClients.of(endpoint)).read(sessionId, artifact.path)
        file.writeBytes(bytes)
        return file
    }

    /** 交给手机上的应用打开；没有应用能处理时抛 [android.content.ActivityNotFoundException]。 */
    fun open(context: Context, file: File, artifact: ChatArtifact, chooser: Boolean) {
        val intent = viewIntent(context, file, artifact)
        if (chooser) {
            context.startActivity(Intent.createChooser(intent, null))
        } else {
            context.startActivity(intent)
        }
    }

    fun share(context: Context, file: File, artifact: ChatArtifact) {
        val uri = uriOf(context, file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeOf(artifact.name)
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, artifact.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, artifact.name))
    }

    /** 存到手机的「下载」；返回给用户看的路径（系统下载目录里就不必再说路径）。 */
    fun saveToDownloads(context: Context, file: File, artifact: ChatArtifact): String? {
        val name = safeName(artifact.name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mimeOf(name))
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("系统没有给出下载位置")
            context.contentResolver.openOutputStream(uri)?.use { out -> out.write(file.readBytes()) }
            return null
        }
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        val target = File(dir, name)
        file.copyTo(target, overwrite = true)
        return target.absolutePath
    }

    fun copyPath(context: Context, artifact: ChatArtifact) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(artifact.name, artifact.path))
    }

    private fun viewIntent(context: Context, file: File, artifact: ChatArtifact): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uriOf(context, file), mimeOf(artifact.name))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    private fun uriOf(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)

    private fun appCacheDir(context: Context): File =
        File(context.cacheDir, "artifacts").apply { mkdirs() }

    private fun safeName(name: String): String {
        val cleaned = name.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
        return cleaned.ifEmpty { "file" }
    }
}

/** 已知的 DS Bot/DSH 错误码；其余情况由界面显示原始信息。 */
fun artifactErrorCode(error: Throwable): String? = (error as? DshRpcException)?.code

/** 扩展名 → MIME 类型；认不出来时按二进制流处理。 */
fun mimeOf(name: String): String = when (fileExtensionOf(name)) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "svg" -> "image/svg+xml"
    "pdf" -> "application/pdf"
    "doc" -> "application/msword"
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    "xls" -> "application/vnd.ms-excel"
    "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    "csv" -> "text/csv"
    "ppt" -> "application/vnd.ms-powerpoint"
    "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    "md" -> "text/markdown"
    "txt", "log" -> "text/plain"
    "json" -> "application/json"
    "html", "htm" -> "text/html"
    "zip" -> "application/zip"
    "mp3" -> "audio/mpeg"
    "wav" -> "audio/wav"
    "m4a" -> "audio/mp4"
    "mp4" -> "video/mp4"
    "mov" -> "video/quicktime"
    else -> "application/octet-stream"
}
