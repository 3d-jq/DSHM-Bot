package com.dshm.bot.data.runtime

import org.json.JSONObject

/**
 * 诊断报告：对齐 dsh-Go `app/lib/ui/settings/diagnostics_page.dart` 的 `report`。
 *
 * 报告里**永远不含** token 与 API Key 明文（token 固定写成 null，
 * API Key 只给「有没有配」这一个布尔值）。
 *
 * 纯函数，不依赖 Android，便于 JVM 单测覆盖。
 */
@Suppress("LongParameterList")
fun buildDiagnosticsReport(
    phase: String,
    abi: String,
    bundledVersion: String,
    installedVersion: String?,
    dshVersion: String,
    nodeVersion: String,
    baseUrl: String?,
    error: String?,
    logLines: Int,
    hasApiKey: Boolean,
    connection: String,
): String {
    val runtime = JSONObject().apply {
        put("phase", phase)
        put("abi", abi)
        put("bundledVersion", bundledVersion)
        put("installedVersion", installedVersion ?: JSONObject.NULL)
        put("dshVersion", dshVersion)
        put("nodeVersion", nodeVersion)
        put("baseUrl", baseUrl ?: JSONObject.NULL)
        put("token", JSONObject.NULL)
        put("error", error ?: JSONObject.NULL)
        put("logLines", logLines)
    }
    val root = JSONObject().apply {
        put("runtime", runtime)
        put("connection", connection)
        put("settings", JSONObject().put("hasApiKey", hasApiKey))
    }
    return root.toString(2)
}
