package com.dshm.bot.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.ui.settings.dshmFieldColors
import com.dshm.bot.ui.settings.dshmFieldShape
import com.dshm.bot.data.remote.DshClients
import com.dshm.bot.data.remote.RemoteDshApi
import com.dshm.bot.data.runtime.RuntimeEndpoint
import com.dshm.bot.domain.model.AvatarGlyph
import com.dshm.bot.domain.model.BotInks
import com.dshm.bot.domain.model.BotShapeDefaults
import com.dshm.bot.ui.components.BotAvatar
import com.dshm.bot.ui.settings.SettingsCancelButton
import com.dshm.bot.ui.settings.SettingsPage
import com.dshm.bot.ui.settings.SettingsSection
import org.json.JSONObject

data class NewBotCatalog(
    val models: List<BotModelOption> = emptyList(),
    val defaultModel: String? = null,
    val existingNames: Set<String> = emptySet(),
)

private suspend fun loadBotCatalog(endpoint: RuntimeEndpoint?): NewBotCatalog {
    if (endpoint == null) return NewBotCatalog()
    val state = RemoteDshApi(DshClients.of(endpoint)).bot("state")
    val models = buildList {
        state.optJSONArray("models")?.let { array ->
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val ref = item.optString("ref")
                if (ref.isNotEmpty()) add(BotModelOption(ref, item.optString("name").ifEmpty { ref }))
            }
        }
    }
    val names = buildSet {
        state.optJSONArray("bots")?.let { array ->
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.optString("name")?.lowercase()?.let(::add)
            }
        }
    }
    val defaultRef = state.optString("defaultModel").takeIf { it.isNotEmpty() }
    val selected = when {
        defaultRef != null && models.any { it.ref == defaultRef } -> defaultRef
        else -> models.firstOrNull()?.ref
    }
    return NewBotCatalog(models, selected, names)
}

private suspend fun createBot(
    endpoint: RuntimeEndpoint?,
    name: String,
    role: String,
    brief: String,
    model: String?,
    color: String,
    shape: String,
): Result<String> {
    if (endpoint == null) return Result.failure(IllegalStateException("本机引擎未运行"))
    return runCatching {
        val payload = JSONObject()
            .put("name", name)
            .put("role", role)
            .put("color", color)
            .put("avatar", JSONObject().put("shape", shape))
            .put("brief", brief)
        if (!model.isNullOrBlank()) payload.put("model", model)
        val value = RemoteDshApi(DshClients.of(endpoint)).bot("create-bot", payload)
        val id = value.optString("id")
        require(id.isNotEmpty()) { "创建 Bot 没有返回 id" }
        id
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewBotScreen(
    endpoint: RuntimeEndpoint?,
    onBack: () -> Unit,
    onCreated: (String) -> Unit,
) {
    var catalog by remember { mutableStateOf(NewBotCatalog()) }
    var name by rememberSaveable { mutableStateOf("") }
    var role by rememberSaveable { mutableStateOf("") }
    var brief by rememberSaveable { mutableStateOf("") }
    var shape by rememberSaveable { mutableStateOf(BotShapeDefaults.shapes.random()) }
    var color by rememberSaveable { mutableStateOf(BotInks.pickerOrder.random()) }
    var model by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(endpoint?.baseUrl, endpoint?.token) {
        catalog = runCatching { loadBotCatalog(endpoint) }.getOrDefault(NewBotCatalog())
        if (model == null) model = catalog.defaultModel
    }

    val fallbackName = remember(catalog.existingNames) {
        generateSequence(1) { it + 1 }
            .map { if (it == 1) "New Bot" else "New Bot $it" }
            .first { it.lowercase() !in catalog.existingNames }
    }
    val finalName = name.trim().ifEmpty { fallbackName }
    val nameProblem = botNameProblem(finalName, catalog.existingNames)
    val selectedModel = model ?: catalog.defaultModel
    val fieldShape = dshmFieldShape()
    val fieldColors = dshmFieldColors()

    SettingsPage(
        title = stringResource(R.string.home_new_bot),
        onBack = onBack,
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Dshm.colors.pageBackground)
                    // 底栏要吃系统导航栏 inset（否则按钮贴着屏幕底边），
                    // 再留一档自己的下边距让按钮「抬起来」。
                    .navigationBarsPadding()
                    .padding(horizontal = Dshm.spacing.lg, vertical = Dshm.spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(Dshm.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SettingsCancelButton(
                    text = stringResource(android.R.string.cancel),
                    onClick = onBack,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            createBot(endpoint, finalName, role.trim(), brief.trim(), selectedModel, color, shape)
                                .onSuccess { id -> onCreated(id) }
                                .onFailure { failure ->
                                    busy = false
                                    error = failure.message ?: "创建 Bot 失败"
                                }
                        }
                    },
                    enabled = !busy && nameProblem == null,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Dshm.colors.accent,
                        contentColor = Dshm.colors.onAccent,
                    ),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        if (busy) stringResource(R.string.new_bot_creating)
                        else stringResource(R.string.room_settings_create),
                    )
                }
            }
        },
    ) {
        item {
            SettingsSection {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Dshm.spacing.lg),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    BotAvatar(
                        inkId = color,
                        glyph = AvatarGlyph.BOT,
                        avatarShape = shape,
                        size = Dshm.spacing.settingsAvatar,
                    )
                    Spacer(Modifier.height(Dshm.spacing.sm))
                    Text(
                        text = finalName,
                        style = Dshm.typography.sheetTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.bot_settings_look)) {
                Column(Modifier.padding(Dshm.spacing.md)) {
                    Text(stringResource(R.string.bot_settings_color), style = Dshm.typography.rowPreview)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Dshm.spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(Dshm.spacing.sm),
                        modifier = Modifier.padding(vertical = Dshm.spacing.sm),
                    ) {
                        BotInks.pickerOrder.forEach { ink ->
                            Box(
                                modifier = Modifier
                                    .size(Dshm.spacing.lg + Dshm.spacing.xs)
                                    .clip(CircleShape)
                                    .border(
                                        width = if (ink == color) 2.dp else 0.dp,
                                        color = if (ink == color) Dshm.colors.textPrimary else Color.Transparent,
                                        shape = CircleShape,
                                    )
                                    .padding(4.dp)
                                    .clip(CircleShape)
                                    .background(Color(BotInks.color(ink, Dshm.colors.isDark)))
                                    .clickable { color = ink },
                            )
                        }
                    }
                    Text(stringResource(R.string.bot_settings_shape), style = Dshm.typography.rowPreview)
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(Dshm.spacing.xs),
                        contentPadding = PaddingValues(vertical = Dshm.spacing.xs),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        items(listOf("whale") + BotShapeDefaults.shapes) { option ->
                            TextButton(
                                onClick = { shape = option },
                                shape = RoundedCornerShape(Dshm.radius.md),
                                modifier = Modifier
                                    .width(88.dp)
                                    .then(
                                        if (option == shape) Modifier.border(
                                            width = Dshm.spacing.hairline,
                                            color = Dshm.colors.divider,
                                            shape = RoundedCornerShape(Dshm.radius.md),
                                        ) else Modifier,
                                    ),
                            ) { Text(shapeLabel(option), maxLines = 1) }
                        }
                    }
                }
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.bot_settings_profile)) {
                Column(
                    modifier = Modifier.padding(Dshm.spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Dshm.spacing.sm),
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(40) },
                        label = { Text(stringResource(R.string.bot_settings_name)) },
                        placeholder = { Text(fallbackName) },
                        singleLine = true,
                        isError = nameProblem != null,
                        shape = fieldShape,
                        colors = fieldColors,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    nameProblem?.let { Text(it, color = Dshm.colors.danger, style = Dshm.typography.rowPreview) }
                    OutlinedTextField(
                        value = role,
                        onValueChange = { role = it.take(24) },
                        label = { Text(stringResource(R.string.bot_settings_role)) },
                        singleLine = true,
                        shape = fieldShape,
                        colors = fieldColors,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = brief,
                        onValueChange = { brief = it },
                        label = { Text(stringResource(R.string.new_bot_brief)) },
                        placeholder = { Text(stringResource(R.string.bot_settings_note_hint)) },
                        minLines = 3,
                        maxLines = 5,
                        shape = fieldShape,
                        colors = fieldColors,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    BotModelComboBox(
                        models = catalog.models,
                        selectedRef = selectedModel,
                        onSelect = { ref -> model = ref },
                        enabled = !busy,
                    )
                    error?.let { message ->
                        Text(message, color = Dshm.colors.danger, style = Dshm.typography.rowPreview)
                    }
                }
            }
        }
    }
}

private fun botNameProblem(name: String, existing: Set<String>): String? = when {
    name.isBlank() -> "Bot 需要名字"
    name.any { it == '@' || it == '`' || it == '\r' || it == '\n' } -> "名字不能包含 @、` 或换行"
    name.lowercase() in setOf("all", "everyone", "所有人", "全体") -> "这个名字会呼叫全体成员"
    name.lowercase() in setOf("user", "you") -> "这个名字会与用户混淆"
    name.lowercase() in existing -> "已存在同名 Bot"
    else -> null
}

@Composable
private fun shapeLabel(shape: String): String = when (shape) {
    "whale" -> stringResource(R.string.bot_shape_whale)
    "star" -> "Star"
    "moon" -> "Moon"
    "gumdrop" -> "Gumdrop"
    "peanut" -> "Peanut"
    "bunny" -> "Bunny"
    "kitty" -> "Kitty"
    "acorn" -> "Acorn"
    else -> shape.replaceFirstChar { it.uppercase() }
}
