package com.dshm.bot.data.remote

import org.json.JSONObject
import java.net.URI

/** 一次模型发现的回执：端点返回的模型 id，以及真正请求成功的 Base URL。 */
data class DiscoveredModels(val ids: List<String>, val baseUrl: String)

/** dsh-Go `normalizeBaseUrl`：去尾斜杠；Anthropic 自己拼 `/v1/messages`，不要重复 `/v1`。 */
fun normalizeDiscoveryBaseUrl(raw: String, api: String): String {
    var url = raw.trim().trimEnd('/')
    if (api == "anthropic-messages") {
        url = url.removeSuffix("/v1")
    }
    return url
}

/** dsh-Go `baseUrlError`：http(s) + 主机 + 不带 query/fragment。 */
fun isValidProviderBaseUrl(raw: String): Boolean {
    val url = raw.trim()
    if (url.isEmpty() || url.any { it.isWhitespace() }) return false
    return runCatching {
        val uri = URI(url)
        val scheme = uri.scheme?.lowercase()
        (scheme == "http" || scheme == "https") &&
            !uri.host.isNullOrEmpty() &&
            uri.rawQuery == null &&
            uri.rawFragment == null
    }.getOrDefault(false)
}

/**
 * dsh-Go 的 `ask(baseUrl)` / 404 后 `ask(baseUrl/v1)`：裸域名且非 Anthropic 时
 * 才给第二个候选；第二个成功时把生效 URL 回填表单。
 */
fun discoveryBaseUrlCandidates(raw: String, api: String): List<String> {
    val normalized = normalizeDiscoveryBaseUrl(raw, api)
    val bare = runCatching { URI(normalized).path.let { it.isNullOrBlank() || it == "/" } }
        .getOrDefault(false)
    return if (api != "anthropic-messages" && bare) {
        listOf(normalized, "$normalized/v1")
    } else {
        listOf(normalized)
    }
}

/**
 * `llm/discoverModels` 的 request：`provider` 让引擎按已存 route 解析 Key，
 * `baseURL/api` 告诉引擎问哪个端点，`apiKey` 只在用户当场输入了新 Key 时出现。
 */
fun discoveryRequest(
    baseUrl: String,
    api: String,
    apiKey: String,
    route: String?,
): JSONObject = JSONObject().apply {
    if (!route.isNullOrBlank()) put("provider", route.trim())
    put("baseURL", baseUrl)
    put("api", api)
    if (apiKey.isNotBlank()) put("apiKey", apiKey.trim())
}

fun isDiscoveryNotFound(error: Throwable): Boolean =
    error is DshRpcException && error.message?.contains("answered 404") == true
