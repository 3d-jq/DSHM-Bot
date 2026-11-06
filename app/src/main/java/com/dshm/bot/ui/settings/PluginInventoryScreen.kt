package com.dshm.bot.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.data.remote.InventoryStatus
import com.dshm.bot.data.remote.PluginInventoryEntry
import com.dshm.bot.data.remote.PresetPlugins
import com.dshm.bot.data.remote.inventoryStatusOf
import com.dshm.bot.data.remote.inventoryTitleOf
import com.dshm.bot.data.remote.isSystemEntry

/**
 * 插件清单页（对齐 dsh-Go `app/lib/ui/plugins/plugin_inventory_page.dart`）：
 *
 * **只读** —— 引擎里每个 Loader 条目及其状态。分两部分：
 * - **全局插件**：不属于任何预设、一直在引擎里的条目；
 * - **Agent 预设**：每个预设一组，列出这个预设带出来的条目。
 *
 * 启用/停用开关在「插件」页，不在这里。
 */
@Composable
fun PluginInventoryScreen(
    viewModel: EngineSettingsViewModel,
    onBack: () -> Unit,
    onOpenSettings: (ns: String, title: String) -> Unit = { _, _ -> },
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val language = settingsLanguage()
    val inventory = state.plugins
    val entries = inventory?.entries.orEmpty()
    val presets = inventory?.presets.orEmpty()

    val globalTitle = stringResource(R.string.plugins_inventory_global)
    val presetsTitle = stringResource(R.string.plugins_inventory_presets)
    // dsh-Go：顶部分类栏切「全局插件 / Agent 预设」；选预设时再挑具体哪个预设。
    var scope by remember { mutableStateOf(InventoryScope.GLOBAL) }
    var presetId by remember { mutableStateOf<String?>(null) }
    val activeScope = if (presets.isEmpty()) InventoryScope.GLOBAL else scope
    val activePreset = presets.firstOrNull { it.id == presetId }
        ?: presets.firstOrNull { it.isDefault }
        ?: presets.firstOrNull()

    // 全局插件 = 不属于任何预设的条目；其中没有中文名的是「系统组件」，单独折叠收起来。
    val globalEntries = remember(entries, presets) {
        val provided = mutableSetOf<String>()
        presets.forEach { preset -> preset.rows.forEach { provided.add(it.entryId) } }
        entries.filterNot { it.entryId in provided }
    }
    val systemEntries = remember(globalEntries) { globalEntries.filter { isSystemEntry(it.meta) } }
    val namedEntries = remember(globalEntries) { globalEntries.filterNot { isSystemEntry(it.meta) } }

    SettingsPage(
        title = stringResource(R.string.plugins_inventory_title),
        onBack = onBack,
        actions = { SettingsRefreshButton(onClick = viewModel::refresh) },
    ) {
        // 分类栏：全局插件 / Agent 预设（dsh-Go 的 SegmentedPill）。
        if (presets.isNotEmpty()) {
            item {
                SettingsSegmentedPill(
                    options = listOf(globalTitle, presetsTitle),
                    selected = if (activeScope == InventoryScope.GLOBAL) globalTitle else presetsTitle,
                    onSelect = { picked ->
                        scope = if (picked == globalTitle) InventoryScope.GLOBAL else InventoryScope.PRESETS
                    },
                    modifier = Modifier.padding(horizontal = Dshm.spacing.lg, vertical = Dshm.spacing.sm),
                )
            }
            // 预设切换：横向一排胶囊。
            if (activeScope == InventoryScope.PRESETS && presets.size > 1) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = Dshm.spacing.lg, vertical = Dshm.spacing.xs),
                        horizontalArrangement = Arrangement.spacedBy(Dshm.spacing.sm),
                    ) {
                        presets.forEach { preset ->
                            SettingsChoiceChip(
                                label = presetTitle(preset),
                                selected = preset.id == activePreset?.id,
                                onClick = { presetId = preset.id },
                            )
                        }
                    }
                }
            }
        }

        when {
            state.loading && entries.isEmpty() -> item { SettingsLoading() }
            state.error != null && entries.isEmpty() -> item {
                SettingsError(state.error.orEmpty(), onRetry = viewModel::refresh)
            }
            activeScope == InventoryScope.PRESETS -> {
                val preset = activePreset
                if (preset == null || preset.rows.isEmpty()) {
                    item { SettingsEmpty(stringResource(R.string.plugins_inventory_empty)) }
                } else {
                    item {
                        SettingsSection(title = presetTitle(preset)) {
                            preset.rows.forEachIndexed { index, entry ->
                                if (index > 0) SettingsRowDivider()
                                InventoryRow(
                                    entry = entry,
                                    language = language,
                                    onClick = {
                                        val title = entry.meta?.title?.resolve(language)
                                            ?.takeIf { it.isNotBlank() }
                                            ?: entry.moduleName
                                        onOpenSettings(entry.rowId, title)
                                    },
                                )
                            }
                        }
                    }
                }
            }
            globalEntries.isEmpty() -> item { SettingsEmpty(stringResource(R.string.plugins_inventory_empty)) }
            else -> {
                if (namedEntries.isNotEmpty()) {
                item {
                    SettingsSection(title = globalTitle) {
                        namedEntries.forEachIndexed { index, entry ->
                            if (index > 0) SettingsRowDivider()
                            InventoryRow(
                                entry = entry,
                                language = language,
                                onClick = {
                                    val title = entry.meta?.title?.resolve(language)
                                        ?.takeIf { it.isNotBlank() }
                                        ?: entry.moduleName
                                    onOpenSettings(entry.rowId, title)
                                },
                            )
                        }
                    }
                }
                }
                // 系统组件：没有中文名的内部组件，收进折叠卡（对齐 dsh-Go）。
                if (systemEntries.isNotEmpty()) {
                    item {
                        SettingsCollapsedSection(
                            title = stringResource(R.string.plugins_inventory_system_group),
                            count = systemEntries.size,
                        ) {
                            systemEntries.forEachIndexed { index, entry ->
                                if (index > 0) SettingsRowDivider()
                                InventoryRow(
                                    entry = entry,
                                    language = language,
                                    onClick = { onOpenSettings(entry.rowId, inventoryTitleOf(entry)) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 插件清单的分类：全局插件 / Agent 预设。 */
enum class InventoryScope { GLOBAL, PRESETS }

/** 清单的一节：一组条目 + 一个标题。 */
data class InventorySection(
    val title: String,
    val entries: List<PluginInventoryEntry>,
)

/** 预设那一节的标题：官方中文名优先，没有就用名字，再没有就用 id。 */
fun presetTitle(preset: PresetPlugins): String = when (preset.id) {
    "standard" -> "标准模式"
    "ptc" -> "PTC 模式"
    "minimal" -> "极简模式"
    "cordis" -> "创造模式"
    else -> preset.name?.takeIf { it.isNotBlank() } ?: preset.id
}

/** 清单里的一行：名字 + 描述 + 状态点（设置行，行高 56dp）。 */
@Composable
private fun InventoryRow(
    entry: PluginInventoryEntry,
    language: String,
    onClick: () -> Unit,
) {
    // 名字：中文名优先，没有就用模块短名；引擎给的 title 可能是 file:// 入口路径。
    val title = inventoryTitleOf(entry)
    val status = inventoryStatusOf(entry.enabled, entry.fiberPhase)
    SettingsRow(
        title = title,
        trailing = {
            Box(
                modifier = Modifier
                    .size(Dshm.spacing.statusDot)
                    .clip(CircleShape)
                    .background(inventoryTone(status)),
            )
        },
        onClick = onClick,
    )
}

/** 状态点的语义色（对齐 dsh-Go 的 `inventoryTone`）。 */
@Composable
private fun inventoryTone(status: InventoryStatus): androidx.compose.ui.graphics.Color = when (status) {
    InventoryStatus.RUNNING -> Dshm.colors.success
    InventoryStatus.FAILED -> Dshm.colors.danger
    InventoryStatus.LOADING -> Dshm.colors.accent
    InventoryStatus.WAITING -> Dshm.colors.warning
    InventoryStatus.OFF, InventoryStatus.IDLE -> Dshm.colors.textTertiary
}

/** 状态词（详情用）。 */
@Composable
fun inventoryStatusLabel(status: InventoryStatus): String = when (status) {
    InventoryStatus.RUNNING -> stringResource(R.string.plugins_inventory_running)
    InventoryStatus.OFF -> stringResource(R.string.plugins_inventory_off)
    InventoryStatus.FAILED -> stringResource(R.string.plugins_inventory_failed)
    InventoryStatus.LOADING -> stringResource(R.string.plugins_inventory_loading)
    InventoryStatus.WAITING -> stringResource(R.string.plugins_inventory_waiting)
    InventoryStatus.IDLE -> stringResource(R.string.plugins_inventory_idle)
}
