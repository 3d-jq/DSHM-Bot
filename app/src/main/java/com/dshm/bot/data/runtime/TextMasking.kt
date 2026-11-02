package com.dshm.bot.data.runtime

/** 日志展示前打码：token/API Key 不显示给用户、不进剪贴板。 */
object TextMasking {
    private val token = Regex("token=[A-Za-z0-9_-]+")
    private val apiKey = Regex("(?i)(api[_-]?key\\s*[=:]\\s*)[A-Za-z0-9_.-]+")

    private val ansi = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")

    fun maskSecrets(text: String): String =
        apiKey.replace(token.replace(ansi.replace(text, ""), "token=***"), "$1***")
}

