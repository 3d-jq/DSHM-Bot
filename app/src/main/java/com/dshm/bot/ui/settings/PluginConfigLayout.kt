package com.dshm.bot.ui.settings

import com.dshm.bot.data.remote.FieldKind
import com.dshm.bot.data.remote.FieldUnit
import com.dshm.bot.data.remote.SettingsSchemaNode
import com.dshm.bot.data.remote.fieldKindOf
import com.dshm.bot.data.remote.unitFor

/**
 * 插件配置页的字段布局，对齐 dsh-Go
 * `app/lib/ui/plugins/config/plugin_config_layout.dart`：
 *
 * - 字段分三层：**主要**（密钥、必填、开关、选项）→ **常规**（插件自己的调参）
 *   → **高级**（超时、限额、路径、日志这类「管道」字段）；
 * - 表单够长（≥ [FOLD_FROM] 个字段）且高级字段够多（≥ [MIN_FOLDED]）时，
 *   高级字段折进「高级」分组，不铺满整屏；
 * - schema 自己声明了折叠（`collapse`）就听它的，不再自作主张地折。
 *
 * 纯逻辑，不依赖 Android，便于 JVM 单测覆盖。
 */
enum class ConfigTier {
    /** 排在最前：密钥、必填、开关与选项。 */
    PRIMARY,

    /** 插件自己的调参，按插件原有顺序。 */
    REGULAR,

    /** 管道类字段（超时、限额、路径、日志）。 */
    ADVANCED,
}

/** 表单达到这么多字段才开始折叠。 */
const val FOLD_FROM = 7

/** 至少这么多高级字段才值得折。 */
const val MIN_FOLDED = 3

/** dsh-Go 的「管道类」字段名：超时、间隔、限额、缓存、路径、端点、日志…… */
private val PLUMBING_SUFFIX = Regex(
    "(Ms|Millis|Seconds|Sec|Bytes|Tokens|Timeout|Interval|Threshold|Padding|Grace|Retries|Retry|Backoff|" +
        "Concurrency|Pending|Probe|Buffer|Cache|Ttl|TTL|Debug|Verbose|Log|Logs|Path|Root|Dir|Directory|" +
        "File|Origin|Origins|Endpoint|Endpoints)$",
)

/** dsh-Go 的「管道类」字段名前缀。 */
private val PLUMBING_PREFIX = Regex("^(max|min|debug|verbose|log)([A-Z_]|$)")

/** 字段有没有可用的默认值（必填但有默认值就不算必填）。 */
private fun hasWorkingDefault(node: SettingsSchemaNode): Boolean {
    val meta = node.effective.meta
    return (meta.hasDefault && meta.defaultValue != null) ||
        (node.meta.hasDefault && node.meta.defaultValue != null)
}

/**
 * 字段该放在哪一层。
 *
 * @param live 这个字段是「实时生效」的（改完立刻见效），实时字段不折进高级。
 */
fun configTierOf(key: String, node: SettingsSchemaNode, live: Boolean = false): ConfigTier {
    val kind = fieldKindOf(node)
    val meta = node.effective.meta
    val required = meta.required && !hasWorkingDefault(node)
    // 密钥与必填项是「先要填的」，永远在最上面。
    if (kind == FieldKind.SECRET || required) return ConfigTier.PRIMARY
    if (!live && isPlumbingKey(key)) return ConfigTier.ADVANCED
    val unit = if (kind == FieldKind.NUMBER) unitFor(key, node) else FieldUnit.PLAIN
    if (!live && unit != FieldUnit.PLAIN && unit != FieldUnit.RATIO) return ConfigTier.ADVANCED
    if (live || kind == FieldKind.TOGGLE || kind == FieldKind.CHOICE) return ConfigTier.PRIMARY
    return ConfigTier.REGULAR
}

/** 这个字段名是不是 dsh-Go 说的「管道类」。 */
fun isPlumbingKey(key: String): Boolean =
    PLUMBING_SUFFIX.containsMatchIn(key) || PLUMBING_PREFIX.containsMatchIn(key)

/**
 * schema 自己声明了折叠吗（`.collapse()`）：作者已经排好版，页面不再自己折。
 */
fun declaresCollapse(root: SettingsSchemaNode): Boolean {
    val seen = mutableSetOf<SettingsSchemaNode>()
    fun walk(source: SettingsSchemaNode, depth: Int): Boolean {
        if (depth > 32 || !seen.add(source)) return false
        val node = source.effective
        if (source.meta.collapse || node.meta.collapse) return true
        val children = buildList {
            node.fields()?.values?.let(::addAll)
            addAll(node.listMembers)
            node.inner?.let(::add)
        }
        return children.any { walk(it, depth + 1) }
    }
    return walk(root, 0)
}

/**
 * 要不要把高级字段折起来。
 *
 * @param fieldCount 表单一共画多少字段。
 * @param advancedCount 其中多少是高级字段。
 */
fun shouldFoldAdvanced(fieldCount: Int, advancedCount: Int, collapseDeclared: Boolean): Boolean =
    !collapseDeclared &&
        fieldCount >= FOLD_FROM &&
        advancedCount >= MIN_FOLDED &&
        advancedCount < fieldCount
