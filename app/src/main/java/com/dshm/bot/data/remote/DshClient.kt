package com.dshm.bot.data.remote

import com.dshm.bot.data.runtime.RuntimeEndpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** DS Bot SSE 的一条事件（`event:` + `data:`）。 */
data class BotFeedEvent(val event: String, val data: String)

/** 一次附件上传的凭证（对应 DSH `fileUpload.upload` 的 receipt）。 */
data class DshUploadReceipt(
    val receiptId: String,
    val attachmentId: String,
    val name: String,
    val bytes: Long,
)

/** 引擎 RPC 的业务错误（HTTP 200 + result.ok=false）。 */
class DshRpcException(
    val code: String?,
    message: String,
    val details: JSONObject?,
) : Exception(message)

/**
 * 本机 DSH 的最小客户端：
 * 1) `GET /?token=` 换 `dsh-auth-*` cookie；
 * 2) `POST /api/bot` 发 Bot 请求；
 * 3) `GET /api/bot/feed` 读 SSE 事件流。
 *
 * 引擎重启后旧 cookie 会失效：所有请求统一走 [withAuthRetry]，401/403 时重新登录再试一次。
 */
class DshClient(private val endpoint: RuntimeEndpoint) {

    @Volatile
    private var cookie: String? = null

    /** 认证失效的内部信号：只在 401/403 时抛，用来触发一次「重新登录 + 重试」。 */
    private class AuthExpiredException(code: Int) : Exception("DSH 认证已失效：HTTP $code")

    /**
     * 用当前 cookie 执行一次请求；引擎重启后旧 cookie 作废时（401/403），清掉 cookie
     * 重新登录并重试一次。业务错误（HTTP 200 + ok=false）不重试，避免重复执行写操作。
     */
    private suspend fun <T> withAuthRetry(call: suspend (String) -> T): T {
        val auth = cookie ?: login()
        return try {
            call(auth)
        } catch (expired: AuthExpiredException) {
            cookie = null
            call(login())
        }
    }

    /** 在读响应体之前判定认证失效，交给 [withAuthRetry] 处理。 */
    private fun checkAuthExpired(code: Int) {
        if (code == HTTP_UNAUTHORIZED || code == HTTP_FORBIDDEN) throw AuthExpiredException(code)
    }

    suspend fun login(): String = withContext(Dispatchers.IO) {
        val url = URL(endpoint.baseUrl + "?token=" + endpoint.token)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        try {
            val setCookies = connection.headerFields["Set-Cookie"].orEmpty()
            val auth = setCookies.map { it.substringBefore(';') }
                .firstOrNull { it.startsWith("dsh-auth-") }
                ?: error("登录失败：没有拿到 dsh-auth cookie（HTTP ${connection.responseCode}）")
            cookie = auth
            auth
        } finally {
            connection.disconnect()
        }
    }

    /** 引擎地址（给 WebSocket 等需要自己拼 URL 的通道用）。 */
    val baseUrl: String get() = endpoint.baseUrl

    /**
     * 建立一条 `/api/remote.mux` 链路。
     *
     * mux 是 WebSocket —— 承载 `$events` 主机事件流（审批 / 提问 / 会话状态），
     * 是 DSH 客户端的地基。没有它，DSH 发出的审批请求没人能应答。
     */
    internal suspend fun openMuxLink(): DshMuxLink = withContext(Dispatchers.IO) {
        val auth = cookie ?: login()
        DshWebSocketMuxLink.connect(endpoint.baseUrl, auth)
    }

    /**
     * 调 `/api/bot`：body 是 `{endpoint, payload}`，响应是 `{ok, value|error}`。
     * 与 dsh_client 的 DsBot API 一致。
     */
    suspend fun bot(endpoint: String, payload: JSONObject = JSONObject()): JSONObject =
        botValue(endpoint, payload) as? JSONObject ?: JSONObject()

    /**
     * 同 [bot]，但保留原始 `value` 的形状（数组 / 布尔 / 字符串）。
     *
     * DS Bot 的 `exchange` 返回的是数组，`optJSONObject` 会把它吞成空对象。
     */
    suspend fun botValue(endpoint: String, payload: JSONObject = JSONObject()): Any? =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("endpoint", endpoint)
                .put("payload", payload)
            withAuthRetry { auth ->
                val connection = (URL(this@DshClient.endpoint.baseUrl + "api/bot").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Cookie", auth)
                }
                try {
                    connection.outputStream.use { it.write(body.toString().toByteArray()) }
                    val code = connection.responseCode
                    checkAuthExpired(code)
                    val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                    val text = stream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
                    require(code in 200..299) { "DS Bot 请求失败：HTTP $code $text" }
                    val response = JSONObject(text)
                    if (!response.optBoolean("ok", false)) {
                        val error = response.optJSONObject("error")
                        throw DshRpcException(
                            code = error?.anyOrNull("code") as? String,
                            message = error?.anyOrNull("message") as? String ?: "DS Bot 请求失败",
                            details = error?.optJSONObject("details"),
                        )
                    }
                    response.opt("value")
                } finally {
                    connection.disconnect()
                }
            }
        }

    /**
     * `timeline` 快照（Phase 1a，`docs/phase1-design.md` §3）：`/api/bot` 的新端点，
     * envelope 与 [bot] 完全一致（body `{endpoint, payload}`，响应 `{ok, value}`）。
     *
     * 请求 `{botId|roomId, since?, cursor?, max?}`，响应
     * `{sessionId|roomId, seq, rows[], cursor, running?, speaking?}`（解析见
     * [parseTimelineSnapshot]）。服务端把它进 READS 集合（只读主机可用，无需特殊处理）。
     *
     * 错误（含 `DshRpcException`）向上抛——shadow 调用方自己 runCatching，
     * 契约测试才能区分「失败」与「空快照」。
     */
    suspend fun timeline(request: TimelineRequest = TimelineRequest()): TimelineSnapshot =
        parseTimelineSnapshot(bot("timeline", request.toPayload()))
            ?: error("timeline 响应形状不对")

    /**
     * 上传一个附件到某个 Session，返回 prompt 可消费的 receipt。
     *
     * 对齐 DSH `dsh-client-file-upload` 的原始路由：
     * `POST /api/session/uploadFileBinary?sessionId=&name=`，body 是 `application/octet-stream`。
     */
    suspend fun uploadAttachment(
        sessionId: String,
        name: String,
        bytes: ByteArray,
    ): DshUploadReceipt = withContext(Dispatchers.IO) {
        withAuthRetry { auth ->
            val query = "sessionId=" + URLEncoder.encode(sessionId, "UTF-8") +
                "&name=" + URLEncoder.encode(name, "UTF-8")
            val connection = (URL(endpoint.baseUrl.trimEnd('/') + "/api/session/uploadFileBinary?" + query).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 30_000
                readTimeout = 120_000
                setRequestProperty("Content-Type", "application/octet-stream")
                setRequestProperty("Cookie", auth)
            }
            try {
                connection.outputStream.use { it.write(bytes) }
                val code = connection.responseCode
                checkAuthExpired(code)
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
                require(code in 200..299) { "上传附件失败：HTTP $code $text" }
                val response = JSONObject(text)
                if (!response.optBoolean("ok", false)) {
                    val error = response.optJSONObject("error")
                    throw DshRpcException(
                        code = error?.anyOrNull("code") as? String,
                        message = error?.anyOrNull("message") as? String ?: "上传附件失败",
                        details = error?.optJSONObject("details"),
                    )
                }
                val value = response.optJSONObject("value") ?: JSONObject()
                val file = value.optJSONObject("file") ?: JSONObject()
                val receiptId = value.optString("receiptId")
                require(receiptId.isNotEmpty()) { "上传附件没有返回 receiptId" }
                DshUploadReceipt(
                    receiptId = receiptId,
                    attachmentId = file.optString("attachmentId"),
                    name = file.optString("name").ifEmpty { name },
                    bytes = file.optLong("bytes"),
                )
            } finally {
                connection.disconnect()
            }
        }
    }

    /**
     * 读一个文件的内容：`GET /api/file?path=<绝对路径>`。
     *
     * 对齐 dsh-Go `files_api.dart` 的 HTTP 回落 —— 文件字节只有这条路径最简单可靠
     * （`workspaceFiles/readBytes` 的 HTTP 结果是 multipart，另一个通道才用得上）。
     * 上限由 Host 自己的图片字节上限决定（默认 20 MiB）。
     */
    suspend fun readFileBytes(absolutePath: String): ByteArray = withContext(Dispatchers.IO) {
        withAuthRetry { auth ->
            val encoded = URLEncoder.encode(absolutePath, "UTF-8").replace("+", "%20")
            val connection =
                (URL(endpoint.baseUrl.trimEnd('/') + "/api/file?path=" + encoded).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15_000
                    readTimeout = 300_000
                    setRequestProperty("Cookie", auth)
                }
            try {
                val code = connection.responseCode
                // 与 botValue / rpcValue 对齐：401/403 都可能是 cookie 失效，
                // 统一交给 withAuthRetry 重登一次再试。
                checkAuthExpired(code)
                if (code !in 200..299) {
                    val text = connection.errorStream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
                    throw DshRpcException(
                        code = when (code) {
                            404 -> "workspace-file/not-found"
                            413 -> "workspace-file/too-large"
                            else -> "workspace-file/http-$code"
                        },
                        message = "/api/file: HTTP $code ${text.trim()}".trim(),
                        details = null,
                    )
                }
                connection.inputStream.use { it.readBytes() }
            } finally {
                connection.disconnect()
            }
        }
    }

    /** 订阅 DS Bot SSE；断线自动重连，保留 event 名。 */
    fun feed(): Flow<BotFeedEvent> = flow {
        while (currentCoroutineContext().isActive) {
            try {
                val auth = cookie ?: login()
                val connection = (URL(endpoint.baseUrl + "api/bot/feed").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    // 读超时给有限值：引擎空闲时靠注释行 / 心跳维持连接，超过 [FEED_READ_TIMEOUT_MS]
                    // 一个字节都没有就视为死链（进程被冻结、网络栈半开），断开走下面的重连，
                    // 不再卡在无超时的 read 上永远不重连。
                    readTimeout = FEED_READ_TIMEOUT_MS
                    setRequestProperty("Accept", "text/event-stream")
                    setRequestProperty("Cookie", auth)
                }
                try {
                    connection.inputStream.bufferedReader().use { source ->
                        val lines = SseLineReader(source)
                        var event = "message"
                        val data = StringBuilder()
                        while (true) {
                            val line = lines.readLine() ?: break
                            when {
                                line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                                line.startsWith("data:") -> {
                                    // 同一条事件的 data 累计也要设上限，防止多行拼接绕过单行限制。
                                    if (data.length + line.length > MAX_SSE_EVENT_CHARS) {
                                        throw IllegalStateException("SSE 事件超过 $MAX_SSE_EVENT_CHARS 字符上限，断开重连")
                                    }
                                    if (data.isNotEmpty()) data.append("\n")
                                    data.append(line.removePrefix("data:").trim())
                                }
                                line.isBlank() -> {
                                    if (data.isNotEmpty()) {
                                        emit(BotFeedEvent(event, data.toString()))
                                        data.clear()
                                    }
                                    event = "message"
                                }
                            }
                        }
                        // 服务端在最后一条事件后没补空行就关闭连接时，缓冲区里仍是一条完整事件，
                        // 必须派发出去，否则 revision / live 会静默丢失。
                        if (data.isNotEmpty()) {
                            emit(BotFeedEvent(event, data.toString()))
                        }
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                cookie = null
                // 断线原因进 DshMuxLog 的统一出口（JVM 测试可换 sink），不再完全静默；
                // 无限重试的语义保持不变。
                DshMuxLog.log(
                    "feed 断线，${RECONNECT_DELAY_MS}ms 后重连：" +
                        (error.message ?: error::class.java.simpleName),
                )
            }
            // 无论异常断线还是被服务端正常关闭，都退避后再重连，避免立刻重连形成忙循环。
            if (currentCoroutineContext().isActive) delay(RECONNECT_DELAY_MS)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 有状态的 SSE 行读取器：行尾语义与 [BufferedReader.readLine] 对齐（\n / \r\n，
     * 去掉行尾 \r），但字符自己累积、跨调用保留读多出来的残留 —— 无换行的大流会在
     * 累计超过 [MAX_SSE_LINE_CHARS] 时立刻抛错断开，不会先在 BufferedReader.readLine
     * 的内部缓冲里把内存撑爆。
     */
    private class SseLineReader(private val source: BufferedReader) {
        private val buf = CharArray(1024)
        private var pos = 0
        private var limit = 0

        fun readLine(): String? {
            val line = StringBuilder()
            while (true) {
                if (pos >= limit) {
                    limit = source.read(buf)
                    pos = 0
                    if (limit < 0) return if (line.isEmpty()) null else line.toString()
                }
                val c = buf[pos++]
                if (c == '\n') return line.toString().removeSuffix("\r")
                line.append(c)
                if (line.length > MAX_SSE_LINE_CHARS) {
                    throw IllegalStateException("SSE 行超过 $MAX_SSE_LINE_CHARS 字符上限，断开重连")
                }
            }
        }
    }

    /**
     * Remote RPC（对齐 dsh-Go `packages/dsh_client/src/carrier.dart` 的 DshHttpCarrier）：
     * POST /api/<method>，body 为 envelope：
     * {"type":"client-request","rpcId":uuid,"method":method,"payload":{"args":{...}}}
     * 响应：{"type":"server-response","rpcId":...,"result":{"ok":true,"value":...}}，
     * 失败为 ok=false（HTTP 仍 200）。返回 result.value。
     */
    suspend fun rpc(
        method: String,
        args: JSONObject = JSONObject(),
        timeoutMs: Int = 30_000,
    ): JSONObject = rpcValue(method, args, timeoutMs) as? JSONObject ?: JSONObject()

    /** 同 [rpc]，但返回原始 value（数组/对象/布尔都保留），供列表类接口使用。 */
    suspend fun rpcValue(
        method: String,
        args: JSONObject = JSONObject(),
        timeoutMs: Int = 30_000,
    ): Any? = withContext(Dispatchers.IO) {
        val rpcId = java.util.UUID.randomUUID().toString()
        val envelope = JSONObject()
            .put("type", "client-request")
            .put("rpcId", rpcId)
            .put("method", method)
            .put("payload", JSONObject().put("args", args))
        withAuthRetry { auth ->
            val connection = (URL(endpoint.baseUrl + "api/" + method).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15_000
                readTimeout = timeoutMs
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Cookie", auth)
            }
            try {
                connection.outputStream.use { it.write(envelope.toString().toByteArray()) }
                val code = connection.responseCode
                checkAuthExpired(code)
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
                require(code == 200) { "DSH RPC $method 失败：HTTP $code $text" }
                val response = JSONObject(text)
                val result = response.optJSONObject("result") ?: error("DSH RPC $method 缺少 result")
                if (!result.optBoolean("ok", false)) {
                    val error = result.optJSONObject("error")
                    throw DshRpcException(
                        code = error?.anyOrNull("code") as? String,
                        message = error?.anyOrNull("message") as? String ?: "DSH RPC $method 失败：$result",
                        details = error?.optJSONObject("details"),
                    )
                }
                result.opt("value")
            } finally {
                connection.disconnect()
            }
        }
    }

    /** 账号服务：对齐 dsh-Go account_api.dart 的 Remote 调用。 */
    suspend fun account(method: String, args: JSONObject = JSONObject()): JSONObject =
        rpc("account/$method", args)

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val RECONNECT_DELAY_MS = 2_000L

        /** SSE 读超时：引擎有心跳 / 注释行维持连接，60s 无字节即判死链断开重连。 */
        const val FEED_READ_TIMEOUT_MS = 60_000

        /**
         * SSE 单行最大字符数（16MB）：正常事件是模型输出的 JSON，远小于此；
         * 超过只可能是无换行的异常流，立刻断开重连，防 readLine 式无限累积把内存撑爆。
         */
        const val MAX_SSE_LINE_CHARS = 16 * 1024 * 1024

        /** 单条 SSE 事件（多行 data 拼接后）的最大字符数，同 [MAX_SSE_LINE_CHARS]。 */
        const val MAX_SSE_EVENT_CHARS = 16 * 1024 * 1024
    }
}
