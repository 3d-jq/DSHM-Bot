package com.dshm.bot.data.runtime

/**
 * 日志展示 / 落盘前打码：token、API Key 不显示给用户，也不留在盘上。
 *
 * 覆盖面按「真会出现的形态」给（宁多打一点，别漏）：
 * - `token=<值>`：`dsh web: http://…/?token=…` 这种；
 * - `api_key=…` / `api-key: …`；
 * - `sk-…`（DeepSeek 的 key 前缀，裸着出现时也要打）；
 * - `Bearer <值>`（Authorization 头，引擎与插件日志里常见）。
 */
object TextMasking {
    private val token = Regex("token=[A-Za-z0-9_-]+")
    private val apiKey = Regex("(?i)(api[_-]?key\\s*[=:]\\s*)[A-Za-z0-9_.-]+")
    private val skKey = Regex("sk-[A-Za-z0-9_-]{8,}")
    private val bearer = Regex("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+")

    private val ansi = Regex("\\u001B\\[[0-9;?]*[ -/]*[@-~]")

    fun maskSecrets(text: String): String = bearer.replace(
        skKey.replace(
            apiKey.replace(token.replace(ansi.replace(text, ""), "token=***"), "$1***"),
            "sk-***",
        ),
        "$1***",
    )
}
