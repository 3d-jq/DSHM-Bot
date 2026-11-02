package com.dshm.bot.data.remote

import com.dshm.bot.data.runtime.RuntimeEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 本机 DSH 的最小客户端：
 * 1) `GET /?token=` 换 `dsh-auth-*` cookie；
 * 2) `POST /api/bot` 发 Bot 请求；
 * 3) `GET /api/bot/feed` 读 SSE 事件流。
 */
class DshClient(private val endpoint: RuntimeEndpoint) {

    @Volatile
    private var cookie: String? = null

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

    /** 调 `/api/bot`；[body] 是 JSON 对象（如 {"type":"state"}）。 */
    suspend fun bot(body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val auth = cookie ?: login()
        val connection = (URL(endpoint.baseUrl + "api/bot").openConnection() as HttpURLConnection).apply {
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
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
            require(code in 200..299) { "DSH 请求失败：HTTP $code $text" }
            JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    /** 订阅 Bot 事件流（SSE 的 `data:` 行）。 */
    fun feed(): Flow<String> = flow {
        val auth = cookie ?: login()
        val connection = (URL(endpoint.baseUrl + "api/bot/feed").openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 0
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("Cookie", auth)
        }
        try {
            connection.inputStream.bufferedReader().useLines { lines ->
                lines.filter { it.startsWith("data:") }
                    .forEach { emit(it.removePrefix("data:").trim()) }
            }
        } finally {
            connection.disconnect()
        }
    }.flowOn(Dispatchers.IO)
}
