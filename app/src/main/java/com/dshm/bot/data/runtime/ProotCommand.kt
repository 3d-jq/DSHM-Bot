package com.dshm.bot.data.runtime

/** 启动引擎成功后拿到的连接信息。 */
data class RuntimeEndpoint(val baseUrl: String, val token: String)

/**
 * proot 启动命令的纯逻辑：展开 manifest 里的占位符、拼 argv、解析 `dsh web` 输出的 token。
 * 不依赖 Android，便于单测。
 */
object ProotCommand {
    private val tokenRegex = Regex("^dsh web: (http://127\\.0\\.0\\.1:\\d+/)\\?token=([A-Za-z0-9_-]+)")

    /** 替换所有 `{KEY}` 占位符。 */
    fun expand(template: String, placeholders: Map<String, String>): String {
        var result = template
        placeholders.forEach { (key, value) -> result = result.replace(key, value) }
        return result
    }

    /**
     * 展开 argv 模板。`{GUEST_ENV}` 是独立元素：替换成零个或多个 `KEY=VALUE`。
     */
    fun buildArgv(
        template: List<String>,
        placeholders: Map<String, String>,
        guestEnv: List<String>,
    ): List<String> = template.flatMap { token ->
        if (token == GUEST_ENV) guestEnv else listOf(expand(token, placeholders))
    }

    fun buildEnv(template: Map<String, String>, placeholders: Map<String, String>): Map<String, String> =
        template.mapValues { (_, value) -> expand(value, placeholders) }

    /** 从 `dsh web: http://127.0.0.1:PORT/?token=...` 一行解析出地址与 token。 */
    fun parseToken(line: String): RuntimeEndpoint? {
        val match = tokenRegex.find(line.trim()) ?: return null
        return RuntimeEndpoint(baseUrl = match.groupValues[1], token = match.groupValues[2])
    }

    const val GUEST_ENV = "{GUEST_ENV}"
}
