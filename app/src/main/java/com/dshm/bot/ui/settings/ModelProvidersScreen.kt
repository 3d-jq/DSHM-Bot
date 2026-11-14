package com.dshm.bot.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.activity.compose.BackHandler
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.core.design.DshmMotion
import com.dshm.bot.data.remote.ModelDefaults
import com.dshm.bot.data.remote.PROVIDER_NAMESPACE
import com.dshm.bot.data.remote.ProviderHealth
import com.dshm.bot.data.remote.ProviderHealthEvaluator
import com.dshm.bot.data.remote.ProviderModelSpec
import com.dshm.bot.data.remote.ProviderProfile
import com.dshm.bot.data.remote.deriveProviderKeyRef
import com.dshm.bot.data.remote.isValidProviderBaseUrl
import com.dshm.bot.data.remote.normalizeDiscoveryBaseUrl
import com.dshm.bot.data.remote.providerRoutePattern

/** 渠道表单的草稿：放在页面外壳里，模型设置页返回时才有地方回填。 */
private data class ProviderDraft(
    val existing: ProviderProfile?,
    val displayName: String,
    val api: String,
    val baseUrl: String,
    val apiKey: String,
    val showKey: Boolean,
    val models: List<ProviderModelSpec>,
)

/**
 * 内层导航栈的 saveable 化：把 target 序列化成 route / model id 这些简单参数。
 * 恢复时按 route 从当前引擎状态反查 profile / model；渠道被删或引擎还没读回来的层
 * 直接丢弃（落回上一层或列表）。`editor|` 空 route 表示「新建渠道」的编辑层，原样还原。
 */
private fun providerStackSaver(
    profileOf: (String) -> ProviderProfile?,
    // listSaver 的 Saveable 泛型在返回值里被擦成 Any（restore 会转回 Original），
    // 这里声明成 Saver<..., Any> 与之对齐。
): Saver<List<ProviderTarget>, Any> = listSaver(
    save = { stack -> stack.map { it.encodeTarget() } },
    restore = { encoded -> encoded.mapNotNull { it.decodeTarget(profileOf) } },
)

private fun ProviderTarget.encodeTarget(): String = when (this) {
    is ProviderTarget.Detail -> "detail|$route"
    is ProviderTarget.Editor -> "editor|${existing?.route.orEmpty()}"
    is ProviderTarget.Model -> "model|$route|${model.id}|${if (writes) "1" else "0"}"
}

private fun String.decodeTarget(profileOf: (String) -> ProviderProfile?): ProviderTarget? {
    val parts = split('|')
    return when (parts.getOrNull(0)) {
        "detail" -> parts.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let { ProviderTarget.Detail(it) }
        "editor" -> {
            val route = parts.getOrNull(1).orEmpty()
            if (route.isEmpty()) {
                ProviderTarget.Editor(existing = null)
            } else {
                profileOf(route)?.let { ProviderTarget.Editor(existing = it) }
            }
        }
        "model" -> {
            val route = parts.getOrNull(1) ?: return null
            val modelId = parts.getOrNull(2) ?: return null
            val writes = parts.getOrNull(3) == "1"
            val model = profileOf(route)?.models?.firstOrNull { it.id == modelId } ?: return null
            ProviderTarget.Model(route = route, model = model, writes = writes)
        }
        else -> null
    }
}

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
    val providers = viewModel.providers()
    // 内层导航栈：列表（空）→ 详情 → 编辑 / 模型。用栈而不是单个目标，
    // 返回才永远是「弹一层」；栈为空（列表态）时不拦截，交回顶层路由。
    // 栈本身走 rememberSaveable 扛 Activity 重建（深页返回状态），profile/model
    // 按 route 从当前引擎状态反查；draft 含密钥，特意不进 saveable，重建后回列表。
    var targets by rememberSaveable(
        stateSaver = providerStackSaver { route ->
            providers.profiles.firstOrNull { it.route == route }
        },
    ) { mutableStateOf<List<ProviderTarget>>(ProviderNav.reset()) }
    val page = ProviderNav.current(targets)
    BackHandler(enabled = page != null) {
        targets = ProviderNav.back(targets)
    }
    var draft by remember { mutableStateOf<ProviderDraft?>(null) }
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
    // 渠道健康度按 dsh-Go 的四态展示：可用 / 缺少 API Key / 配置有误 / 未启用。
    val healthOf: (ProviderProfile) -> ProviderHealth = { profile ->
        ProviderHealthEvaluator.evaluate(
            profile,
            state.credentials[profile.apiKeyEnv ?: deriveProviderKeyRef(profile.route)]?.configured == true,
        )
    }
    val stateHolder = rememberSaveableStateHolder()
    // 动效档位（团队偏好 `prefs.motion`）：转场时长从这里取。
    // transitionSpec 不是 composable 上下文，所以要在外面取好。
    val motionProfile = Dshm.motion

    AnimatedContent(
        targetState = page,
        transitionSpec = {
            // 动效档位（团队偏好 `prefs.motion`）：`quiet` 直接不转场。
            val duration = if (reduceMotion) 0 else motionProfile.screenTransitionMs
            val fade = if (reduceMotion) 0 else motionProfile.fadeMs
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
        stateHolder.SaveableStateProvider(
            when (target) {
                is ProviderTarget.Detail -> "detail"
                is ProviderTarget.Editor -> "editor"
                is ProviderTarget.Model -> "model"
                null -> "providers"
            },
        ) {
            val current = target
            if (current is ProviderTarget.Detail) {
                ProviderDetailPage(
                    viewModel = viewModel,
                    route = current.route,
                    onBack = { targets = ProviderNav.back(targets) },
                    onEdit = { profile ->
                        draft = ProviderDraft(
                            existing = profile,
                            displayName = profile?.displayName.orEmpty(),
                            api = profile?.api ?: providerProtocols.first().first,
                            baseUrl = profile?.baseUrl.orEmpty(),
                            apiKey = "",
                            showKey = false,
                            models = profile?.models.orEmpty(),
                        )
                        targets = ProviderNav.push(targets, ProviderTarget.Editor(profile))
                    },
                    onOpenModel = { model ->
                        targets = ProviderNav.push(
                            targets,
                            ProviderTarget.Model(current.route, model, writes = true),
                        )
                    },
                )
            } else if (current is ProviderTarget.Editor) {
                val currentDraft = draft
                if (currentDraft == null) {
                    targets = ProviderNav.reset()
                } else {
                    ProviderEditorScreen(
                        viewModel = viewModel,
                        draft = currentDraft,
                        onDraftChange = { draft = it },
                        language = language,
                        reduceMotion = reduceMotion,
                        onBack = { targets = ProviderNav.back(targets) },
                        onOpenModel = { route, model ->
                            targets = ProviderNav.push(
                                targets,
                                ProviderTarget.Model(route, model, writes = false),
                            )
                        },
                        onSaved = { targets = ProviderNav.reset() },
                    )
                }
            } else if (current is ProviderTarget.Model) {
                val ui = remember(current) { mutableStateOf(ModelSaveUi()) }
                ProviderModelPage(
                    model = current.model,
                    defaults = ModelDefaults.of(
                        providers.profiles.firstOrNull { it.route == current.route },
                    ),
                    submitLabel = stringResource(
                        if (current.writes) R.string.action_save else R.string.settings_model_item_done,
                    ),
                    saving = ui.value.saving,
                    error = ui.value.error,
                    reduceMotion = reduceMotion,
                    onSubmit = { spec ->
                        if (current.writes) {
                            // 详情页进来：直接写引擎，写完回到详情页。
                            ui.value = ui.value.copy(saving = true, error = null)
                            viewModel.saveProviderModel(current.route, spec) { failure ->
                                ui.value = ui.value.copy(saving = false, error = failure)
                                if (failure == null) targets = ProviderNav.back(targets)
                            }
                        } else {
                            // 表单进来：交回表单，跟着表单一起保存。
                            val editing = draft
                            if (editing != null) {
                                draft = editing.copy(
                                    models = editing.models.map { if (it.id == spec.id) spec else it },
                                )
                                targets = ProviderNav.back(targets)
                            }
                        }
                    },
                    onBack = { targets = ProviderNav.back(targets) },
                )
            } else {
                SettingsPage(
                    title = stringResource(R.string.settings_model),
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
                                    // dsh-Go：卡内行间没有分割线，靠行内边距分组；
                                    // 状态用「8dp 色点 + 文字副标题」表达。
                                    SettingsRow(
                                        title = "DeepSeek",
                                        leading = { Monogram("DeepSeek") },
                                        subtitle = if (accountSignedIn) {
                                            stringResource(R.string.settings_model_signed_in)
                                        } else {
                                            stringResource(R.string.settings_model_sign_in_or_key)
                                        },
                                        trailing = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                StatusDot(
                                                    if (accountSignedIn) StatusTone.Ok else StatusTone.Idle,
                                                )
                                                Spacer(Modifier.width(Dshm.spacing.xs))
                                                Chevron()
                                            }
                                        },
                                        onClick = onOpenAccount,
                                    )
                                    otherRegistered.forEach { provider ->
                                        SettingsRow(
                                            title = provider.name,
                                            leading = { Monogram(provider.name) },
                                            subtitle = provider.id,
                                            trailing = {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    StatusDot(StatusTone.Ok)
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
                                        // dsh-Go：未登录时右侧是 tonal「登录」胶囊。本项目还没接引擎的
                                        // appAuth 登录桥，胶囊置灰并写明原因，不做点了没反应的假按钮。
                                        subscriptions.forEach { provider ->
                                            SettingsRow(
                                                title = provider.name,
                                                leading = { Monogram(provider.name) },
                                                subtitle = stringResource(R.string.settings_model_subscription_unsupported),
                                                trailing = {
                                                    Text(
                                                        text = stringResource(R.string.settings_model_subscription_login),
                                                        style = Dshm.typography.rowPreview,
                                                        color = Dshm.colors.textTertiary,
                                                        modifier = Modifier
                                                            .clip(RoundedCornerShape(Dshm.radius.pill))
                                                            .background(Dshm.colors.surfaceMuted)
                                                            .padding(
                                                                horizontal = Dshm.spacing.md,
                                                                vertical = Dshm.spacing.xs,
                                                            ),
                                                    )
                                                },
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
                                    providers.profiles.forEach { profile ->
                                        // dsh-Go：状态四态（可用 / 缺少 API Key / 配置有误 / 未启用），
                                        // 用「色点 + 文字副标题」表达，卡内行间不画分割线。
                                        val health = healthOf(profile)
                                        SettingsRow(
                                            title = profile.label,
                                            leading = { Monogram(profile.label) },
                                            subtitle = when (health) {
                                                ProviderHealth.READY -> stringResource(
                                                    R.string.settings_model_custom_subtitle,
                                                    protocolLabel(profile.api),
                                                    profile.models.size,
                                                )
                                                ProviderHealth.MISSING_KEY ->
                                                    stringResource(R.string.settings_model_health_missing_key)
                                                ProviderHealth.MISCONFIGURED ->
                                                    stringResource(R.string.settings_model_health_misconfigured)
                                                ProviderHealth.DISABLED ->
                                                    stringResource(R.string.settings_model_health_disabled)
                                            },
                                            trailing = {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    StatusDot(health.toStatusTone())
                                                    Spacer(Modifier.width(Dshm.spacing.xs))
                                                    Chevron()
                                                }
                                            },
                                            onClick = {
                                                targets = ProviderNav.push(
                                                    targets,
                                                    ProviderTarget.Detail(profile.route),
                                                )
                                            },
                                        )
                                    }
                                    SettingsRow(
                                        title = stringResource(R.string.settings_model_add),
                                        leading = { SettingsAddTile() },
                                        enabled = state.description?.namespace(PROVIDER_NAMESPACE)?.writable == true,
                                        trailing = {},
                                        onClick = {
                                            draft = ProviderDraft(
                                                existing = null,
                                                displayName = "",
                                                api = providerProtocols.first().first,
                                                baseUrl = "",
                                                apiKey = "",
                                                showKey = false,
                                                models = emptyList(),
                                            )
                                            targets = ProviderNav.push(
                                                targets,
                                                ProviderTarget.Editor(null),
                                            )
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

/** 模型设置页的保存状态（保存中 / 错误）。 */
private data class ModelSaveUi(
    val saving: Boolean = false,
    val error: String? = null,
)

@Composable
private fun ProviderEditorScreen(
    viewModel: EngineSettingsViewModel,
    draft: ProviderDraft,
    onDraftChange: (ProviderDraft) -> Unit,
    language: String,
    reduceMotion: Boolean,
    onBack: () -> Unit,
    onOpenModel: (route: String, model: ProviderModelSpec) -> Unit,
    onSaved: () -> Unit,
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val providers = viewModel.providers()
    val existing = draft.existing
    var saving by remember { mutableStateOf(false) }
    var discovering by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    // dsh-Go：拿到模型后先弹层勾选，不直接覆盖清单。
    var pickerCandidates by remember { mutableStateOf<List<String>>(emptyList()) }
    var showModelPicker by remember { mutableStateOf(false) }
    var showManualModel by remember { mutableStateOf(false) }
    var manualModelId by remember { mutableStateOf("") }
    var manualModelError by remember { mutableStateOf<String?>(null) }
    val route = existing?.route ?: derivedRoute(draft.displayName, providers.profiles)
    val keyRef = existing?.apiKeyEnv ?: deriveProviderKeyRef(route)
    val credential = state.credentials[keyRef]
    val configured = credential?.configured == true
    val keyWritable = credential?.writable != false
    // stringResource 只能在 Composable 作用域调用；attemptSave 是普通局部函数，
    // 所以先在这里取好文案再传进去用。
    val credentialLockedText = stringResource(R.string.settings_model_credential_locked)
    val enterNameText = stringResource(R.string.settings_model_enter_name)
    val enterBaseUrlText = stringResource(R.string.settings_model_enter_base_url)
    val needModelText = stringResource(R.string.settings_model_need_model)
    val invalidRouteText = stringResource(R.string.settings_model_invalid_route)
    val enterKeyText = stringResource(R.string.settings_model_enter_key)
    val invalidBaseUrlText = stringResource(R.string.settings_model_invalid_base_url)

    fun attemptSave() {
        error = null
        val models = draft.models
        if (draft.apiKey.isNotBlank() && !keyWritable) {
            error = credentialLockedText
            return
        }
        if (draft.displayName.isBlank()) {
            error = enterNameText
            return
        }
        if (draft.baseUrl.isBlank()) {
            error = enterBaseUrlText
            return
        }
        if (models.isEmpty()) {
            error = needModelText
            return
        }
        if (!providerRoutePattern.matches(route)) {
            error = invalidRouteText
            return
        }
        if (existing == null && draft.apiKey.isBlank()) {
            error = enterKeyText
            return
        }
        val normalizedBaseUrl = normalizeDiscoveryBaseUrl(draft.baseUrl, draft.api)
        if (!isValidProviderBaseUrl(draft.baseUrl)) {
            error = invalidBaseUrlText
            return
        }
        saving = true
        viewModel.saveProvider(
            route = route,
            displayName = draft.displayName,
            api = draft.api,
            baseUrl = normalizedBaseUrl,
            models = models,
            apiKey = draft.apiKey,
            existing = existing,
        ) { failure ->
            saving = false
            if (failure == null) onSaved() else error = failure
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
                    value = draft.displayName,
                    onValueChange = {
                        onDraftChange(draft.copy(displayName = it))
                        error = null
                    },
                    placeholder = stringResource(R.string.settings_model_name_hint),
                    // 每张卡的第一个字段：字段组顶部留白 12dp（dsh-Go 的 FormFieldGroup）。
                    top = Dshm.spacing.md,
                )
                // 协议也是字段块：标题缩进 4dp，与字段边缘对齐。
                Text(
                    text = stringResource(R.string.settings_model_protocol),
                    style = Dshm.typography.formFieldLabel,
                    color = Dshm.colors.textPrimary,
                    modifier = Modifier.padding(
                        start = Dshm.spacing.lg + Dshm.spacing.xs,
                        end = Dshm.spacing.lg,
                    ),
                )
                Spacer(Modifier.height(Dshm.spacing.xs))
                ProtocolSelector(
                    selected = draft.api,
                    onSelect = { onDraftChange(draft.copy(api = it)) },
                    reduceMotion = reduceMotion,
                )
                Spacer(Modifier.height(Dshm.spacing.lg))
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.settings_model_connection)) {
                ProviderField(
                    label = stringResource(R.string.settings_model_base_url),
                    value = draft.baseUrl,
                    onValueChange = {
                        onDraftChange(draft.copy(baseUrl = it))
                        error = null
                    },
                    // dsh-Go：Anthropic 协议不带 /v1，其余协议带。
                    placeholder = if (draft.api == "anthropic-messages") {
                        "https://api.example.com"
                    } else {
                        "https://api.example.com/v1"
                    },
                    keyboardType = KeyboardType.Uri,
                    top = Dshm.spacing.md,
                )
                // dsh-Go：API Key 也是满宽字段 ——「粘贴」贴在标题行右侧，
                // 「显示 / 隐藏」在输入框内，两框宽度一致才不显得歪。
                ProviderField(
                    label = stringResource(R.string.settings_model_api_key_label),
                    value = draft.apiKey,
                    onValueChange = {
                        onDraftChange(draft.copy(apiKey = it))
                        error = null
                    },
                    placeholder = if (configured) {
                        "••••••••"
                    } else {
                        "sk-…"
                    },
                    keyboardType = KeyboardType.Password,
                    visualTransformation = if (draft.showKey) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailing = {
                        // dsh-Go 用的是矮按钮 FormSmallButton；Material3 的 TextButton 默认
                        // 最小高度 40dp，会把标题行撑高、标签离输入框很远，这里用紧凑文本。
                        Text(
                            text = stringResource(R.string.settings_model_paste),
                            style = Dshm.typography.settingsRowSubtitle,
                            color = Dshm.colors.accent,
                            modifier = Modifier.clickable {
                                clipboard.getText()?.text?.trim()?.let { onDraftChange(draft.copy(apiKey = it)) }
                                error = null
                            },
                        )
                    },
                    suffix = {
                        // 用 Icon + clickable，而不是 IconButton：Material3 的 IconButton 默认
                        // 最小尺寸 48dp，会把这个输入框整体撑高，两个框就高矮不一了。
                        Icon(
                            painter = painterResource(
                                if (draft.showKey) R.drawable.ic_lucide_eye_off else R.drawable.ic_lucide_eye,
                            ),
                            contentDescription = if (draft.showKey) {
                                stringResource(R.string.settings_model_hide_key)
                            } else {
                                stringResource(R.string.settings_model_show_key)
                            },
                            tint = Dshm.colors.textTertiary,
                            modifier = Modifier
                                .size(Dshm.spacing.topBarIcon)
                                .clickable { onDraftChange(draft.copy(showKey = !draft.showKey)) },
                        )
                    },
                )
                if (configured) {
                    // dsh-Go：已保存的 Key 用掩码占位，并在下方说明「留空则不修改」。
                    Text(
                        text = stringResource(R.string.settings_secret_keep_hint),
                        style = Dshm.typography.rowPreview,
                        color = Dshm.colors.textTertiary,
                        modifier = Modifier.padding(
                            start = Dshm.spacing.lg + Dshm.spacing.xs,
                            end = Dshm.spacing.lg,
                            top = Dshm.spacing.xs,
                        ),
                    )
                }
                // dsh-Go：表单页没有「测试连接」，它只在渠道详情页。
                Spacer(Modifier.height(Dshm.spacing.lg))
            }
        }

        item {
            SettingsSection(
                // dsh-Go：模型为空时标题是「模型」，有模型时是「模型 · N」。
                title = if (draft.models.isEmpty()) {
                    stringResource(R.string.settings_model_models)
                } else {
                    stringResource(R.string.settings_model_detail_models, draft.models.size)
                },
            ) {
                SettingsRow(
                    // dsh-Go：获取模型行没有副标题，发现中时标题变「正在获取」并在右侧转圈。
                    title = if (discovering) {
                        stringResource(R.string.settings_model_fetching)
                    } else {
                        stringResource(R.string.settings_model_fetch_models)
                    },
                    icon = R.drawable.ic_lucide_refresh_cw,
                    enabled = !discovering,
                    trailing = if (discovering) {
                        {
                            CircularProgressIndicator(
                                modifier = Modifier.size(Dshm.spacing.topBarIcon),
                                strokeWidth = Dshm.spacing.hairline * 2,
                                color = Dshm.colors.textTertiary,
                            )
                        }
                    } else {
                        {}
                    },
                    onClick = {
                        error = null
                        if (existing == null && draft.apiKey.isBlank()) {
                            error = enterKeyText
                        }
                        else if (!isValidProviderBaseUrl(draft.baseUrl)) {
                            error = invalidBaseUrlText
                        } else {
                            discovering = true
                            viewModel.discoverModels(
                                baseUrl = draft.baseUrl,
                                api = draft.api,
                                apiKey = draft.apiKey,
                                // 已存 route 让引擎解析凭据；draft Key 只覆盖本次新填的值。
                                route = existing?.route,
                            ) { ids, failure, effectiveBaseUrl ->
                                discovering = false
                                if (!effectiveBaseUrl.isNullOrBlank() && effectiveBaseUrl != draft.baseUrl) {
                                    onDraftChange(draft.copy(baseUrl = effectiveBaseUrl))
                                }
                                if (failure != null) {
                                    error = failure
                                } else if (ids != null) {
                                    pickerCandidates = ids
                                    showModelPicker = true
                                }
                            }
                        }
                    },
                )
                // dsh-Go 的模型卡只有「获取模型」+ 已选模型（逐条可删、可打开设置）+「手动添加」，
                // 没有「暂无模型」占位行，也没有独立的「模型设置」行。
                draft.models.forEach { model ->
                    SettingsRowDivider()
                    SettingsRow(
                        title = model.label,
                        icon = R.drawable.ic_lucide_bot,
                        subtitle = providerModelSummary(
                            model,
                            ModelDefaults.of(existing),
                        ).takeIf { it.isNotEmpty() },
                        onClick = { onOpenModel(route, model) },
                        trailing = {
                            IconButton(onClick = {
                                onDraftChange(draft.copy(models = draft.models - model))
                            }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_lucide_x),
                                    contentDescription = stringResource(R.string.action_delete),
                                    tint = Dshm.colors.textTertiary,
                                    modifier = Modifier.size(Dshm.spacing.topBarIcon),
                                )
                            }
                        },
                    )
                }
                SettingsRowDivider()
                // 手动添加模型：dsh-Go 用弹窗输入模型 ID。
                SettingsRow(
                    title = stringResource(R.string.settings_model_add_manual),
                    icon = R.drawable.ic_lucide_plus,
                    // dsh-Go 的 SettingsTile 是 chevron: false，这里同样不出箭头。
                    trailing = {},
                    onClick = { showManualModel = true },
                )
            }
        }

        if (error != null) {
            item { SettingsError(error.orEmpty()) }
        }

        // 保存按钮改成表单里的一张卡，跟着内容滚，不再浮在屏幕底部。
        item {
            SettingsSection {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Dshm.spacing.lg, vertical = Dshm.spacing.md),
                ) {
                    SettingsPrimaryButton(
                        text = when {
                            saving -> stringResource(R.string.action_saving)
                            state.description == null -> stringResource(R.string.settings_model_waiting_engine)
                            else -> stringResource(R.string.action_save)
                        },
                        enabled = !saving && state.description != null,
                        onClick = { attemptSave() },
                    )
                }
            }
        }

        if (existing != null && viewModel.providers().removable(existing.route)) {
            item {
                // 删除渠道单独一张卡，按钮居中（和详情页同一套）。
                SettingsSection {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Spacer(Modifier.height(Dshm.spacing.sm))
                        SettingsDangerButton(
                            text = stringResource(R.string.settings_model_delete),
                            icon = R.drawable.ic_lucide_trash_2,
                            enabled = !saving,
                            onClick = { confirmDelete = true },
                        )
                        Spacer(Modifier.height(Dshm.spacing.sm))
                    }
                }
            }
        }
    }

    if (showModelPicker) {
        ModelPickerSheet(
            candidates = pickerCandidates,
            alreadyAdded = draft.models.map { it.id }.toSet(),
            onConfirm = { picked ->
                // dsh-Go：发现到的模型是追加进渠道，不是覆盖已有清单。
                onDraftChange(
                    draft.copy(
                        models = (draft.models + picked.map { ProviderModelSpec.create(it) })
                            .distinctBy { it.id },
                    ),
                )
                showModelPicker = false
            },
            onDismiss = { showModelPicker = false },
        )
    }

    if (showManualModel) {
        val duplicateText = stringResource(R.string.settings_model_add_manual_duplicate)
        AlertDialog(
            onDismissRequest = { showManualModel = false },
            containerColor = Dshm.colors.surface,
            title = {
                Text(
                    text = stringResource(R.string.settings_model_add_manual),
                    style = Dshm.typography.sheetTitle,
                    color = Dshm.colors.textPrimary,
                )
            },
            text = {
                Column {
                    SettingsInput(
                        value = manualModelId,
                        onValueChange = {
                            manualModelId = it
                            manualModelError = null
                        },
                        placeholder = stringResource(R.string.settings_model_add_manual_hint),
                    )
                    if (manualModelError != null) {
                        Text(
                            text = manualModelError.orEmpty(),
                            style = Dshm.typography.rowPreview,
                            color = Dshm.colors.danger,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = manualModelId.trim()
                        when {
                            id.isEmpty() -> Unit
                            id in draft.models.map { it.id } -> manualModelError = duplicateText
                            else -> {
                                onDraftChange(
                                    draft.copy(models = draft.models + ProviderModelSpec.create(id)),
                                )
                                manualModelId = ""
                                manualModelError = null
                                showManualModel = false
                            }
                        }
                    },
                    enabled = manualModelId.isNotBlank(),
                ) {
                    Text(stringResource(R.string.action_save), color = Dshm.colors.accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { showManualModel = false }) {
                    Text(stringResource(R.string.action_cancel), color = Dshm.colors.textSecondary)
                }
            },
        )
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
                        if (failure == null) onSaved() else error = failure
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
private fun ProtocolSelector(
    selected: String,
    onSelect: (String) -> Unit,
    reduceMotion: Boolean = false,
) {
    SettingsSegmentedPill(
        options = providerProtocols.map { it.second },
        selected = protocolLabel(selected),
        onSelect = { label ->
            providerProtocols.firstOrNull { it.second == label }?.let { onSelect(it.first) }
        },
        modifier = Modifier.padding(horizontal = Dshm.spacing.lg),
        reduceMotion = reduceMotion,
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
