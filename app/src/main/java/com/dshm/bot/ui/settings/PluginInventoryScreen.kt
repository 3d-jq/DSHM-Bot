package com.dshm.bot.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.data.remote.InventoryStatus
import com.dshm.bot.data.remote.PluginBundle
import com.dshm.bot.data.remote.PluginInventoryEntry
import com.dshm.bot.data.remote.PresetPlugins
import com.dshm.bot.data.remote.inventoryStatusOf
import com.dshm.bot.data.remote.inventoryTitleOf
import com.dshm.bot.data.remote.isSystemEntry
import com.dshm.bot.data.remote.shortModuleName

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
    var sheetEntry by remember { mutableStateOf<PluginInventoryEntry?>(null) }
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
                                    onClick = { sheetEntry = entry },
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
                                onClick = { sheetEntry = entry },
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
                                    onClick = { sheetEntry = entry },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    sheetEntry?.let { entry ->
        PluginInventorySheet(
            entry = entry,
            language = language,
            viewModel = viewModel,
            presets = presets,
            bundles = state.bundles,
            onDismiss = { sheetEntry = null },
            onOpenSettings = { ns, title ->
                sheetEntry = null
                onOpenSettings(ns, title)
            },
        )
    }
}

/**
 * 插件清单条目的详情面板（对齐 dsh-Go `InventorySheet`）：
 * 是什么、来自哪个包、运行状态、启用条件，以及有设置时进入设置。
 */
@Composable
private fun PluginInventorySheet(
    entry: PluginInventoryEntry,
    language: String,
    viewModel: EngineSettingsViewModel,
    presets: List<PresetPlugins>,
    bundles: List<PluginBundle>,
    onDismiss: () -> Unit,
    onOpenSettings: (ns: String, title: String) -> Unit,
) {
    val title = inventoryTitleOf(entry)
    val status = inventoryStatusOf(entry.enabled, entry.fiberPhase)
    val origin = inventoryOrigin(entry, bundles, language)
    val providedBy = if (entry.enabled) {
        emptyList()
    } else {
        presets
            .filter { preset -> preset.rows.any { it.moduleName == entry.moduleName && (it.enabled || it.condition != null) } }
            .map(::presetTitle)
            .distinct()
    }
    val sourceLabel = stringResource(R.string.plugins_detail_source)
    val versionLabel = stringResource(R.string.settings_version)
    val conditionLabel = stringResource(R.string.plugins_detail_condition)
    val providedByLabel = stringResource(R.string.plugins_detail_provided_by)
    val facts = buildList {
        origin.source?.let { add(sourceLabel to it) }
        origin.version?.let { add(versionLabel to it) }
        entry.condition?.let { add(conditionLabel to it) }
        if (providedBy.isNotEmpty()) {
            add(providedByLabel to providedBy.joinToString("、"))
        }
    }
    val configurable = viewModel.namespace(entry.rowId)?.takeIf { namespace ->
        namespace.ns in PluginConfigScope.CURATED_NAMESPACES ||
            viewModel.pluginConfigNamespaces().any { it.ns == namespace.ns }
    }

    SettingsSheet(title = stringResource(R.string.plugins_detail_title), onDismiss = onDismiss) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PluginAvatar(
                name = entry.moduleName,
                title = title,
                icon = entry.meta?.icon,
                size = Dshm.spacing.settingsAvatar,
            )
            Spacer(Modifier.size(Dshm.spacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = Dshm.typography.sheetTitle,
                    color = Dshm.colors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (entry.moduleName.isNotBlank()) {
                    Text(
                        text = entry.moduleName,
                        style = Dshm.typography.rowPreview,
                        color = Dshm.colors.textTertiary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(Dshm.spacing.xs))
                InventoryStatusBadge(status)
            }
        }

        val description = entry.meta?.description?.resolve(language)?.takeIf { it.isNotBlank() }
        if (description != null) {
            Spacer(Modifier.height(Dshm.spacing.lg))
            Text(
                text = description,
                style = Dshm.typography.rowPreview,
                color = Dshm.colors.textSecondary,
            )
        }
        entry.meta?.error?.let { error ->
            Spacer(Modifier.height(Dshm.spacing.sm))
            Text(
                text = stringResource(R.string.plugins_detail_meta_error, error),
                style = Dshm.typography.rowPreview,
                color = Dshm.colors.danger,
            )
        }

        if (facts.isNotEmpty()) {
            Spacer(Modifier.height(Dshm.spacing.lg))
            SettingsSection {
                facts.forEachIndexed { index, (label, value) ->
                    if (index > 0) SettingsRowDivider()
                    SettingsRow(
                        title = label,
                        value = value,
                        trailing = {},
                        singleLineTitle = true,
                    )
                }
            }
        }

        configurable?.let { namespace ->
            Spacer(Modifier.height(Dshm.spacing.lg))
            SettingsSection {
                SettingsRow(
                    title = stringResource(R.string.settings_plugin_configs),
                    icon = R.drawable.ic_lucide_settings,
                    subtitle = if (!namespace.writable) {
                        stringResource(R.string.settings_engine_read_only)
                    } else {
                        null
                    },
                    onClick = { onOpenSettings(namespace.ns, title) },
                )
            }
        }
    }
}

@Composable
private fun InventoryStatusBadge(status: InventoryStatus) {
    val tone = inventoryTone(status)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(Dshm.radius.pill))
            .background(tone.copy(alpha = 0.12f))
            .padding(horizontal = Dshm.spacing.sm, vertical = Dshm.spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(Dshm.spacing.statusDot)
                .clip(CircleShape)
                .background(tone),
        )
        Spacer(Modifier.size(Dshm.spacing.xs))
        Text(
            text = inventoryStatusLabel(status),
            style = Dshm.typography.rowTime,
            color = tone,
        )
    }
}

private data class InventoryOrigin(
    val source: String?,
    val version: String?,
)

private fun inventoryOrigin(
    entry: PluginInventoryEntry,
    bundles: List<PluginBundle>,
    language: String,
): InventoryOrigin {
    var owner: PluginBundle? = null
    bundles.forEach { bundle ->
        val matches = bundle.rows.any { row ->
            row.rowId == entry.rowId ||
                row.moduleName == entry.moduleName ||
                row.rowId == entry.entryId
        }
        if (matches && (owner == null || (!owner!!.enabled && bundle.enabled))) owner = bundle
    }
    val bundle = owner ?: return InventoryOrigin(null, null)
    val titled = bundle.meta?.title?.resolve(language)?.takeIf { it.isNotBlank() && it != bundle.name }
    return InventoryOrigin(
        source = titled ?: shortModuleName(bundle.name),
        version = bundle.version,
    )
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
