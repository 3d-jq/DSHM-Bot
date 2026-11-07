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
                    response.optJSONObject("value") ?: JSONObject()
                } finally {
                    connection.disconnect()
                }
            }
        }

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

    /** 订阅 DS Bot SSE；断线自动重连，保留 event 名。 */
    fun feed(): Flow<BotFeedEvent> = flow {
        while (currentCoroutineContext().isActive) {
            try {
                val auth = cookie ?: login()
                val connection = (URL(endpoint.baseUrl + "api/bot/feed").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 0
                    setRequestProperty("Accept", "text/event-stream")
                    setRequestProperty("Cookie", auth)
                }
                try {
                    connection.inputStream.bufferedReader().use { reader ->
                        var event = "message"
                        val data = StringBuilder()
                        while (true) {
                            val line = reader.readLine() ?: break
                            when {
                                line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                                line.startsWith("data:") -> {
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
            } catch (_: Exception) {
                cookie = null
            }
            // 无论异常断线还是被服务端正常关闭，都退避后再重连，避免立刻重连形成忙循环。
            if (currentCoroutineContext().isActive) delay(RECONNECT_DELAY_MS)
        }
    }.flowOn(Dispatchers.IO)

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
    }
}
