package com.dshm.bot.ui.chat

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import android.webkit.WebResourceResponse
import com.dshm.bot.data.runtime.RuntimeInstaller
import java.io.ByteArrayInputStream
import java.io.File

/**
 * 把 rootfs 里的本地页面映射成 WebView 能加载的自定义 scheme。
 *
 * 和 DeepSearch 的 `minis://` 是同一思路：没有本机端口，页面 origin 固定，
 * `shouldInterceptRequest` 直接按文件返回响应，HTML 旁边的 JS/CSS/图片
 * 也能用相对路径正常加载。
 */
object RootfsWeb {
    const val SCHEME = "dshm"

    private const val HOST_WORKSPACE = "workspace"
    private const val HOST_ATTACHMENTS = "attachments"
    private const val HOST_DSH = "dsh"

    private const val WORKSPACE_PATH = "root/.dsh/bot/workspace"
    private const val ATTACHMENTS_PATH = "root/.dsh/attachments"
    private const val DSH_PATH = "root/.dsh"

    private const val TEXT_ENCODING = "UTF-8"

    /** 本地文件转成 `dshm://`。不是本地文件、或不在可暴露目录内时返回 null。 */
    fun urlFor(context: Context, raw: String): String? {
        if (raw.startsWith("$SCHEME://")) return raw
        val route = routeOf(raw) ?: return null
        val rootfs = RuntimeInstaller(context).rootfsDir.canonicalFile
        if (!rootfs.isDirectory) return null
        val base = baseFor(rootfs, route.host) ?: return null
        val target = resolve(base, route.path) ?: return null
        val exists = target.isFile || (target.isDirectory && File(target, "index.html").isFile)
        if (route.implicit && !exists) return null

        return buildString {
            append(SCHEME)
            append("://")
            append(route.host)
            append('/')
            append(Uri.encode(route.path, "/"))
            route.query?.let {
                append('?')
                append(it)
            }
            route.fragment?.let {
                append('#')
                append(it)
            }
        }
    }

    /**
     * WebView 的 `shouldInterceptRequest`：按 URL 找到 rootfs 文件并返回。
     * 非 `dshm://` 返回 null，交回 WebView 自己处理。
     */
    fun intercept(context: Context, uri: Uri): WebResourceResponse? {
        if (uri.scheme != SCHEME) return null
        val target = fileFor(context, uri) ?: return notFound("File not found")

        val mime = mimeOf(target.name)
        val headers = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Methods" to "GET, HEAD, OPTIONS",
            "Access-Control-Allow-Headers" to "*",
            "Cross-Origin-Resource-Policy" to "cross-origin",
            "Cache-Control" to "no-store",
        )
        return WebResourceResponse(
            mime,
            if (isTextMime(mime)) TEXT_ENCODING else null,
            200,
            "OK",
            headers,
            target.inputStream(),
        )
    }

    /** `dshm://` 对应的 rootfs 文件；目录会落到 `index.html`。 */
    fun fileFor(context: Context, uri: Uri): File? {
        if (uri.scheme != SCHEME) return null
        val host = uri.host ?: return null
        val rootfs = RuntimeInstaller(context).rootfsDir.canonicalFile
        val base = baseFor(rootfs, host) ?: return null
        val relative = uri.path.orEmpty().removePrefix("/")
        val candidate = resolve(base, relative) ?: return null
        val target = if (candidate.isDirectory) File(candidate, "index.html") else candidate
        return target.takeIf { it.isFile }
    }

    private data class Route(
        val host: String,
        val path: String,
        val implicit: Boolean,
        val query: String?,
        val fragment: String?,
    )

    private fun routeOf(raw: String): Route? {
        if (raw.startsWith("$SCHEME://")) return null
        if (!raw.startsWith("file://") && schemePattern.containsMatchIn(raw)) return null
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        val implicit = !raw.startsWith("file://") && !raw.startsWith("/")
        val path = when {
            raw.startsWith("file://") -> uri.path.orEmpty()
            raw.startsWith("/") -> uri.path.orEmpty()
            raw.contains(' ') -> return null
            else -> "$WORKSPACE_PATH/${uri.path.orEmpty().removePrefix("/")}"
        }
        if (path.isBlank()) return null

        val clean = path.trimStart('/')
        val hostAndPath = when {
            clean == WORKSPACE_PATH ->
                HOST_WORKSPACE to ""
            clean.startsWith("$WORKSPACE_PATH/") ->
                HOST_WORKSPACE to clean.removePrefix("$WORKSPACE_PATH/")
            clean == ATTACHMENTS_PATH ->
                HOST_ATTACHMENTS to ""
            clean.startsWith("$ATTACHMENTS_PATH/") ->
                HOST_ATTACHMENTS to clean.removePrefix("$ATTACHMENTS_PATH/")
            clean == DSH_PATH ->
                HOST_DSH to ""
            clean.startsWith("$DSH_PATH/") ->
                HOST_DSH to clean.removePrefix("$DSH_PATH/")
            else -> return null
        }
        return Route(
            host = hostAndPath.first,
            path = hostAndPath.second,
            implicit = implicit,
            query = uri.encodedQuery,
            fragment = uri.encodedFragment,
        )
    }

    private fun baseFor(rootfs: File, host: String): File? = when (host) {
        HOST_WORKSPACE -> File(rootfs, WORKSPACE_PATH)
        HOST_ATTACHMENTS -> File(rootfs, ATTACHMENTS_PATH)
        HOST_DSH -> File(rootfs, DSH_PATH)
        else -> null
    }

    private fun resolve(base: File, relative: String): File? {
        val canonicalBase = runCatching { base.canonicalFile }.getOrNull() ?: return null
        val target = runCatching { File(canonicalBase, relative).canonicalFile }.getOrNull() ?: return null
        val basePrefix = canonicalBase.path + File.separator
        return target.takeIf {
            it.path == canonicalBase.path || it.path.startsWith(basePrefix)
        }
    }

    private fun notFound(message: String): WebResourceResponse =
        WebResourceResponse(
            "text/plain",
            TEXT_ENCODING,
            404,
            "Not Found",
            mapOf(
                "Access-Control-Allow-Origin" to "*",
                "Cache-Control" to "no-store",
            ),
            ByteArrayInputStream(message.toByteArray(Charsets.UTF_8)),
        )

    private fun mimeOf(filename: String): String {
        val ext = filename.substringAfterLast('.', "").lowercase()
        specialMimes[ext]?.let { return it }
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }

    private fun isTextMime(mime: String): Boolean =
        mime.startsWith("text/") ||
            mime == "application/json" ||
            mime == "application/javascript" ||
            mime == "application/xml" ||
            mime.endsWith("+xml") ||
            mime == "image/svg+xml"

    private val schemePattern = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

    private val specialMimes = mapOf(
        "html" to "text/html",
        "htm" to "text/html",
        "css" to "text/css",
        "js" to "application/javascript",
        "mjs" to "application/javascript",
        "json" to "application/json",
        "map" to "application/json",
        "svg" to "image/svg+xml",
        "wasm" to "application/wasm",
        "md" to "text/markdown",
        "txt" to "text/plain",
        "csv" to "text/csv",
        "xml" to "application/xml",
        "webmanifest" to "application/manifest+json",
        "woff" to "font/woff",
        "woff2" to "font/woff2",
        "ttf" to "font/ttf",
        "otf" to "font/otf",
    )
}
