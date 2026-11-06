package com.dshm.bot.data.remote

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.net.URI
import java.net.URISyntaxException

/**
 * 插件市场的数据模型与「市场索引格式 1」解析器，对齐 dsh-Go
 * `packages/dsh_client/lib/src/plugins_api.dart`（`MarketIndex` /
 * `MarketIndexException` / `MarketIndexIssue` 一族）与
 * `packages/dsh_client/lib/src/plugins_api.dart` 的 `pluginManager` 一族结果。
 *
 * 解析规则与官方校验器一致，但**宽松**：一个插件坏了就跳过它并记一条 issue，
 * 只有整份文档根本不是格式 1 的索引时才抛 [MarketIndexException]。
 *
 * 这里全部是纯逻辑（不依赖 Android），所以能直接跑 JVM 单测。
 */

/** 索引读不出来（不是 JSON、不是 format 1、没有插件列表）。 */
class MarketIndexException(val path: String, val reason: String) : Exception("$path $reason")

/** 读索引时丢掉的一个条目或成员（合法索引的 issues 是空的）。 */
data class MarketIndexIssue(val path: String, val reason: String)

/** 一个声明好的分类或标签。 */
data class MarketDeclaration(val id: String, val title: EngineText)

/** 一个声明好的排序键。 */
data class MarketSortKey(val id: String, val title: EngineText, val descending: Boolean)

/** 版本的来源：npm 注册表或固定 commit 的 git 仓库。 */
sealed interface MarketSource {
    /** npm 包（`registry` 为 null = 用宿主自己的配置）。 */
    data class Npm(val registry: String?) : MarketSource

    /** 固定 commit 的 git 仓库。 */
    data class Git(val url: String, val commit: String, val path: String?) : MarketSource
}

/** 索引里的一个版本。 */
data class MarketVersion(
    val version: String,
    /** 发布时间（epoch 秒）。 */
    val publishedEpochSeconds: Long,
    val source: MarketSource,
    /** `engines.dsh` 的范围串（未校验）。 */
    val enginesDsh: String?,
)

/**
 * 索引里的一个插件。
 *
 * 注意格式 1 **没有**「危险插件」字段：市场用标签（`metadata.labels`）声明风险，
 * 这里按标签 id 约定识别（见 [MarketRisk]）。
 */
data class MarketPlugin(
    val name: String,
    val title: EngineText?,
    val description: EngineText?,
    val icon: String?,
    val author: String?,
    val homepage: String?,
    val repositoryUrl: String?,
    val repositoryDirectory: String?,
    val category: String?,
    val keywords: List<String>,
    val labels: List<String>,
    val sortKeys: Map<String, Double>,
    /** 非空，最新（SemVer 最高）在前。 */
    val versions: List<MarketVersion>,
    /**
     * 索引声明「构建脚本已获批准」的包（格式 1 没有这个字段，读到就用，读不到就空）。
     *
     * 非空时安装前要跟用户确认一遍：这些包会在装的时候执行构建脚本。
     */
    val approvedBuilds: List<String> = emptyList(),
) {
    val latest: MarketVersion get() = versions.first()

    /** 装它要传给 `pluginManager/installBundle` 的 spec。 */
    fun installSpec(): String = when (val source = latest.source) {
        is MarketSource.Npm -> "$name@${latest.version}"
        is MarketSource.Git -> {
            val base = if (source.url.endsWith(".git")) source.url else "${source.url}.git"
            val dir = source.path?.let { "&path:/$it" } ?: ""
            "git+$base#${source.commit}$dir"
        }
    }

    /** npm 源声明的注册表（装的时候优先用它）。 */
    val registry: String? get() = (latest.source as? MarketSource.Npm)?.registry

    /** 索引声明的危险标记。 */
    val dangerous: Boolean get() = MarketRisk.isDangerous(labels)

    /** 显示名：本地化标题优先，没有就用包名。 */
    fun displayTitle(language: String): String =
        title?.resolve(language)?.takeIf { it.isNotBlank() } ?: name
}

/**
 * 危险标记。
 *
 * 格式 1 没有专门的 risk 字段（dsh-Go 的官方索引把风险写在旁边的
 * `dsh-go.json` sidecar 里，App 读不到那个文件）。这里退而用索引**自己声明的标签**
 * 来识别：标签 id 命中 [DANGER_LABELS] 就当这个插件有风险，界面上给提示。
 * 这是本项目的约定，不是格式 1 的规范。
 */
object MarketRisk {
    val DANGER_LABELS: Set<String> = setOf(
        "danger",
        "dangerous",
        "malicious",
        "unsafe",
        "blocked",
        "untrusted",
    )

    fun isDangerous(labels: List<String>): Boolean =
        labels.any { it.lowercase() in DANGER_LABELS }
}

/** 读下来的一份市场索引。 */
data class MarketIndex(
    val url: String,
    val title: EngineText,
    val description: EngineText?,
    val categories: List<MarketDeclaration>,
    val labels: List<MarketDeclaration>,
    val sortKeys: List<MarketSortKey>,
    val plugins: List<MarketPlugin>,
    /** 被丢掉的条目和成员（合法索引是空列表）。 */
    val issues: List<MarketIndexIssue>,
)

/**
 * 市场索引格式 1 的解析器。
 *
 * 顶层形状（对齐 dsh-Go 的 `parseMarketIndex`）：
 * ```
 * { "format": 1,
 *   "title": { "en": "…", "zh": "…" },
 *   "description": "…",
 *   "metadata": { "categories": { id: { "title": … } },
 *                 "labels": { id: { "title": … } },
 *                 "sortKeys": { id: { "title": …, "order": "asc|desc" } } },
 *   "plugins": [ { "name": "@scope/pkg",
 *                  "title": …, "description": …, "icon": "https://…",
 *                  "author": "…", "homepage": "https://…",
 *                  "repository": { "url": "https://…", "directory": "sub/dir" },
 *                  "metadata": { "category": "tools", "keywords": [...],
 *                                "labels": [...], "sortKeys": { id: 1 } },
 *                  "versions": [ { "version": "1.2.3",
 *                                  "publishTimestamp": 1730000000,
 *                                  "source": { "type": "npm", "registry": "https://…" },
 *                                  "engines": { "dsh": ">=0.2.1" } } ] } ] }
 * ```
 */
object MarketIndexParser {

    private const val MAX_INLINE_ICON_BYTES = 32 * 1024

    private val languageIdRegex = Regex("^[a-z]{2,8}(-[a-z0-9]{1,8})*$")
    private val packageNameRegex = Regex("^(?:@[a-z0-9][a-z0-9._~-]*/)?[a-z0-9][a-z0-9._~-]*$")
    private val commitRegex = Regex("^[0-9a-f]{40}$")
    private val pathSegmentRegex = Regex("^[A-Za-z0-9._@+-]+$")
    private val dataIconRegex = Regex(
        "^data:(?:image/svg\\+xml|image/png|image/jpeg|image/webp);base64,([A-Za-z0-9+/]*={0,2})$",
    )
    private val semverRegex = Regex(
        "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)" +
            "(?:-((?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*)(?:\\.(?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?" +
            "(?:\\+[0-9a-zA-Z-]+(?:\\.[0-9a-zA-Z-]+)*)?$",
    )

    /** 读一段索引文本；不是 JSON 时抛 [MarketIndexException]。 */
    fun parse(text: String, url: String): MarketIndex {
        val root = try {
            JSONObject(text)
        } catch (error: Exception) {
            throw MarketIndexException("$", "不是 JSON（${error.message}）")
        }
        return parse(root, url)
    }

    /** 读一份已解码的索引对象。 */
    fun parse(root: JSONObject, url: String): MarketIndex {
        if (jsonInt(root.opt("format")) != 1) {
            throw MarketIndexException("$.format", "必须是 1")
        }
        val rawPlugins = root.opt("plugins")
        if (rawPlugins !is JSONArray) {
            throw MarketIndexException("$.plugins", "必须是数组")
        }
        val issues = mutableListOf<MarketIndexIssue>()

        val metadata = root.optJSONObject("metadata")
        val categories = declarations(metadata, "categories", issues) { id, member ->
            MarketDeclaration(id, strictLocalized(member.opt("title")) ?: return@declarations null)
        }
        val labels = declarations(metadata, "labels", issues) { id, member ->
            MarketDeclaration(id, strictLocalized(member.opt("title")) ?: return@declarations null)
        }
        val sortKeys = declarations(metadata, "sortKeys", issues) { id, member ->
            val title = strictLocalized(member.opt("title")) ?: return@declarations null
            val order = member.opt("order") as? String ?: return@declarations null
            if (order != "asc" && order != "desc") return@declarations null
            MarketSortKey(id, title, descending = order == "desc")
        }

        val names = mutableSetOf<String>()
        val plugins = mutableListOf<MarketPlugin>()
        for (position in 0 until rawPlugins.length()) {
            val path = "$.plugins[$position]"
            val plugin = plugin(rawPlugins.opt(position), path, url, categories, labels, sortKeys, issues)
                ?: continue
            if (!names.add(plugin.name)) {
                issues += MarketIndexIssue("$path.name", "重复的 ${plugin.name}")
                continue
            }
            plugins += plugin
        }

        return MarketIndex(
            url = url,
            title = strictLocalized(root.opt("title")) ?: EngineText(mapOf("" to hostOf(url))),
            description = strictLocalized(root.opt("description")),
            categories = categories,
            labels = labels,
            sortKeys = sortKeys,
            plugins = plugins,
            issues = issues,
        )
    }

    /**
     * 索引地址的候选顺序：先是 `pluginManager/registries` 的 `resolved`
     * （宿主最终生效的地址），再是 `plugin-market` 设置里配的索引。
     * 逐个试，第一个能读成格式 1 的就是它。
     */
    fun marketIndexCandidates(resolved: String?, settingsIndexes: List<String>): List<String> =
        (listOfNotNull(resolved?.trim()?.takeIf { it.isNotEmpty() }) + settingsIndexes)
            .filter { it.isNotEmpty() }
            .distinct()

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private inline fun <T> declarations(
        metadata: JSONObject?,
        key: String,
        issues: MutableList<MarketIndexIssue>,
        read: (String, JSONObject) -> T?,
    ): List<T> {        val raw = metadata?.opt(key)
        if (raw == null) {
            if (metadata != null && metadata.has(key)) {
                issues += MarketIndexIssue("$.metadata.$key", "必须是对象")
            }
            return emptyList()
        }
        val map = raw as? JSONObject
        if (map == null) {
            issues += MarketIndexIssue("$.metadata.$key", "必须是对象")
            return emptyList()
        }
        val out = mutableListOf<T>()
        for (id in map.keys()) {
            val path = "$.metadata.$key.$id"
            val member = map.opt(id) as? JSONObject
            val parsed = if (id.isEmpty() || member == null) null else read(id, member)
            if (parsed == null) {
                issues += MarketIndexIssue(path, "不是合法的声明")
            } else {
                out += parsed
            }
        }
        return out
    }

    private fun plugin(
        value: Any?,
        path: String,
        indexUrl: String,
        categories: List<MarketDeclaration>,
        labels: List<MarketDeclaration>,
        sortKeys: List<MarketSortKey>,
        issues: MutableList<MarketIndexIssue>,
    ): MarketPlugin? {
        val map = value as? JSONObject
        if (map == null) {
            issues += MarketIndexIssue(path, "必须是对象")
            return null
        }
        val name = map.opt("name") as? String
        if (name == null || name.length > 214 || !packageNameRegex.matches(name)) {
            issues += MarketIndexIssue("$path.name", "必须是合法的 npm 包名")
            return null
        }

        val versions = mutableListOf<MarketVersion>()
        val seen = mutableSetOf<String>()
        val rawVersions = map.opt("versions")
        if (rawVersions is JSONArray) {
            for (position in 0 until rawVersions.length()) {
                val version = version(rawVersions.opt(position))
                if (version == null || !seen.add(version.version)) {
                    issues += MarketIndexIssue("$path.versions[$position]", "不是合法的版本条目")
                    continue
                }
                versions += version
            }
        }
        if (versions.isEmpty()) {
            issues += MarketIndexIssue("$path.versions", "没有合法的版本")
            return null
        }
        versions.sortWith { left, right -> MarketSemver.compare(right.version, left.version) }

        val meta = map.optJSONObject("metadata")
        val category = meta?.opt("category") as? String
        val repository = map.optJSONObject("repository")
        val repositoryUrl = absoluteHttp(repository?.opt("url") as? String)
        val directory = (repository?.opt("directory") as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val homepage = absoluteHttp(map.opt("homepage") as? String)
        val declaredCategoryIds = categories.map { it.id }.toSet()
        val declaredLabelIds = labels.map { it.id }.toSet()
        val declaredSortKeyIds = sortKeys.map { it.id }.toSet()

        return MarketPlugin(
            name = name,
            title = lenientLocalized(map.opt("title")),
            description = lenientLocalized(map.opt("description")),
            icon = icon(map.opt("icon") as? String, indexUrl),
            author = (map.opt("author") as? String)?.trim()?.takeIf { it.isNotEmpty() },
            homepage = homepage?.toString(),
            repositoryUrl = repositoryUrl?.toString(),
            repositoryDirectory = if (repositoryUrl != null && directory != null && validPackagePath(directory)) {
                directory
            } else {
                null
            },
            category = category?.takeIf { it in declaredCategoryIds },
            keywords = stringList(meta?.opt("keywords")),
            labels = stringList(meta?.opt("labels")).filter { it in declaredLabelIds },
            sortKeys = sortKeyValues(meta?.opt("sortKeys"), declaredSortKeyIds),
            versions = versions,
            // 格式 1 没有「构建要批准」这个字段；两种放法都读一下，读不到就是空。
            approvedBuilds = stringList(map.opt("approvedBuilds"))
                .ifEmpty { stringList(meta?.opt("approvedBuilds")) },
        )
    }

    private fun version(value: Any?): MarketVersion? {
        val map = value as? JSONObject ?: return null
        val version = map.opt("version") as? String ?: return null
        if (!MarketSemver.isExact(version)) return null
        val published = jsonLong(map.opt("publishTimestamp")) ?: return null
        if (published < 0 || published >= 100_000_000_000L) return null
        val source = source(map.opt("source")) ?: return null
        val range = (map.optJSONObject("engines")?.opt("dsh") as? String)
            ?.trim()
            ?.takeIf { it.isNotEmpty() && !it.startsWith("workspace:") }
        return MarketVersion(
            version = version,
            publishedEpochSeconds = published,
            source = source,
            enginesDsh = range,
        )
    }

    private fun source(value: Any?): MarketSource? {
        val map = value as? JSONObject ?: return null
        return when (map.opt("type")) {
            "npm" -> {
                if (!map.has("registry")) {
                    MarketSource.Npm(registry = null)
                } else {
                    val registry = absoluteHttp(map.opt("registry") as? String) ?: return null
                    if (registry.rawQuery != null || registry.rawFragment != null) return null
                    MarketSource.Npm(registry = registry.toString())
                }
            }
            "git" -> {
                val commit = map.opt("commit") as? String ?: return null
                if (!commitRegex.matches(commit)) return null
                val url = absoluteHttp(map.opt("url") as? String, httpsOnly = true) ?: return null
                if (url.rawQuery != null || url.rawFragment != null) return null
                if (url.toString() != map.opt("url")) return null
                if (url.path.split('/').count { it.isNotEmpty() } < 2) return null
                if (Regex("\\.(?:tgz|tar\\.gz)$", RegexOption.IGNORE_CASE).containsMatchIn(url.path)) return null
                val dir = if (map.has("path")) {
                    (map.opt("path") as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                } else {
                    null
                }
                if (dir != null && !validPackagePath(dir)) return null
                MarketSource.Git(url = url.toString(), commit = commit, path = dir)
            }
            else -> null
        }
    }

    /** 格式 1 的本地化文本：对象必须用语言 id，且必须带 `en`。 */
    private fun strictLocalized(value: Any?): EngineText? {
        return when (value) {
            is String -> value.trim().takeIf { it.isNotEmpty() }?.let { EngineText(mapOf("" to it)) }
            is JSONObject -> {
                val values = LinkedHashMap<String, String>()
                for (key in value.keys()) {
                    val text = value.opt(key)
                    if (!languageIdRegex.matches(key) || text !is String || text.isBlank()) return null
                    values[key] = text
                }
                if (values.containsKey("en")) EngineText(values) else null
            }
            else -> null
        }
    }

    /** 插件自己的标题/描述：宽松（非字符串成员丢掉）。 */
    private fun lenientLocalized(value: Any?): EngineText? = EngineText.fromJson(value)

    private fun icon(value: String?, indexUrl: String): String? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (text.startsWith("data:")) {
            val payload = dataIconRegex.find(text)?.groupValues?.getOrNull(1) ?: return null
            if (payload.isEmpty() || payload.length % 4 != 0) return null
            val padding = if (payload.endsWith("==")) 2 else if (payload.endsWith("=")) 1 else 0
            if (payload.length / 4 * 3 - padding > MAX_INLINE_ICON_BYTES) return null
            return text
        }
        val base = try {
            URI(indexUrl)
        } catch (_: URISyntaxException) {
            return null
        }
        val resolved = try {
            base.resolve(text)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val sameOrigin = resolved.scheme == base.scheme && resolved.host == base.host && resolved.port == base.port
        if (resolved.scheme == "https" || (resolved.scheme == "http" && sameOrigin)) {
            return resolved.toString()
        }
        return null
    }

    private fun absoluteHttp(text: String?, httpsOnly: Boolean = false): URI? {
        val value = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val uri = try {
            URI(value)
        } catch (_: URISyntaxException) {
            return null
        }
        if (!uri.isAbsolute || uri.host.isNullOrEmpty()) return null
        if (uri.scheme != "https" && (httpsOnly || uri.scheme != "http")) return null
        if (!uri.userInfo.isNullOrEmpty()) return null
        return uri
    }

    private fun validPackagePath(text: String): Boolean =
        text.split('/').all { it != "." && it != ".." && pathSegmentRegex.matches(it) }

    private fun stringList(value: Any?): List<String> {
        val array = value as? JSONArray ?: return emptyList()
        val out = mutableListOf<String>()
        for (index in 0 until array.length()) {
            val item = array.opt(index)
            if (item is String && item.isNotEmpty()) out += item
        }
        return out
    }

    private fun sortKeyValues(value: Any?, declared: Set<String>): Map<String, Double> {
        val map = value as? JSONObject ?: return emptyMap()
        val out = LinkedHashMap<String, Double>()
        for (key in map.keys()) {
            if (key !in declared) continue
            val number = map.opt(key) as? Number ?: continue
            val double = number.toDouble()
            if (!double.isFinite()) continue
            out[key] = double
        }
        return out
    }

    private fun jsonLong(value: Any?): Long? = when (value) {
        is Number -> {
            val double = value.toDouble()
            if (double.isFinite() && double == double.toLong().toDouble()) double.toLong() else null
        }
        else -> null
    }

    private fun jsonInt(value: Any?): Int? = when (value) {
        is Number -> {
            val double = value.toDouble()
            if (double.isFinite() && double == double.toInt().toDouble()) double.toInt() else null
        }
        else -> null
    }

    private fun hostOf(url: String): String = try {
        URI(url).host.orEmpty()
    } catch (_: URISyntaxException) {
        ""
    }
}

/** SemVer 2.0.0 的判断与比较（对齐 dsh-Go 的 `isExactSemver` / `compareSemver`）。 */
object MarketSemver {
    private val regex = Regex(
        "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)" +
            "(?:-((?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*)(?:\\.(?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?" +
            "(?:\\+[0-9a-zA-Z-]+(?:\\.[0-9a-zA-Z-]+)*)?$",
    )

    fun isExact(version: String): Boolean = regex.matches(version)

    /** 负数表示 a 比 b 小；不合法的版本排在最前（彼此之间按字符串比）。 */
    fun compare(a: String, b: String): Int {
        val ma = regex.find(a)
        val mb = regex.find(b)
        if (ma == null || mb == null) {
            if (ma == null && mb == null) return a.compareTo(b)
            return if (ma == null) -1 else 1
        }
        for (group in 1..3) {
            val c = BigInteger(ma.groupValues[group]).compareTo(BigInteger(mb.groupValues[group]))
            if (c != 0) return c
        }
        val pa = ma.groupValues.getOrNull(4).orEmpty()
        val pb = mb.groupValues.getOrNull(4).orEmpty()
        if (pa.isEmpty() && pb.isEmpty()) return 0
        // 正式版排在预发布版之前。
        if (pa.isEmpty()) return 1
        if (pb.isEmpty()) return -1
        val xa = pa.split('.')
        val xb = pb.split('.')
        for (i in 0 until minOf(xa.size, xb.size)) {
            val na = xa[i].takeIf { it.all(Char::isDigit) }?.toBigIntegerOrNull()
            val nb = xb[i].takeIf { it.all(Char::isDigit) }?.toBigIntegerOrNull()
            val c = when {
                na != null && nb != null -> na.compareTo(nb)
                // 数字标识排在字母标识之前。
                na != null -> -1
                nb != null -> 1
                else -> xa[i].compareTo(xb[i])
            }
            if (c != 0) return c
        }
        return xa.size.compareTo(xb.size)
    }
}

// ---------------------------------------------------------------------------
// pluginManager 这一族的结果模型
// ---------------------------------------------------------------------------

/** `pluginManager/registries`：宿主用的包注册表配置。 */
data class PluginRegistries(
    /** 显式配置的注册表（null = 用 pnpm 自己的配置）。 */
    val registry: String?,
    val fallbackRegistries: List<String>,
    /** 最终生效的地址。 */
    val resolved: String?,
) {
    companion object {
        fun fromJson(raw: JSONObject?): PluginRegistries {
            if (raw == null) return PluginRegistries(null, emptyList(), null)
            val fallbacks = raw.optJSONArray("fallbackRegistries")
            val list = mutableListOf<String>()
            if (fallbacks != null) {
                for (index in 0 until fallbacks.length()) {
                    val item = fallbacks.opt(index)
                    if (item is String && item.isNotEmpty()) list += item
                }
            }
            return PluginRegistries(
                registry = (raw.opt("registry") as? String)?.takeIf { it.isNotEmpty() },
                fallbackRegistries = list,
                resolved = (raw.opt("resolved") as? String)?.takeIf { it.isNotEmpty() },
            )
        }
    }
}

/** 管理操作的结果里 `error` 字段。 */
data class PluginManagerError(
    val code: String,
    val diagnostic: String? = null,
    val incompatible: List<PluginIncompatibility> = emptyList(),
) {
    companion object {
        fun fromJson(raw: JSONObject?): PluginManagerError? {
            if (raw == null) return null
            val incompatible = mutableListOf<PluginIncompatibility>()
            raw.optJSONArray("incompatible")?.let { array ->
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    incompatible += PluginIncompatibility(
                        name = item.optString("name"),
                        version = item.optString("version"),
                        runtimeVersion = item.optString("runtimeVersion"),
                        requirements = stringMap(item.optJSONObject("requirements")),
                    )
                }
            }
            return PluginManagerError(
                code = raw.opt("code") as? String ?: "unknown",
                diagnostic = raw.opt("diagnostic") as? String,
                incompatible = incompatible,
            )
        }

        private fun stringMap(raw: JSONObject?): Map<String, String> {
            if (raw == null) return emptyMap()
            val out = LinkedHashMap<String, String>()
            for (key in raw.keys()) {
                val value = raw.opt(key)
                if (value is String) out[key] = value
            }
            return out
        }
    }
}

/** 宿主当前版本拒绝的包。 */
data class PluginIncompatibility(
    val name: String,
    val version: String,
    val runtimeVersion: String,
    val requirements: Map<String, String>,
)

/** 一次 pnpm 运行的摘要。 */
data class PluginPackageRun(
    val exitCode: Int? = null,
    val output: String = "",
    val truncated: Boolean = false,
    val logPath: String? = null,
    /** 失败的原因：`network` / `timeout` / `integrity` / `pnpm-missing` / `not-found` … */
    val kind: String? = null,
    val timedOut: Boolean = false,
    val incompatible: List<PluginIncompatibility> = emptyList(),
)

/** 操作在宿主上是怎么生效的。 */
enum class PluginApplication(val wire: String) {
    APPLIED("applied"),
    RESTART_REQUIRED("restart-required"),
    FAILED("failed"),
    CANCELLED("cancelled"),
    OVERRIDDEN("overridden"),
    UNKNOWN("");

    companion object {
        fun parse(value: String?): PluginApplication =
            entries.firstOrNull { it != UNKNOWN && it.wire == value } ?: UNKNOWN
    }
}

/** 安装 / 卸载 / 开关的结果。 */
data class PluginOperationResult(
    val changed: Boolean = false,
    val application: PluginApplication = PluginApplication.UNKNOWN,
    val restartReason: String? = null,
    /** `install` / `remove` / `enable`。 */
    val stage: String? = null,
    val target: String = "",
    val enabled: Boolean? = null,
    val error: PluginManagerError? = null,
    val warnings: List<String> = emptyList(),
    val packageResult: PluginPackageRun? = null,
    /** 这次安装加进来的包。 */
    val bundle: String? = null,
    val version: String? = null,
    /** 构建脚本被 pnpm 拦下的包：带着它们重试（`approvedBuilds`）才能装上。 */
    val pendingBuilds: List<String> = emptyList(),
    val approvedBuilds: List<String> = emptyList(),
    /** 依次问过的注册表（null = pnpm 自己的配置）。 */
    val registries: List<String> = emptyList(),
    /** `registry` 或 `spec-host`：最后一次失败没能到达哪里。 */
    val failedAt: String? = null,
) {
    val succeeded: Boolean
        get() = application == PluginApplication.APPLIED ||
            application == PluginApplication.RESTART_REQUIRED ||
            application == PluginApplication.OVERRIDDEN

    val needsRestart: Boolean get() = application == PluginApplication.RESTART_REQUIRED

    /** 失败时 pnpm 报的原因。 */
    val failureKind: String? get() = packageResult?.kind

    companion object {
        fun fromJson(raw: JSONObject?): PluginOperationResult {
            if (raw == null) return PluginOperationResult()
            val packageRun = raw.optJSONObject("packageResult")
            return PluginOperationResult(
                changed = raw.optBoolean("changed", false),
                application = PluginApplication.parse(raw.opt("application") as? String),
                restartReason = raw.opt("restartReason") as? String,
                stage = raw.opt("stage") as? String,
                target = raw.opt("target") as? String ?: "",
                enabled = if (raw.has("enabled")) raw.optBoolean("enabled", false) else null,
                error = PluginManagerError.fromJson(raw.optJSONObject("error")),
                warnings = stringList(raw.opt("warnings")),
                packageResult = if (packageRun == null) {
                    null
                } else {
                    PluginPackageRun(
                        exitCode = if (packageRun.has("exitCode")) packageRun.optInt("exitCode") else null,
                        output = packageRun.opt("output") as? String ?: "",
                        truncated = packageRun.optBoolean("truncated", false),
                        logPath = packageRun.opt("logPath") as? String,
                        kind = packageRun.opt("kind") as? String,
                        timedOut = packageRun.optBoolean("timedOut", false),
                        incompatible = emptyList(),
                    )
                },
                bundle = raw.opt("bundle") as? String,
                version = raw.opt("version") as? String,
                pendingBuilds = stringList(raw.opt("pendingBuilds")),
                approvedBuilds = stringList(raw.opt("approvedBuilds")),
                registries = stringList(raw.opt("registries")),
                failedAt = raw.opt("failedAt") as? String,
            )
        }

        private fun stringList(value: Any?): List<String> {
            val array = value as? JSONArray ?: return emptyList()
            val out = mutableListOf<String>()
            for (index in 0 until array.length()) {
                val item = array.opt(index)
                if (item is String && item.isNotEmpty()) out += item
            }
            return out
        }
    }
}

/** 一次操作失败时给人看的原话（引擎给的诊断优先，其次错误码）。 */
fun pluginFailureText(result: PluginOperationResult): String {
    val detail = result.error?.diagnostic?.takeIf { it.isNotBlank() }
        ?: result.error?.code?.takeIf { it.isNotBlank() }
        ?: result.failureKind?.takeIf { it.isNotBlank() }
    val prefix = result.stage?.takeIf { it.isNotBlank() }
    return listOfNotNull(prefix, detail).joinToString("：").ifEmpty { "操作失败" }
}

/** `pluginManager/cancelInstall` 的结果。 */
enum class PluginCancelStatus(val wire: String) {    CANCELLED("cancelled"),
    NOT_RUNNING("not-running"),
    TOO_LATE("too-late");

    companion object {
        fun parse(value: String?): PluginCancelStatus =
            entries.firstOrNull { it.wire == value } ?: NOT_RUNNING
    }
}

/** `pluginManager/inspect` 的结果：spec 指向什么，或者为什么被拒。 */
sealed interface PluginInspection {
    data class Accepted(
        /** `registry` / `path` / `git` / `tarball`。 */
        val kind: String,
        val name: String? = null,
        val version: String? = null,
        val description: String? = null,
        /** 这个包声明了 bundle patch（不知道时为 null）。 */
        val bundle: Boolean? = null,
        val registry: String? = null,
        val host: String? = null,
    ) : PluginInspection

    data class Refused(
        /** `network` / `unknown` / `invalid-spec` / `already-installed` / `not-found` … */
        val problem: String,
        val reason: String = "",
        val registries: List<String> = emptyList(),
    ) : PluginInspection

    companion object {
        fun fromJson(raw: JSONObject?): PluginInspection {
            if (raw == null) return Refused(problem = "unknown")
            if (raw.opt("status") == "refused") {
                return Refused(
                    problem = raw.opt("problem") as? String ?: "unknown",
                    reason = raw.opt("reason") as? String ?: "",
                    registries = stringList(raw.opt("registries")),
                )
            }
            return Accepted(
                kind = raw.opt("kind") as? String ?: "registry",
                name = raw.opt("name") as? String,
                version = raw.opt("version") as? String,
                description = raw.opt("description") as? String,
                bundle = if (raw.has("bundle")) raw.optBoolean("bundle", false) else null,
                registry = raw.opt("registry") as? String,
                host = raw.opt("host") as? String,
            )
        }

        private fun stringList(value: Any?): List<String> {
            val array = value as? JSONArray ?: return emptyList()
            val out = mutableListOf<String>()
            for (index in 0 until array.length()) {
                val item = array.opt(index)
                if (item is String && item.isNotEmpty()) out += item
            }
            return out
        }
    }
}
