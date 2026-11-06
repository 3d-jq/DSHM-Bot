package com.dshm.bot.data.remote

import com.dshm.bot.data.runtime.RuntimeEndpoint
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Executors

/**
 * 极简本机 DSH 服务端，只用 JDK 的 ServerSocket，不引入 MockWebServer 等新依赖。
 * 用来覆盖真实 HTTP 行为：Set-Cookie、401、SSE 流结尾、首次请求失败等。
 */
internal class FakeDshServer : AutoCloseable {

    data class Request(
        val method: String,
        val path: String,
        val cookie: String?,
        /** 已读掉的请求体，供契约测试断言 RPC envelope。 */
        val body: String = "",
    )

    data class Response(
        val code: Int,
        val headers: Map<String, String> = emptyMap(),
        val body: String = "",
    )

    private val serverSocket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable).apply { isDaemon = true }
    }
    private val seen = Collections.synchronizedList(mutableListOf<Request>())

    @Volatile
    var responder: (Request) -> Response = { Response(404) }

    val endpoint: RuntimeEndpoint
        get() = RuntimeEndpoint("http://127.0.0.1:${serverSocket.localPort}/", "test-token")

    fun start() {
        executor.submit {
            while (!serverSocket.isClosed) {
                val socket = try {
                    serverSocket.accept()
                } catch (_: Exception) {
                    return@submit
                }
                executor.submit { handle(socket) }
            }
        }
    }

    fun requests(): List<Request> = synchronized(seen) { seen.toList() }

    private fun handle(socket: Socket) {
        socket.use { connection ->
            connection.soTimeout = 5_000
            // 全程按字节读：header 用行读取，body 按 Content-Length 取字节后 UTF-8 解码。
            // Content-Length 是字节数，若按字符数读，中文等多字节字符会让最后一次 read
            // 一直阻塞到超时，服务端不写响应，客户端看到 "Unexpected end of file"。
            val stream = connection.getInputStream()
            fun readLine(): String? {
                val buffer = java.io.ByteArrayOutputStream()
                while (true) {
                    val byte = stream.read()
                    if (byte < 0) return if (buffer.size() == 0) null else buffer.toString("UTF-8")
                    if (byte == '\n'.code) return buffer.toString("UTF-8").removeSuffix("\r")
                    buffer.write(byte)
                }
            }
            val requestLine = readLine() ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1]
            var cookie: String? = null
            var contentLength = 0
            while (true) {
                val line = readLine() ?: break
                if (line.isBlank()) break
                if (line.startsWith("Cookie:", ignoreCase = true)) {
                    cookie = line.substringAfter(':').trim()
                }
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                }
            }
            var body = ""
            if (contentLength > 0) {
                val bytes = ByteArray(contentLength)
                var offset = 0
                while (offset < contentLength) {
                    val read = stream.read(bytes, offset, contentLength - offset)
                    if (read < 0) break
                    offset += read
                }
                body = String(bytes, 0, offset, Charsets.UTF_8)
            }
            val request = Request(method, path, cookie, body)
            seen.add(request)
            val response = responder(request)
            writeResponse(connection, response)
        }
    }

    private fun writeResponse(connection: Socket, response: Response) {
        val body = response.body.toByteArray(Charsets.UTF_8)
        val reason = when (response.code) {
            200 -> "OK"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            500 -> "Internal Server Error"
            else -> "Unknown"
        }
        val head = buildString {
            append("HTTP/1.1 ${response.code} $reason\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n")
            response.headers.forEach { (key, value) -> append("$key: $value\r\n") }
            append("\r\n")
        }
        connection.getOutputStream().apply {
            write(head.toByteArray(Charsets.UTF_8))
            write(body)
            flush()
        }
    }

    override fun close() {
        serverSocket.close()
        executor.shutdownNow()
    }

    companion object {
        fun cookie(value: String): Response =
            Response(200, mapOf("Set-Cookie" to "dsh-auth-$value=abc; Path=/"))

        const val LOGIN_PATH_PREFIX = "/?token="
    }
}
