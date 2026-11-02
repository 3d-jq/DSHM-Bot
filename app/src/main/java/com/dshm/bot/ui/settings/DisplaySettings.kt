package com.dshm.bot.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.domain.model.AppLanguage
import com.dshm.bot.domain.model.ChatFontSize

/** 外观 → 对话字号。 */
@Composable
fun ChatFontScreen(
    current: ChatFontSize,
    onSelect: (ChatFontSize) -> Unit,
    onBack: () -> Unit,
) {
    SettingsPageScaffold(title = stringResource(R.string.settings_chat_font), onBack = onBack) {
        OptionCard {
            ChatFontSize.entries.forEach { size ->
                OptionRow(
                    label = chatFontLabel(size),
                    selected = size == current,
                    onClick = { onSelect(size) },
                )
            }
        }
        Spacer(Modifier.padding(Dshm.spacing.xs))
    }
}

/** 通用 → 语言。 */
@Composable
fun LanguageScreen(
    current: AppLanguage,
    onSelect: (AppLanguage) -> Unit,
    onBack: () -> Unit,
) {
    SettingsPageScaffold(title = stringResource(R.string.settings_language), onBack = onBack) {
        OptionCard {
            AppLanguage.entries.forEach { language ->
                OptionRow(
                    label = languageLabel(language),
                    selected = language == current,
                    onClick = { onSelect(language) },
                )
            }
        }
    }
}

@Composable
private fun OptionCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dshm.radius.lg))
            .background(Dshm.colors.surface)
            .padding(vertical = Dshm.spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Dshm.spacing.xxs),
    ) {
        content()
    }
}

@Composable
private fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Dshm.spacing.lg, vertical = Dshm.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(Dshm.spacing.sm))
        Text(text = label, style = Dshm.typography.rowTitle, color = Dshm.colors.textPrimary)
    }
}

@Composable
internal fun chatFontLabel(size: ChatFontSize): String = stringResource(
    when (size) {
        ChatFontSize.SMALL -> R.string.chat_font_small
        ChatFontSize.STANDARD -> R.string.settings_value_standard
        ChatFontSize.LARGE -> R.string.chat_font_large
        ChatFontSize.XLARGE -> R.string.chat_font_xlarge
    },
)

@Composable
internal fun languageLabel(language: AppLanguage): String = stringResource(
    when (language) {
        AppLanguage.SYSTEM -> R.string.settings_value_follow_system
        AppLanguage.CHINESE -> R.string.language_chinese
        AppLanguage.ENGLISH -> R.string.language_english
    },
)
