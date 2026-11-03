package com.dshm.bot.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.core.design.DshmMotion
import com.dshm.bot.data.remote.PROVIDER_NAMESPACE
import com.dshm.bot.data.remote.ProviderProfile
import com.dshm.bot.data.remote.deriveProviderKeyRef
import com.dshm.bot.data.remote.providerRoutePattern

private data class ProviderEditorTarget(val existing: ProviderProfile?)

private val providerProtocols = listOf(
    "openai-completions" to "OpenAI",
    "anthropic-messages" to "Anthropic",
    "openai-responses" to "Responses",
)

/** 模型：官方 / 订阅 / 自定义渠道，结构对齐 dsh-Go providers_page。 */
@Composable
fun ModelProvidersScreen(
    viewModel: EngineSettingsViewModel,
    onBack: () -> Unit,
    onOpenAccount: () -> Unit,
    dshVersion: String? = null,
    accountSignedIn: Boolean = false,
    reduceMotion: Boolean = false,
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val language = settingsLanguage()
    var editor by remember { mutableStateOf<ProviderEditorTarget?>(null) }
    val providers = viewModel.providers()
    val customRoutes = providers.profiles.map { it.route }.toSet()
    // DeepSeek 的渠道由上面的官方卡统一表示，不再重复列 deepseek-official/account。
    val registered = state.listedProviders.filter {
        it.id !in customRoutes && !it.id.contains("deepseek", ignoreCase = true)
    }
    val subscriptions = registered.filter {
        val id = it.id.lowercase()
        id.contains("openai") || id.contains("anthropic") || id.contains("claude") || id.contains("chatgpt")
    }
    val otherRegistered = registered.filterNot { it in subscriptions }
    val available = (if (accountSignedIn) 1 else 0) + providers.profiles.count { profile ->
        state.credentials[profile.apiKeyEnv ?: deriveProviderKeyRef(profile.route)]?.configured == true
    }
    val channelCount = 1 + registered.size + providers.profiles.size
    val stateHolder = rememberSaveableStateHolder()

    AnimatedContent(
        targetState = editor,
        transitionSpec = {
            val duration = if (reduceMotion) 0 else DshmMotion.screenTransitionMs
            val fade = if (reduceMotion) 0 else DshmMotion.fadeMs
            if (targetState != null) {
                (
                    slideInHorizontally(tween(duration, easing = DshmMotion.standard)) { it } +
                        fadeIn(tween(fade))
                    ).togetherWith(
                    slideOutHorizontally(tween(duration, easing = DshmMotion.exit)) { -it / 4 } +
                        fadeOut(tween(fade)),
                )
            } else {
                (
                    slideInHorizontally(tween(duration, easing = DshmMotion.standard)) { -it / 4 } +
                        fadeIn(tween(fade))
                    ).togetherWith(
                    slideOutHorizontally(tween(duration, easing = DshmMotion.standard)) { it } +
                        fadeOut(tween(fade)),
                )
            }
        },
        label = "provider-editor",
    ) { target ->
        stateHolder.SaveableStateProvider(if (target == null) "providers" else "editor") {
            if (target != null) {
                ProviderEditorScreen(
                    viewModel = viewModel,
                    existing = target.existing,
                    language = language,
                    onBack = { editor = null },
                )
            } else {
                SettingsPage(
                    title = stringResource(R.string.settings_model),
                    subtitle = stringResource(R.string.settings_model_summary, channelCount, available),
                    onBack = onBack,
                    actions = { SettingsRefreshButton(onClick = viewModel::refresh) },
                ) {
                    when {
                        state.loading && state.description == null -> item { SettingsLoading() }
                        state.error != null && state.description == null -> item {
                            SettingsError(state.error.orEmpty(), onRetry = viewModel::refresh)
                        }
                        else -> {
                            item {
                                SettingsSection(title = stringResource(R.string.settings_model_official)) {
                                    SettingsRow(
                                        title = "DeepSeek",
                                        leading = { LetterAvatar("D") },
                                        subtitle = if (accountSignedIn) {
                                            stringResource(R.string.settings_model_account_line, dshVersion.orEmpty())
                                        } else {
                                            stringResource(R.string.settings_model_not_signed_in)
                                        },
                                        trailing = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                StatusDot(active = accountSignedIn)
                                                Spacer(Modifier.width(Dshm.spacing.xs))
                                                Chevron()
                                            }
                                        },
                                        onClick = onOpenAccount,
                                    )
                                    otherRegistered.forEach { provider ->
                                        SettingsRowDivider()
                                        SettingsRow(
                                            title = provider.name,
                                            leading = { LetterAvatar(provider.name) },
                                            subtitle = provider.id,
                                            trailing = {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    StatusDot(active = true)
                                                    Spacer(Modifier.width(Dshm.spacing.xs))
                                                    Chevron()
                                                }
                                            },
                                        )
                                    }
                                }
                            }

                            if (subscriptions.isNotEmpty()) {
                                item {
                                    SettingsSection(title = stringResource(R.string.settings_model_subscription)) {
                                        subscriptions.forEachIndexed { index, provider ->
                                            if (index > 0) SettingsRowDivider()
                                            SettingsRow(
                                                title = provider.name,
                                                leading = { LetterAvatar(provider.name) },
                                                subtitle = stringResource(R.string.settings_model_signed_in),
                                                trailing = { StatusDot(active = true) },
                                            )
                                        }
                                    }
                                }
                            }

                            item {
                                SettingsSection(
                                    title = stringResource(R.string.settings_model_custom),
                                    footer = stringResource(R.string.settings_model_custom_hint),
                                ) {
                                    providers.profiles.forEachIndexed { index, profile ->
                                        if (index > 0) SettingsRowDivider()
                                        val keyReady = state.credentials[
                                            profile.apiKeyEnv ?: deriveProviderKeyRef(profile.route)
                                        ]?.configured == true
                                        SettingsRow(
                                            title = profile.label,
                                            leading = { LetterAvatar(profile.label) },
                                            subtitle = stringResource(
                                                R.string.settings_model_custom_subtitle,
                                                protocolLabel(profile.api),
                                                profile.models.size,
                                            ),
                                            trailing = {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    StatusDot(active = keyReady)
                                                    Spacer(Modifier.width(Dshm.spacing.xs))
                                                    Chevron()
                                                }
                                            },
                                            onClick = {
                                                editor = ProviderEditorTarget(existing = profile)
                                            },
                                        )
                                    }
                                    if (providers.profiles.isNotEmpty()) SettingsRowDivider()
                                    SettingsRow(
                                        title = stringResource(R.string.settings_model_add),
                                        leading = { PlusTile() },
                                        enabled = state.description?.namespace(PROVIDER_NAMESPACE)?.writable == true,
                                        trailing = {},
                                        onClick = {
                                            editor = ProviderEditorTarget(existing = null)
                                        },
                                    )
                                }
                            }

                            if (state.description?.namespace(PROVIDER_NAMESPACE) == null) {
                                item { SettingsHint(stringResource(R.string.info_engine_required)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderEditorScreen(
    viewModel: EngineSettingsViewModel,
    existing: ProviderProfile?,
    language: String,
    onBack: () -> Unit,
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val zh = SettingsCopy.isChinese(language)
    val providers = viewModel.providers()
    var displayName by remember(existing) { mutableStateOf(existing?.displayName.orEmpty()) }
    var api by remember(existing) { mutableStateOf(existing?.api ?: providerProtocols.first().first) }
    var baseUrl by remember(existing) { mutableStateOf(existing?.baseUrl.orEmpty()) }
    var models by remember(existing) { mutableStateOf(existing?.models?.joinToString("\n") { it.id }.orEmpty()) }
    var apiKey by remember(existing) { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var discovering by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var discovered by remember { mutableStateOf<Int?>(null) }
    val route = existing?.route ?: derivedRoute(displayName, providers.profiles)
    val keyRef = existing?.apiKeyEnv ?: deriveProviderKeyRef(route)
    val credential = state.credentials[keyRef]
    val configured = credential?.configured == true
    val keyWritable = credential?.writable != false

    fun attemptSave() {
        error = null
        val modelIds = models.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (apiKey.isNotBlank() && !keyWritable) {
            error = if (zh) "该凭据由启动环境提供，无法在这里修改" else "This credential comes from the launch environment"
            return
        }
        if (displayName.isBlank()) {
            error = if (zh) "请填写名称" else "Enter a name"
            return
        }
        if (baseUrl.isBlank()) {
            error = if (zh) "请填写服务地址" else "Enter a base URL"
            return
        }
        if (modelIds.isEmpty()) {
            error = if (zh) "至少填写一个模型" else "Add at least one model"
            return
        }
        if (!providerRoutePattern.matches(route)) {
            error = if (zh) "名称生成的渠道 ID 不合法，请换一个名称" else "The generated route id is invalid; rename it"
            return
        }
        saving = true
        viewModel.saveProvider(
            route = route,
            displayName = displayName,
            api = api,
            baseUrl = baseUrl,
            modelIds = modelIds,
            apiKey = apiKey,
            existing = existing,
        ) { failure ->
            saving = false
            if (failure == null) onBack() else error = failure
        }
    }

    SettingsPage(
        title = if (existing == null) {
            stringResource(R.string.settings_model_add)
        } else {
            stringResource(R.string.settings_model_edit)
        },
        onBack = onBack,
    ) {
        item {
            SettingsSection(title = stringResource(R.string.settings_model_basic)) {
                ProviderField(
                    label = stringResource(R.string.settings_model_name),
                    value = displayName,
                    onValueChange = {
                        displayName = it
                        error = null
                    },
                    placeholder = stringResource(R.string.settings_model_name_hint),
                )
                if (existing == null) {
                    Text(
                        text = stringResource(R.string.settings_model_route_hint, route),
                        style = Dshm.typography.rowTime,
                        color = Dshm.colors.textTertiary,
                        modifier = Modifier.padding(horizontal = Dshm.spacing.lg, vertical = Dshm.spacing.xs),
                    )
                }
                Text(
                    text = stringResource(R.string.settings_model_protocol),
                    style = Dshm.typography.rowTitle,
                    color = Dshm.colors.textPrimary,
                    modifier = Modifier.padding(
                        start = Dshm.spacing.lg,
                        end = Dshm.spacing.lg,
                        top = Dshm.spacing.md,
                        bottom = Dshm.spacing.xs,
                    ),
                )
                ProtocolSelector(selected = api, onSelect = { api = it })
                Spacer(Modifier.height(Dshm.spacing.lg))
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.settings_model_connection)) {
                ProviderField(
                    label = stringResource(R.string.settings_model_base_url),
                    value = baseUrl,
                    onValueChange = {
                        baseUrl = it
                        error = null
                    },
                    placeholder = "https://api.example.com/v1",
                    keyboardType = KeyboardType.Uri,
                )
                Row(
                    modifier = Modifier.padding(horizontal = Dshm.spacing.lg, vertical = Dshm.spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.settings_model_api_key_label),
                        style = Dshm.typography.rowTitle,
                        color = Dshm.colors.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        clipboard.getText()?.text?.trim()?.let { apiKey = it }
                        error = null
                    }) {
                        Text(
                            stringResource(R.string.settings_model_paste),
                            color = Dshm.colors.accent,
                        )
                    }
                }
                Row(
                    modifier = Modifier.padding(horizontal = Dshm.spacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SettingsInput(
                        value = apiKey,
                        onValueChange = {
                            apiKey = it
                            error = null
                        },
                        placeholder = if (configured) {
                            stringResource(R.string.settings_secret_keep_hint)
                        } else {
                            "sk-…"
                        },
                        keyboardType = KeyboardType.Password,
                        visualTransformation = if (showKey) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(
                            painter = painterResource(
                                if (showKey) R.drawable.ic_lucide_eye_off else R.drawable.ic_lucide_eye,
                            ),
                            contentDescription = if (showKey) {
                                stringResource(R.string.settings_model_hide_key)
                            } else {
                                stringResource(R.string.settings_model_show_key)
                            },
                            tint = Dshm.colors.textTertiary,
                        )
                    }
                }
                Spacer(Modifier.height(Dshm.spacing.lg))
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.settings_model_models)) {
                SettingsRow(
                    title = stringResource(R.string.settings_model_fetch_models),
                    icon = R.drawable.ic_lucide_refresh_cw,
                    subtitle = when {
                        discovering -> stringResource(R.string.action_loading)
                        discovered != null -> stringResource(R.string.settings_model_fetched, discovered ?: 0)
                        else -> stringResource(R.string.settings_model_fetch_hint)
                    },
                    enabled = !discovering,
                    onClick = {
                        error = null
                        discovering = true
                        viewModel.discoverModels(baseUrl, api, apiKey) { ids, failure ->
                            discovering = false
                            if (failure != null) {
                                error = failure
                            } else if (ids != null) {
                                models = ids.joinToString("\n")
                                discovered = ids.size
                            }
                        }
                    },
                )
                SettingsRowDivider()
                ProviderField(
                    label = stringResource(R.string.settings_model_list),
                    value = models,
                    onValueChange = {
                        models = it
                        error = null
                    },
                    placeholder = "deepseek-chat\ndeepseek-reasoner",
                    multiline = true,
                )
            }
        }

        if (error != null) {
            item { SettingsError(error.orEmpty()) }
        }

        item {
            SettingsPrimaryButton(
                text = if (saving) stringResource(R.string.action_saving) else stringResource(R.string.action_save),
                enabled = !saving,
                onClick = { attemptSave() },
            )
        }

        if (existing != null && viewModel.providers().removable(existing.route)) {
            item {
                SettingsDangerButton(
                    text = stringResource(R.string.settings_model_delete),
                    enabled = !saving,
                    onClick = { confirmDelete = true },
                )
            }
        }
    }

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Dshm.colors.surface,
            title = {
                Text(
                    text = stringResource(R.string.settings_model_delete),
                    style = Dshm.typography.sheetTitle,
                    color = Dshm.colors.textPrimary,
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.settings_model_delete_confirm, existing.label),
                    style = Dshm.typography.rowPreview,
                    color = Dshm.colors.textSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    saving = true
                    viewModel.deleteProvider(existing.route) { failure ->
                        saving = false
                        if (failure == null) onBack() else error = failure
                    }
                }) {
                    Text(stringResource(R.string.action_delete), color = Dshm.colors.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel), color = Dshm.colors.textSecondary)
                }
            },
        )
    }
}

private fun derivedRoute(name: String, existing: List<ProviderProfile>): String {
    val base = name.lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .take(24)
    val safe = when {
        base.isEmpty() -> "provider"
        base.first().isDigit() -> "p-$base"
        else -> base
    }
    var route = safe
    var index = 2
    while (existing.any { it.route == route }) {
        route = "$safe-$index"
        index++
    }
    return route
}

private fun protocolLabel(api: String?): String = when (api) {
    "openai-completions" -> "OpenAI"
    "openai-responses" -> "Responses"
    "anthropic-messages" -> "Anthropic"
    else -> api ?: "OpenAI"
}

@Composable
private fun ProviderField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    multiline: Boolean = false,
) {
    Column(
        modifier = Modifier.padding(horizontal = Dshm.spacing.lg, vertical = Dshm.spacing.sm),
    ) {
        Text(
            text = label,
            style = Dshm.typography.rowTitle,
            color = Dshm.colors.textPrimary,
        )
        Spacer(Modifier.height(Dshm.spacing.xs))
        SettingsInput(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            singleLine = !multiline,
            keyboardType = keyboardType,
            modifier = if (multiline) Modifier.height(Dshm.spacing.xxl * 4) else Modifier,
        )
    }
}

@Composable
private fun ProtocolSelector(selected: String, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dshm.spacing.lg)
            .clip(RoundedCornerShape(Dshm.radius.md))
            .background(Dshm.colors.surfaceMuted)
            .padding(Dshm.spacing.xxs),
        horizontalArrangement = Arrangement.spacedBy(Dshm.spacing.xxs),
    ) {
        providerProtocols.forEach { (wire, label) ->
            val active = selected == wire
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(Dshm.radius.sm))
                    .then(
                        if (active) {
                            Modifier.background(Dshm.colors.surface)
                                .border(Dshm.spacing.hairline, Dshm.colors.divider, RoundedCornerShape(Dshm.radius.sm))
                        } else {
                            Modifier
                        },
                    )
                    .clickable { onSelect(wire) }
                    .padding(vertical = Dshm.spacing.sm),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = Dshm.typography.rowPreview,
                    color = if (active) Dshm.colors.textPrimary else Dshm.colors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun LetterAvatar(text: String) {
    Box(
        modifier = Modifier
            .size(Dshm.spacing.composerKey)
            .clip(CircleShape)
            .background(Dshm.colors.surfaceMuted),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text.trim().take(1).uppercase().ifEmpty { "?" },
            style = Dshm.typography.rowTitle,
            color = Dshm.colors.textSecondary,
        )
    }
}

@Composable
private fun PlusTile() {
    Box(
        modifier = Modifier
            .size(Dshm.spacing.composerKey)
            .clip(CircleShape)
            .background(Dshm.colors.surfaceMuted),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_lucide_plus),
            contentDescription = null,
            tint = Dshm.colors.textSecondary,
            modifier = Modifier.size(Dshm.spacing.topBarIcon),
        )
    }
}

@Composable
private fun StatusDot(active: Boolean) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(if (active) Dshm.colors.success else Dshm.colors.textTertiary),
    )
}

@Composable
private fun Chevron() {
    Icon(
        painter = painterResource(R.drawable.ic_lucide_chevron_right),
        contentDescription = null,
        tint = Dshm.colors.textTertiary,
        modifier = Modifier.size(Dshm.spacing.topBarIcon),
    )
}
