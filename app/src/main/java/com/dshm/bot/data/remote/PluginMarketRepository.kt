package com.dshm.bot.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * 插件市场的引擎通道（对齐 dsh-Go `packages/dsh_client/lib/src/plugins_api.dart` 的
 * `PluginsApi` 扩展）。
 *
 * 写操作只有一条路：`pluginManager` 这一族 RPC（每个宿主都一样）。
 * 市场索引是 App 自己读的远程 JSON（格式 1），见 [MarketIndexParser]。
 */
class PluginMarketRepository(private val client: DshClient) {

    /** `pluginManager/registries`：宿主用的包注册表配置。 */
    suspend fun registries(): PluginRegistries =
        PluginRegistries.fromJson(client.rpc("pluginManager/registries"))

    /** `pluginManager/listPlugins`：宿主里已装载的条目。 */
    suspend fun listPlugins(): List<PluginMarketEntry> {
        val array = client.rpcValue("pluginManager/listPlugins") as? JSONArray ?: return emptyList()
        val out = mutableListOf<PluginMarketEntry>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            out += PluginMarketEntry.fromJson(item)
        }
        return out
    }

    /** `pluginManager/inspect`：看一个 spec 指向什么（装之前先问一次）。 */
    suspend fun inspect(spec: String, registry: String? = null): PluginInspection {
        val args = JSONObject().put("spec", spec)
        if (registry != null) args.put("options", JSONObject().put("registry", registry))
        return PluginInspection.fromJson(client.rpc("pluginManager/inspect", args))
    }

    /**
     * `pluginManager/installBundle`：**很慢**（pnpm 安装 + 激活），所以给 30 分钟超时。
     *
     * 传 [requestId] 就能用 [cancelInstall] 取消、用 [waitForInstall] 兜底。
     */
    suspend fun installBundle(
        spec: String,
        requestId: String = newRequestId(),
        enabled: Boolean? = null,
        saveExact: Boolean? = null,
        approvedBuilds: List<String>? = null,
        registry: String? = null,
    ): PluginOperationResult {
        val options = JSONObject()
        if (enabled != null) options.put("enabled", enabled)
        if (saveExact != null) options.put("saveExact", saveExact)
        options.put("requestId", requestId)
        if (approvedBuilds != null) options.put("approvedBuilds", JSONArray(approvedBuilds))
        if (registry != null) options.put("registry", registry)
        val args = JSONObject().put("spec", spec).put("options", options)
        return PluginOperationResult.fromJson(
            client.rpc("pluginManager/installBundle", args, timeoutMs = INSTALL_TIMEOUT_MS),
        )
    }

    /** `pluginManager/cancelInstall`：停掉一次安装。 */
    suspend fun cancelInstall(requestId: String): PluginCancelStatus {
        val value = client.rpc(
            "pluginManager/cancelInstall",
            JSONObject().put("requestId", requestId),
            timeoutMs = CANCEL_TIMEOUT_MS,
        )
        return PluginCancelStatus.parse(value.opt("status") as? String)
    }

    /** `pluginManager/waitForInstall`：等一次安装的结果（没有在跑就是 null）。 */
    suspend fun waitForInstall(requestId: String): PluginOperationResult? {
        val value = client.rpcValue(
            "pluginManager/waitForInstall",
            JSONObject().put("requestId", requestId),
            timeoutMs = INSTALL_TIMEOUT_MS,
        ) as? JSONObject ?: return null
        return PluginOperationResult.fromJson(value)
    }

    /** `pluginManager/removeBundle`：卸载一个包。 */
    suspend fun removeBundle(name: String): PluginOperationResult =
        PluginOperationResult.fromJson(
            client.rpc("pluginManager/removeBundle", JSONObject().put("name", name), timeoutMs = REMOVE_TIMEOUT_MS),
        )

    /**
     * 市场索引地址的候选：
     * 1. `pluginManager/registries` 的 `resolved`（宿主最终生效的地址）；
     * 2. `plugin-market` 设置里配的索引列表。
     *
     * 挨个试，第一个能读成格式 1 的就是它。
     */
    suspend fun marketIndexCandidates(): List<String> {
        val fromRegistries = runCatching { registries().resolved }.getOrNull()
        val fromSettings = runCatching { pluginMarketIndexes() }.getOrDefault(emptyList())
        return MarketIndexParser.marketIndexCandidates(fromRegistries, fromSettings)
    }

    /** `settings/describe` 里 `plugin-market` 命名空间的 `indexes`（索引地址列表）。 */
    suspend fun pluginMarketIndexes(): List<String> {
        val root = client.rpc("settings/describe")
        val namespaces = root.optJSONArray("namespaces") ?: return emptyList()
        for (index in 0 until namespaces.length()) {
            val item = namespaces.optJSONObject(index) ?: continue
            if (item.optString("ns") != PLUGIN_MARKET_NAMESPACE) continue
            // 合并后的 value 里没有时再找 user 层（索引是用户配的）。
            val indexes = item.optJSONObject("value")?.optJSONArray("indexes")
                ?: item.optJSONObject("user")?.optJSONArray("indexes")
                ?: return emptyList()
            val out = mutableListOf<String>()
            for (position in 0 until indexes.length()) {
                val url = indexes.opt(position)
                if (url is String && url.isNotBlank()) out += url
            }
            return out
        }
        return emptyList()
    }

    /**
     * 用 HTTP GET 拉一份市场索引并解析。
     *
     * 拉不到 / 不是 JSON / 不是 format 1 都抛 [MarketIndexException]，
     * 调用方把原因显示出来，别崩。
     */
    suspend fun fetchMarketIndex(url: String): MarketIndex = withContext(Dispatchers.IO) {
        val text = download(url)
        MarketIndexParser.parse(text, url)
    }

    private fun download(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = connection.responseCode
            require(code in 200..299) { "HTTP $code" }
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            require(text.length <= MAX_INDEX_CHARS) { "索引太大（${text.length} 字符）" }
            return text
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val PLUGIN_MARKET_NAMESPACE = "plugin-market"

        /** 装一次要跑 pnpm + 激活，给 30 分钟。 */
        const val INSTALL_TIMEOUT_MS = 30 * 60 * 1000

        /** 取消安装：5 分钟。 */
        const val CANCEL_TIMEOUT_MS = 5 * 60 * 1000

        /** 卸载：10 分钟。 */
        const val REMOVE_TIMEOUT_MS = 10 * 60 * 1000

        /** 索引最大 32 MB（对齐 dsh-Go 的上限）。 */
        const val MAX_INDEX_CHARS = 32 * 1024 * 1024

        /** 一次安装的请求 id（用来取消 / 兜底查询）。 */
        fun newRequestId(): String = UUID.randomUUID().toString()
    }
}

/**
 * `pluginManager/listPlugins` 的一个条目。
 *
 * 和 `pluginInventory/list` 的条目形状一样，但那边是「引擎清单」，这边是
 * 「宿主实际装载的条目」——市场用它判断某个包是不是已经装上了。
 */
data class PluginMarketEntry(
    val entryId: String,
    val moduleName: String,
    val enabled: Boolean,
    val fiberPhase: String?,
    val meta: PluginDisplayMeta?,
) {
    companion object {
        fun fromJson(raw: JSONObject): PluginMarketEntry = PluginMarketEntry(
            entryId = raw.optString("entryId"),
            moduleName = raw.optString("moduleName"),
            enabled = raw.optBoolean("enabled", false),
            fiberPhase = raw.anyOrNull("fiberPhase") as? String,
            meta = PluginDisplayMeta.fromJson(raw.optJSONObject("meta")),
        )
    }
}
