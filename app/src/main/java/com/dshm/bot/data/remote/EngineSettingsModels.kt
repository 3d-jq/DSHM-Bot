package com.dshm.bot.data.remote

import org.json.JSONArray
import org.json.JSONObject

/**
 * 引擎设置协议模型：对齐 dsh-Go `packages/dsh_client/lib/src/engine_settings_api.dart`。
 *
 * - `settings/describe` 每个 namespace 带 schemastery schema；
 * - `value` 是解析后的配置，`base` 是组合基线，`user` 是用户层；
 * - `role('secret')` 的值不会返回，只给 `secrets` 槽位；
 * - 写操作是 `settings/mutate` 的 `set` / `unset`。
 */
data class EngineText(val values: Map<String, String>) {

    /** 与 dsh_client 的 LocalizedText.resolve 同一套优先级。 */
    fun resolve(language: String): String {
        if (values.isEmpty()) return ""
        val wanted = language.lowercase().replace('_', '-')
        val base = wanted.substringBefore('-')
        values[wanted]?.let { return it }
        values[base]?.let { return it }
        values.entries.firstOrNull { it.key.startsWith("$base-") }?.let { return it.value }
        values[""]?.let { plain ->
            val chinese = plain.any { it.code in 0x3400..0x9FFF }
            if (chinese == (base == "zh")) return plain
        }
        values.entries.firstOrNull { it.key == "en" || it.key.startsWith("en-") }?.let { return it.value }
        values[""]?.let { return it }
        return values.values.first()
    }

    companion object {
        fun fromJson(value: Any?): EngineText? = when (value) {
            is String -> value.takeIf { it.isNotBlank() }?.let { EngineText(mapOf("" to it)) }
            is JSONObject -> {
                val out = LinkedHashMap<String, String>()
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val text = value.opt(key)
                    if (text is String && text.isNotBlank()) {
                        out[key.lowercase().replace('_', '-')] = text
                    }
                }
                out.takeIf { it.isNotEmpty() }?.let(::EngineText)
            }
            else -> null
        }
    }
}

data class FieldMeta(
    val defaultValue: Any? = null,
    val hasDefault: Boolean = false,
    val required: Boolean = false,
    val hidden: Boolean = false,
    val disabled: Boolean = false,
    val collapse: Boolean = false,
    val role: String? = null,
    val title: EngineText? = null,
    val description: EngineText? = null,
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null,
) {
    companion object {
        fun fromJson(raw: JSONObject?): FieldMeta {
            if (raw == null) return FieldMeta()
            return FieldMeta(
                defaultValue = raw.anyOrNull("default"),
                hasDefault = raw.has("default"),
                required = raw.optBoolean("required", false),
                hidden = raw.optBoolean("hidden", false),
                disabled = raw.optBoolean("disabled", false),
                collapse = raw.optBoolean("collapse", false),
                role = raw.anyOrNull("role") as? String,
                title = EngineText.fromJson(raw.anyOrNull("title")),
                description = EngineText.fromJson(raw.anyOrNull("description")),
                min = raw.numberOrNull("min"),
                max = raw.numberOrNull("max"),
                step = raw.numberOrNull("step"),
            )
        }
    }
}

/** 一个 schemastery 节点。`dict` / `list` / `inner` / `sKey` 用 uid 引用，可能成环。 */
class SettingsSchemaNode private constructor(
    val type: String,
    val meta: FieldMeta,
    val constValue: Any?,
    val hasValue: Boolean,
    val bits: Map<String, Int>,
) {
    private val dict = LinkedHashMap<String, SettingsSchemaNode>()
    private val list = mutableListOf<SettingsSchemaNode>()
    private var innerNode: SettingsSchemaNode? = null
    private var sKeyNode: SettingsSchemaNode? = null

    val dictFields: Map<String, SettingsSchemaNode> get() = dict
    val listMembers: List<SettingsSchemaNode> get() = list
    val inner: SettingsSchemaNode? get() = innerNode
    val sKey: SettingsSchemaNode? get() = sKeyNode

    /** transform / lazy 解开后的节点。 */
    val effective: SettingsSchemaNode
        get() {
            var node: SettingsSchemaNode = this
            var guard = 0
            while ((node.type == "transform" || node.type == "lazy") &&
                node.innerNode != null &&
                guard < 8
            ) {
                node = node.innerNode!!
                guard++
            }
            return node
        }

    val isSecret: Boolean get() = meta.role == "secret"
    val isCredentialRef: Boolean get() = meta.role == "credential-ref"

    /** object / intersect 的字段；其它类型返回 null。 */
    fun fields(): Map<String, SettingsSchemaNode>? = fields(0)

    private fun fields(depth: Int): Map<String, SettingsSchemaNode>? {
        if (depth > 24) return null
        val node = effective
        return when (node.type) {
            "object" -> node.dict
            "intersect" -> {
                val merged = LinkedHashMap<String, SettingsSchemaNode>()
                for (member in node.list) {
                    val fields = member.fields(depth + 1) ?: return null
                    merged.putAll(fields)
                }
                merged
            }
            else -> null
        }
    }

    /** union 里去掉 null 常量后的成员。 */
    val members: List<SettingsSchemaNode>
        get() = list.map { it.effective }.filterNot { it.type == "const" && it.constValue == null }

    /** 枚举值；不是枚举形态时返回 null。 */
    val enumValues: List<Any?>?
        get() {
            val node = effective
            if (node.type == "const") return listOf(node.constValue)
            if (node.type != "union") return null
            val members = node.members
            if (members.isEmpty() || members.any { it.type != "const" }) return null
            return members.map { it.constValue }
        }

    /** union 显式允许 null。 */
    val nullable: Boolean
        get() {
            val node = effective
            if (node.type != "union") return false
            return node.listMembers.any { it.effective.type == "const" && it.effective.constValue == null }
        }

    internal fun addDict(name: String, node: SettingsSchemaNode) {
        dict[name] = node
    }

    internal fun addList(node: SettingsSchemaNode) {
        list.add(node)
    }

    internal fun setInner(node: SettingsSchemaNode?) {
        innerNode = node
    }

    internal fun setSKey(node: SettingsSchemaNode?) {
        sKeyNode = node
    }

    companion object {
        fun unknown(): SettingsSchemaNode =
            SettingsSchemaNode("unknown", FieldMeta(), null, false, emptyMap())

        fun fromJson(json: Any?): SettingsSchemaNode {
            val obj = json as? JSONObject ?: return unknown()
            val refs = obj.optJSONObject("refs")
            if (refs == null) return parseInline(obj)

            val nodes = LinkedHashMap<String, SettingsSchemaNode>()
            val keys = refs.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val raw = refs.optJSONObject(key) ?: continue
                nodes[key] = shell(raw)
            }
            fun ref(uid: Any?): SettingsSchemaNode? {
                val id = when (uid) {
                    is String -> uid
                    is Number -> uid.toString()
                    else -> null
                } ?: return null
                return nodes[id] ?: unknown()
            }
            for ((key, node) in nodes) {
                val raw = refs.optJSONObject(key) ?: continue
                raw.optJSONObject("dict")?.let { dict ->
                    val names = dict.keys()
                    while (names.hasNext()) {
                        val name = names.next()
                        node.addDict(name, ref(dict.opt(name)) ?: unknown())
                    }
                }
                raw.optJSONArray("list")?.let { array ->
                    for (i in 0 until array.length()) {
                        node.addList(ref(array.opt(i)) ?: unknown())
                    }
                }
                node.setInner(ref(raw.opt("inner")))
                node.setSKey(ref(raw.opt("sKey")))
            }
            return ref(obj.opt("uid")) ?: unknown()
        }

        /** schema 缺失时，按 value 的形状兜底推断，至少能显示/编辑顶层字段。 */
        fun infer(value: Any?): SettingsSchemaNode = when (value) {
            is Boolean -> SettingsSchemaNode("boolean", FieldMeta(), null, false, emptyMap())
            is Number -> SettingsSchemaNode("number", FieldMeta(), null, false, emptyMap())
            is String -> SettingsSchemaNode("string", FieldMeta(), null, false, emptyMap())
            is JSONObject -> {
                val node = SettingsSchemaNode("object", FieldMeta(), null, false, emptyMap())
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    node.addDict(key, infer(value.anyOrNull(key)))
                }
                node
            }
            is JSONArray -> {
                val node = SettingsSchemaNode("array", FieldMeta(), null, false, emptyMap())
                for (i in 0 until value.length()) node.addList(infer(value.opt(i)))
                node
            }
            else -> unknown()
        }

        private fun shell(raw: JSONObject): SettingsSchemaNode {
            val bits = LinkedHashMap<String, Int>()
            raw.optJSONObject("bits")?.let { rawBits ->
                val keys = rawBits.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val mask = (rawBits.opt(key) as? Number)?.toInt() ?: continue
                    bits[key] = mask
                }
            }
            return SettingsSchemaNode(
                type = raw.optString("type", "unknown"),
                meta = FieldMeta.fromJson(raw.optJSONObject("meta")),
                constValue = raw.anyOrNull("value"),
                hasValue = raw.has("value"),
                bits = bits,
            )
        }

        private fun parseInline(raw: JSONObject): SettingsSchemaNode {
            val node = shell(raw)
            raw.optJSONObject("dict")?.let { dict ->
                val keys = dict.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val child = dict.optJSONObject(key)
                    node.addDict(key, if (child == null) unknown() else parseInline(child))
                }
            }
            raw.optJSONArray("list")?.let { array ->
                for (i in 0 until array.length()) {
                    val child = array.optJSONObject(i)
                    node.addList(if (child == null) unknown() else parseInline(child))
                }
            }
            (raw.anyOrNull("inner") as? JSONObject)?.let { node.setInner(parseInline(it)) }
            (raw.anyOrNull("sKey") as? JSONObject)?.let { node.setSKey(parseInline(it)) }
            return node
        }
    }
}

/** 字段控件类型（对齐 dsh-Go FieldKind，复杂集合用 JSON 编辑器兜底）。 */
enum class FieldKind {
    TOGGLE,
    CHOICE,
    NUMBER,
    TEXT,
    SECRET,
    GROUP,
    LIST,
    OBJECT_LIST,
    DICT,
    JSON,
    READ_ONLY,
    HIDDEN,
}

fun valueNodeOf(source: SettingsSchemaNode): SettingsSchemaNode {
    val effective = source.effective
    if (effective.type == "union") {
        val nonNull = effective.members
        if (nonNull.size == 1) return nonNull.first().effective
    }
    return effective
}

private fun primitive(node: SettingsSchemaNode): Boolean {
    val n = node.effective
    return n.type == "string" || n.type == "number" || n.enumValues != null
}

fun fieldKindOf(source: SettingsSchemaNode): FieldKind {
    val effective = source.effective
    if (effective.meta.hidden || source.meta.hidden || effective.isCredentialRef) return FieldKind.HIDDEN
    if (effective.isSecret || source.isSecret) return FieldKind.SECRET
    val node = valueNodeOf(source)
    return when (node.type) {
        "boolean" -> FieldKind.TOGGLE
        "number" -> FieldKind.NUMBER
        "string" -> FieldKind.TEXT
        "const" -> FieldKind.HIDDEN
        "any", "tuple" -> FieldKind.JSON
        "union" -> {
            val values = node.enumValues
            when {
                values != null -> if (values.size > 1) FieldKind.CHOICE else FieldKind.HIDDEN
                node.members.isNotEmpty() && node.members.all { it.type == "string" || it.type == "const" } -> FieldKind.TEXT
                node.members.isNotEmpty() && node.members.all { it.type == "number" || it.type == "const" } -> FieldKind.NUMBER
                else -> FieldKind.READ_ONLY
            }
        }
        "object", "intersect" -> if (node.fields() != null) FieldKind.GROUP else FieldKind.READ_ONLY
        "array" -> {
            val inner = node.inner
            when {
                inner == null -> FieldKind.READ_ONLY
                primitive(inner) -> FieldKind.LIST
                inner.effective.fields()?.isNotEmpty() == true -> FieldKind.OBJECT_LIST
                else -> FieldKind.READ_ONLY
            }
        }
        "dict" -> {
            val inner = node.inner
            if (inner != null && (primitive(inner) || inner.effective.type == "boolean")) {
                FieldKind.DICT
            } else {
                FieldKind.READ_ONLY
            }
        }
        "bitset" -> if (node.bits.isEmpty()) FieldKind.READ_ONLY else FieldKind.CHOICE
        else -> FieldKind.READ_ONLY
    }
}

enum class FieldUnit { PLAIN, MILLIS, SECONDS, BYTES, TOKENS, RATIO }

fun unitFor(key: String, node: SettingsSchemaNode): FieldUnit {
    val meta = node.effective.meta
    if (meta.role == "slider" && meta.max == 1.0 && (meta.min ?: 0.0) == 0.0) return FieldUnit.RATIO
    return when {
        key.endsWith("Ms") -> FieldUnit.MILLIS
        key.endsWith("Seconds") || key.endsWith("Sec") -> FieldUnit.SECONDS
        key.endsWith("Bytes") -> FieldUnit.BYTES
        key.endsWith("Tokens") -> FieldUnit.TOKENS
        else -> FieldUnit.PLAIN
    }
}

data class PathValue(val found: Boolean, val value: Any?)

fun lookupPath(root: JSONObject?, path: List<String>): PathValue {
    if (root == null || path.isEmpty()) return PathValue(root != null, root)
    var node: Any? = root
    for (key in path) {
        node = when (val current = node) {
            is JSONObject -> {
                if (!current.has(key)) return PathValue(false, null)
                current.anyOrNull(key)
            }
            is JSONArray -> {
                val index = key.toIntOrNull() ?: return PathValue(false, null)
                if (index < 0 || index >= current.length()) return PathValue(false, null)
                current.opt(index).takeUnless { it == JSONObject.NULL }
            }
            else -> return PathValue(false, null)
        }
    }
    return PathValue(true, node)
}

fun pathKey(path: List<String>): String = path.joinToString("\u0000")

/** 一个 `settings/describe` namespace。 */
data class SettingsNamespace(
    val ns: String,
    val schema: SettingsSchemaNode,
    /** mutate 响应是否带回 schema；没有时上层保留旧 schema，避免表单退化。 */
    val schemaProvided: Boolean = true,
    val value: JSONObject,
    val base: JSONObject?,
    val user: JSONObject?,
    val revision: Int,
    val secrets: Map<String, Boolean>,
    val writable: Boolean,
) {
    fun valueOf(path: List<String>): Any? = lookupPath(value, path).value

    fun baseValueOf(path: List<String>): Any? = lookupPath(base, path).value

    fun userHas(path: List<String>): Boolean = lookupPath(user, path).found

    fun secretSet(path: List<String>): Boolean = secrets[pathKey(path)] == true

    /**
     * 同 dsh-Go `credentialRefOf`：secret 字段所在对象里的 `role('credential-ref')`
     * 兄弟字段给出凭据引用；没有则返回 null（值直接写进 settings 文档）。
     */
    fun credentialRefOf(path: List<String>): String? {
        if (path.isEmpty()) return null
        val parentPath = path.dropLast(1)
        val parent = schemaAt(schema, parentPath) ?: return null
        val fields = parent.fields() ?: return null
        for ((key, node) in fields) {
            if (!node.effective.isCredentialRef) continue
            val current = lookupPath(value, parentPath + key).value
            if (current is String && current.isNotEmpty()) return current
            val fallback = node.effective.meta.defaultValue
            if (fallback is String && fallback.isNotEmpty()) return fallback
        }
        return null
    }

    companion object {
        fun fromJson(raw: JSONObject, writable: Boolean): SettingsNamespace? {
            val ns = raw.optString("ns")
            if (ns.isEmpty()) return null
            val value = raw.optJSONObject("value") ?: JSONObject()
            val schemaJson = raw.optJSONObject("schema")
            val schema = schemaJson?.let { SettingsSchemaNode.fromJson(it) }
                ?: SettingsSchemaNode.infer(value)
            val secrets = LinkedHashMap<String, Boolean>()
            raw.optJSONArray("secrets")?.let { array ->
                for (i in 0 until array.length()) {
                    val slot = array.optJSONObject(i) ?: continue
                    val path = slot.optJSONArray("path")?.toStringList().orEmpty()
                    if (path.isNotEmpty()) secrets[pathKey(path)] = slot.optBoolean("set", false)
                }
            }
            return SettingsNamespace(
                ns = ns,
                schema = schema,
                schemaProvided = schemaJson != null,
                value = value,
                base = raw.optJSONObject("base"),
                user = raw.optJSONObject("user"),
                revision = raw.optInt("revision", 0),
                secrets = secrets,
                writable = writable,
            )
        }
    }
}

/** 沿字段路径找到 schema 节点（对象按字段名，数组/字典按 inner）。 */
fun schemaAt(root: SettingsSchemaNode, path: List<String>): SettingsSchemaNode? {
    var node = root.effective
    for (key in path) {
        val fields = node.fields()
        if (fields != null) {
            node = fields[key] ?: return null
        } else if (node.type == "array" || node.type == "dict") {
            node = node.inner ?: return null
        } else {
            return null
        }
        node = node.effective
    }
    return node
}

data class SettingsDescription(
    val namespaces: LinkedHashMap<String, SettingsNamespace>,
    val writable: Boolean,
    val hasDocument: Boolean,
) {
    fun namespace(ns: String): SettingsNamespace? = namespaces[ns]

    fun served(ns: String): Boolean = namespaces.containsKey(ns)
}

data class PluginDisplayMeta(
    val title: EngineText?,
    val description: EngineText?,
    val icon: String?,
    val error: String?,
) {
    companion object {
        fun fromJson(raw: JSONObject?): PluginDisplayMeta? {
            if (raw == null) return null
            return PluginDisplayMeta(
                title = EngineText.fromJson(raw.anyOrNull("title")),
                description = EngineText.fromJson(raw.anyOrNull("description")),
                icon = raw.anyOrNull("icon") as? String,
                error = raw.anyOrNull("error") as? String,
            )
        }
    }
}

data class PluginInventoryEntry(
    val entryId: String,
    val moduleName: String,
    val enabled: Boolean,
    val fiberPhase: String?,
    val meta: PluginDisplayMeta?,
) {
    /** 设置 namespace 用的行 id（`include:subagent` → `subagent`）。 */
    val rowId: String get() = entryId.removePrefix("include:")
}

/**
 * 一个 Agent 预设带出来的插件条目（对齐 dsh-Go 的 `PresetPlugins`）。
 */
data class PresetPlugins(
    val id: String,
    val name: String? = null,
    val isDefault: Boolean = false,
    val broken: String? = null,
    val rows: List<PluginInventoryEntry> = emptyList(),
)

data class PluginInventory(
    val entries: List<PluginInventoryEntry>,
    val managementAvailable: Boolean,
    /** 引擎组合出来的预设清单（`agentPresets` 字段）。 */
    val presets: List<PresetPlugins> = emptyList(),
)

/** `pluginManager/listBundles` 的一项：官方可选包或第三方已安装包。 */
/** 组合包里的一个 Loader 条目。 */
data class PluginBundleRow(
    /** 条目 id（如 `preset-standard`）。 */
    val rowId: String,
    /** 条目所属的模块名（如 `@deepseek-ai/dsh-agent-preset`）。 */
    val moduleName: String,
    val meta: PluginDisplayMeta?,
)

data class PluginBundle(
    val name: String,
    val official: Boolean,
    val version: String?,
    val meta: PluginDisplayMeta?,
    val description: String?,
    val enabled: Boolean,
    val installed: Boolean,
    val optional: Boolean,
    val removable: Boolean,
    val readOnlyReason: String?,
    val error: String?,
    /** 包里的 Loader 条目（找「哪个包声明了某个预设」要用）。 */
    val rows: List<PluginBundleRow> = emptyList(),
) {
    /** dsh-Go `bundleGroup`：第三方的归「已安装」，官方可选/已装的归「DSH 官方」。 */
    val group: PluginBundleGroup
        get() = when {
            installed && official -> PluginBundleGroup.OFFICIAL
            installed -> PluginBundleGroup.INSTALLED
            optional -> PluginBundleGroup.OFFICIAL
            else -> PluginBundleGroup.HIDDEN
        }

    val switchable: Boolean
        get() = readOnlyReason == null && (installed || optional)
}

enum class PluginBundleGroup { INSTALLED, OFFICIAL, HIDDEN }

/** `pluginManager/setBundleEnabled` 的结果：区分“已生效”和“需要重启引擎”。 */
data class PluginToggleResult(
    val succeeded: Boolean,
    val needsRestart: Boolean,
    val message: String?,
)

fun parsePluginBundle(raw: JSONObject): PluginBundle {
    val rows = mutableListOf<PluginBundleRow>()
    raw.optJSONArray("rows")?.let { array ->
        for (index in 0 until array.length()) {
            val row = array.optJSONObject(index) ?: continue
            val rowId = row.optString("rowId").ifEmpty { row.optString("id") }
            if (rowId.isEmpty()) continue
            rows += PluginBundleRow(
                rowId = rowId,
                moduleName = row.optString("moduleName"),
                meta = PluginDisplayMeta.fromJson(row.optJSONObject("meta")),
            )
        }
    }
    return PluginBundle(
        name = raw.optString("name"),
        official = raw.optBoolean("official", false),
        version = raw.anyOrNull("version") as? String,
        meta = PluginDisplayMeta.fromJson(raw.optJSONObject("meta")),
        description = raw.anyOrNull("description") as? String,
        enabled = raw.optBoolean("enabled", false),
        installed = raw.optBoolean("installed", false),
        optional = raw.optBoolean("optional", false),
        removable = raw.optBoolean("removable", false),
        readOnlyReason = raw.anyOrNull("readOnlyReason") as? String,
        error = raw.optJSONObject("error")?.let { error ->
            (error.anyOrNull("diagnostic") as? String) ?: (error.anyOrNull("code") as? String)
        },
        rows = rows,
    )
}

/** `speech/catalog` 的一个识别器及其模型准备状态（对齐 dsh-Go `SpeechProviderView`）。 */
data class SpeechProvider(
    val id: String,
    val name: String,
    val location: String,
    val phase: String,
    val languages: List<String> = emptyList(),
    val completedBytes: Long? = null,
    val totalBytes: Long? = null,
    val message: String? = null,
    val downloadReason: String? = null,
) {
    /** ready / standby / waking 都可以直接录音识别。 */
    val usable: Boolean
        get() = phase == "ready" || phase == "standby" || phase == "waking"

    val busy: Boolean
        get() = phase == "downloading" || phase == "checking" || phase == "loading" || phase == "waking"

    val fraction: Float?
        get() {
            val total = totalBytes ?: return null
            if (total <= 0L) return null
            return ((completedBytes ?: 0L).toFloat() / total).coerceIn(0f, 1f)
        }
}

/** `speech/catalog`：识别器、当前选择、语言和录音上限。 */
data class SpeechCatalog(
    val providers: List<SpeechProvider>,
    val providerId: String?,
    val language: String?,
    val maxAudioBytes: Int = 4 * 1024 * 1024,
    val maxDurationSeconds: Int = 120,
) {
    val selected: SpeechProvider?
        get() = providers.firstOrNull { it.id == providerId }

    val usableProvider: SpeechProvider?
        get() = selected?.takeIf { it.usable } ?: providers.firstOrNull { it.usable }

    /** Host 接受的整段录音秒数（WAV 头也算在字节上限里）。 */
    val maxSeconds: Int
        get() = minOf((maxAudioBytes - 44).coerceAtLeast(0) / 32000, maxDurationSeconds).coerceAtLeast(1)

    companion object {
        fun fromJson(root: JSONObject?): SpeechCatalog? {
            root ?: return null
            val providers = mutableListOf<SpeechProvider>()
            root.optJSONArray("providers")?.let { array ->
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id")
                    if (id.isEmpty()) continue
                    val preparation = item.optJSONObject("preparation")
                    val download = preparation?.optJSONObject("download")
                    val languages = mutableListOf<String>()
                    item.optJSONArray("languages")?.let { list ->
                        for (languageIndex in 0 until list.length()) {
                            list.optString(languageIndex).takeIf { it.isNotEmpty() }?.let(languages::add)
                        }
                    }
                    providers += SpeechProvider(
                        id = id,
                        name = item.optString("name").ifEmpty { id },
                        location = item.optString("location").ifEmpty { "host-local" },
                        phase = preparation?.optString("phase").orEmpty().ifEmpty { item.optString("phase", "ready") },
                        languages = languages,
                        completedBytes = (preparation?.anyOrNull("completedBytes") as? Number)?.toLong(),
                        totalBytes = (preparation?.anyOrNull("totalBytes") as? Number)?.toLong(),
                        message = preparation?.anyOrNull("message") as? String,
                        downloadReason = download?.anyOrNull("reason") as? String,
                    )
                }
            }
            if (providers.isEmpty()) return null
            val selection = root.optJSONObject("selection")
            return SpeechCatalog(
                providers = providers,
                providerId = selection?.optString("providerId")?.takeIf { it.isNotEmpty() },
                language = selection?.optString("language")?.takeIf { it.isNotEmpty() },
                maxAudioBytes = root.optInt("maxAudioBytes", 4 * 1024 * 1024),
                maxDurationSeconds = (root.anyOrNull("maxDurationSeconds") as? Number)?.toInt() ?: 120,
            )
        }
    }
}

data class PermissionOption(
    val value: String,
    val name: String,
    val description: String?,
)

data class PermissionCatalog(
    val options: List<PermissionOption>,
    val defaultOptions: List<PermissionOption>,
    val defaultPreset: String,
)

data class CredentialStatus(
    val configured: Boolean,
    val writable: Boolean,
    val source: String?,
)

/** 一次 `settings/mutate` 的路径编辑。 */
data class SettingsOp private constructor(
    val op: String,
    val path: List<String>,
    val value: Any?,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("op", op)
        put("path", JSONArray(path))
        if (op == "set") put("value", value)
    }

    companion object {
        fun set(path: List<String>, value: Any?): SettingsOp = SettingsOp("set", path, value)
        fun unset(path: List<String>): SettingsOp = SettingsOp("unset", path, null)
    }
}

// ---------------------------------------------------------------------------
// 模型渠道（llm-pi-ai）
// ---------------------------------------------------------------------------

/**
 * `llm-pi-ai` 适配器认识的全部思考档位（按升级顺序），对齐 dsh-Go
 * `piAiThinkingLevels`。`off` 表示「不思考」，其余档位是思考深度。
 */
val piAiThinkingLevels = listOf("off", "minimal", "low", "medium", "high", "xhigh", "max")

/** 适配器在模型没声明时的默认上下文长度（`DEFAULT_CONTEXT_WINDOW`）。 */
const val PIAI_DEFAULT_CONTEXT_WINDOW = 262144

/** 适配器在模型没声明时的默认最大输出（`DEFAULT_MAX_TOKENS`）。 */
const val PIAI_DEFAULT_MAX_TOKENS = 32768

/**
 * 一个渠道的 `models` 列表里的一条模型规格，对齐 dsh-Go
 * `packages/dsh_client/lib/src/providers_api.dart` 的 `ProviderModelSpec`。
 *
 * - 建模字段：`id` / `name` / `contextWindow` / `maxTokens` / `input`；
 * - 未建模字段（`compat`、`reasoningEfforts` …）原样放在 [extra] 里回写，
 *   编辑一次不会把引擎自己写的字段弄丢；
 * - `reasoningEfforts` 是一张「思考档位 → 实际发送值」的表，
 *   引擎认识的档位之外的会被丢掉（否则适配器下次启动会校验失败）。
 */
data class ProviderModelSpec(
    val id: String,
    val name: String?,
    val contextWindow: Int?,
    val maxTokens: Int?,
    val input: List<String>,
    /** 未建模字段，原样回写。 */
    val extra: JSONObject = JSONObject(),
) {
    /** 选择器上的名字；没有名字就用 id。 */
    val label: String get() = name?.takeIf { it.isNotBlank() } ?: id

    /**
     * `reasoningEfforts`：这个模型提供的思考档位 → 实际发送的值。
     * null 表示没有声明（模型不思考）；`off` 的值是 null（不发送任何东西）。
     */
    val reasoningEfforts: Map<String, String?>?
        get() {
            val raw = extra.opt("reasoningEfforts")
            if (raw !is JSONObject) return null
            val levels = LinkedHashMap<String, String?>()
            val keys = raw.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = raw.opt(key)
                when {
                    value == null || value == JSONObject.NULL -> levels[key] = null
                    value is String -> levels[key] = value
                    else -> return null
                }
            }
            return levels
        }

    /** 提供的思考档位（按升级顺序）；不思考的模型是空表。 */
    val thinkingLevels: List<String>
        get() {
            val efforts = reasoningEfforts ?: return emptyList()
            return piAiThinkingLevels.filter { efforts.containsKey(it) }
        }

    /** 是否有思考档位（只声明 `off` 不算思考）。 */
    val thinks: Boolean get() = thinkingLevels.any { it != "off" }

    /** 是否接受图片；没声明输入类型时用渠道默认值。 */
    fun acceptsImages(routeDefault: List<String> = listOf("text")): Boolean =
        (if (input.isEmpty()) routeDefault else input).contains("image")

    /**
     * 换一份 `reasoningEfforts`。空集合表示「不声明任何档位」；
     * 只剩 `off` 时按适配器规则显式关闭思考（写 `false`），
     * 因为一个只有 `off` 的表会被适配器判为非法。
     */
    fun withReasoningEfforts(levels: Iterable<String>?): ProviderModelSpec {
        val before = reasoningEfforts.orEmpty()
        val wanted = levels?.toSet().orEmpty()
        val next = JSONObject(extra.toString())
        if (wanted.isEmpty()) {
            // 已经是显式关闭就保留，其它情况整个字段去掉。
            if (next.opt("reasoningEfforts") != false) next.remove("reasoningEfforts")
        } else {
            val known = JSONObject()
            for (level in piAiThinkingLevels) {
                if (!wanted.contains(level)) continue
                val value: Any? = when {
                    before.containsKey(level) && (before[level] != null || level == "off") -> before[level]
                    level == "off" -> JSONObject.NULL
                    else -> level
                }
                known.put(level, value)
            }
            next.put("reasoningEfforts", knownReasoningEfforts(known))
        }
        return copy(extra = next)
    }

    fun toJson(): JSONObject = JSONObject(extra.toString()).apply {
        put("id", id)
        if (name.isNullOrBlank()) remove("name") else put("name", name)
        if (contextWindow == null) remove("contextWindow") else put("contextWindow", contextWindow)
        if (maxTokens == null) remove("maxTokens") else put("maxTokens", maxTokens)
        if (input.isEmpty()) remove("input") else put("input", JSONArray(input))
    }

    companion object {
        /** 建模字段；不在表里的键都进 [extra]。 */
        private val modelFields = setOf("id", "name", "contextWindow", "maxTokens", "input")

        fun fromJson(raw: JSONObject): ProviderModelSpec? {
            val id = raw.optString("id").trim()
            if (id.isEmpty()) return null
            val input = raw.optJSONArray("input")?.toStringList().orEmpty()
            val extra = JSONObject()
            val keys = raw.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key in modelFields) continue
                val value = raw.opt(key)
                extra.put(key, if (key == "reasoningEfforts") knownReasoningEfforts(value) else value)
            }
            return ProviderModelSpec(
                id = id,
                name = raw.anyOrNull("name") as? String,
                contextWindow = (raw.anyOrNull("contextWindow") as? Number)?.toInt(),
                maxTokens = (raw.anyOrNull("maxTokens") as? Number)?.toInt(),
                input = input,
                extra = extra,
            )
        }

        fun create(id: String): ProviderModelSpec = ProviderModelSpec(id, null, null, null, emptyList())

        /**
         * 只保留适配器认识的档位。剩下的只有 `off` 时显式关闭思考：
         * 空表或只有 `off` 的表是非法的，而整个字段不写又可能继承
         * 目录里的档位（导入的模型并不支持）。
         */
        fun knownReasoningEfforts(raw: Any?): Any? {
            if (raw !is JSONObject) return raw
            val known = JSONObject()
            val keys = raw.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key !in piAiThinkingLevels) continue
                known.put(key, raw.opt(key))
            }
            val names = known.keys().asSequence().toList()
            return if (names.any { it != "off" }) known else false
        }
    }
}

/**
 * 渠道给「没声明字段的模型」的默认值，对齐 dsh-Go 的 `ModelDefaults`。
 */
data class ModelDefaults(
    val contextWindow: Int = PIAI_DEFAULT_CONTEXT_WINDOW,
    val maxTokens: Int = PIAI_DEFAULT_MAX_TOKENS,
    val input: List<String> = listOf("text"),
) {
    companion object {
        fun of(profile: ProviderProfile?): ModelDefaults = ModelDefaults(
            contextWindow = profile?.defaultContextWindow ?: PIAI_DEFAULT_CONTEXT_WINDOW,
            maxTokens = profile?.defaultMaxTokens ?: PIAI_DEFAULT_MAX_TOKENS,
            input = profile?.defaultInput?.takeIf { it.isNotEmpty() } ?: listOf("text"),
        )
    }
}

data class ProviderProfile(
    val route: String,
    val displayName: String?,
    val api: String?,
    val baseUrl: String?,
    val apiKeyEnv: String?,
    val models: List<ProviderModelSpec>,
    /** 渠道给「没声明字段的模型」的默认上下文长度。 */
    val defaultContextWindow: Int? = null,
    /** 渠道给「没声明字段的模型」的默认最大输出。 */
    val defaultMaxTokens: Int? = null,
    /** 渠道给「没声明字段的模型」的默认输入类型。 */
    val defaultInput: List<String>? = null,
    private val raw: JSONObject,
) {
    val label: String get() = displayName?.takeIf { it.isNotBlank() } ?: route

    fun toJson(): JSONObject = JSONObject(raw.toString()).apply {
        if (displayName.isNullOrBlank()) remove("displayName") else put("displayName", displayName)
        if (api.isNullOrBlank()) remove("api") else put("api", api)
        if (baseUrl.isNullOrBlank()) remove("baseURL") else put("baseURL", baseUrl)
        if (apiKeyEnv.isNullOrBlank()) remove("apiKeyEnv") else put("apiKeyEnv", apiKeyEnv)
        put("models", JSONArray().apply { models.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(route: String, raw: JSONObject): ProviderProfile {
            val models = mutableListOf<ProviderModelSpec>()
            raw.optJSONArray("models")?.let { array ->
                for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.let { model -> ProviderModelSpec.fromJson(model)?.let(models::add) }
                }
            }
            return ProviderProfile(
                route = route,
                displayName = raw.anyOrNull("displayName") as? String,
                api = raw.anyOrNull("api") as? String,
                baseUrl = raw.anyOrNull("baseURL") as? String,
                apiKeyEnv = raw.anyOrNull("apiKeyEnv") as? String,
                models = models,
                defaultContextWindow = (raw.anyOrNull("defaultContextWindow") as? Number)?.toInt(),
                defaultMaxTokens = (raw.anyOrNull("defaultMaxTokens") as? Number)?.toInt(),
                defaultInput = raw.optJSONArray("defaultInput")?.toStringList(),
                raw = raw,
            )
        }
    }
}

data class ProviderSettings(
    val profiles: List<ProviderProfile>,
    val revision: Int,
    val writable: Boolean,
    val userRoutes: Set<String>,
    val baseRoutes: Set<String>,
) {
    fun removable(route: String): Boolean = userRoutes.contains(route) && !baseRoutes.contains(route)

    companion object {
        fun fromNamespace(namespace: SettingsNamespace?): ProviderSettings {
            if (namespace == null) {
                return ProviderSettings(emptyList(), 0, false, emptySet(), emptySet())
            }
            val providers = namespace.value.optJSONObject("providers") ?: JSONObject()
            val profiles = mutableListOf<ProviderProfile>()
            val keys = providers.keys()
            while (keys.hasNext()) {
                val route = keys.next()
                providers.optJSONObject(route)?.let { profiles.add(ProviderProfile.fromJson(route, it)) }
            }
            fun routes(layer: JSONObject?): Set<String> {
                val dict = layer?.optJSONObject("providers") ?: return emptySet()
                val out = LinkedHashSet<String>()
                val names = dict.keys()
                while (names.hasNext()) out.add(names.next())
                return out
            }
            return ProviderSettings(
                profiles = profiles,
                revision = namespace.revision,
                writable = namespace.writable,
                userRoutes = routes(namespace.user),
                baseRoutes = routes(namespace.base),
            )
        }
    }
}

val providerRoutePattern = Regex("^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$")

fun deriveProviderKeyRef(route: String): String =
    route.uppercase().replace(Regex("[^A-Z0-9]+"), "_") + "_API_KEY"

const val PROVIDER_NAMESPACE = "llm-pi-ai"

// ---------------------------------------------------------------------------
// JSON helpers
// ---------------------------------------------------------------------------

internal fun JSONObject.anyOrNull(key: String): Any? {
    val value = opt(key)
    return if (value == null || value == JSONObject.NULL) null else value
}

internal fun JSONObject.numberOrNull(key: String): Double? = (anyOrNull(key) as? Number)?.toDouble()

internal fun JSONArray.toStringList(): List<String> = buildList {
    for (i in 0 until length()) {
        when (val value = opt(i)) {
            is String -> add(value)
            is Number -> add(value.toString())
        }
    }
}

/** JSON 值比较（1 == 1.0，对象无序）。 */
fun jsonEquals(a: Any?, b: Any?): Boolean {
    if (a is Number && b is Number) return a.toDouble() == b.toDouble()
    if (a is JSONObject && b is JSONObject) {
        if (a.length() != b.length()) return false
        val keys = a.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (!b.has(key) || !jsonEquals(a.anyOrNull(key), b.anyOrNull(key))) return false
        }
        return true
    }
    if (a is JSONArray && b is JSONArray) {
        if (a.length() != b.length()) return false
        for (i in 0 until a.length()) {
            if (!jsonEquals(a.opt(i).takeUnless { it == JSONObject.NULL }, b.opt(i).takeUnless { it == JSONObject.NULL })) {
                return false
            }
        }
        return true
    }
    return a == b
}
