package com.dshm.bot.data.remote

/**
 * 插件清单里一个条目的状态，对齐 dsh-Go `app/lib/state/engine_settings_state.dart`
 * 的 `InventoryStatus`。
 */
enum class InventoryStatus {
    /** 已启用且在跑。 */
    RUNNING,

    /** 没启用。 */
    OFF,

    /** 加载失败。 */
    FAILED,

    /** 正在装载 / 卸载。 */
    LOADING,

    /** 排队等资源。 */
    WAITING,

    /** 启用了但没在跑。 */
    IDLE,
}

/**
 * 条目的状态判定。失败的优先级最高（先看 fiberPhase，再看启用与否）。
 */
fun inventoryStatusOf(enabled: Boolean, fiberPhase: String?): InventoryStatus = when (fiberPhase) {
    "failed" -> InventoryStatus.FAILED
    "loading", "unloading" -> InventoryStatus.LOADING
    "pending" -> InventoryStatus.WAITING
    else -> if (!enabled) {
        InventoryStatus.OFF
    } else if (fiberPhase == "active") {
        InventoryStatus.RUNNING
    } else {
        InventoryStatus.IDLE
    }
}

/**
 * 这是不是「系统组件」：包没有中文名 = 内部组件（对齐 dsh-Go 的
 * `InventoryItem.system`）。只看有没有中文的键，不看解析结果 ——
 * 只有英文时按中文解析会回退到英文，不能当成"有中文名"。
 */
fun isSystemEntry(meta: PluginDisplayMeta?): Boolean {
    val values = meta?.title?.values ?: return true
    if (values.isEmpty()) return true
    return values.keys.none { it.startsWith("zh") }
}

/**
 * 插件清单的分组，对齐 dsh-Go 的 `groupInventory`：
 * 出错的条目单独放最前面（一张「出错」卡），其余按来源分组，
 * 系统组件收进折叠卡。
 */
data class InventoryGroup(
    val title: String,
    val entries: List<PluginInventoryEntry>,
)

/**
 * 把清单分组。[system] 的条目（没有中文名的内部组件）单独收进一组，
 * 交给界面折叠起来。
 */
fun groupInventory(
    entries: List<PluginInventoryEntry>,
    groupTitleOf: (PluginInventoryEntry) -> String,
    failedTitle: String,
    systemTitle: String,
): Pair<List<InventoryGroup>, List<PluginInventoryEntry>> {
    val failed = entries.filter { inventoryStatusOf(it.enabled, it.fiberPhase) == InventoryStatus.FAILED }
    val rest = entries.filterNot { it in failed }
    val system = rest.filter { isSystemEntry(it.meta) }
    val normal = rest.filterNot { it in system }

    val groups = mutableListOf<InventoryGroup>()
    if (failed.isNotEmpty()) groups += InventoryGroup(failedTitle, failed)
    normal.groupBy { groupTitleOf(it) }.forEach { (title, items) ->
        groups += InventoryGroup(title, items)
    }
    return groups to system
}
