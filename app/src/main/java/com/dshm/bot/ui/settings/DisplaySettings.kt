package com.dshm.bot.ui.settings

import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.dshm.bot.R
import com.dshm.bot.domain.model.AppLanguage
import com.dshm.bot.domain.model.ChatFontSize

/** 外观 → 对话字号。 */
@Composable
fun ChatFontScreen(
    current: ChatFontSize,
    onSelect: (ChatFontSize) -> Unit,
    onBack: () -> Unit,
) {
    SettingsPage(title = stringResource(R.string.settings_chat_font), onBack = onBack) {
        item {
            SettingsSection {
                ChatFontSize.entries.forEachIndexed { index, size ->
                    if (index > 0) SettingsRowDivider()
                    SettingsRow(
                        title = chatFontLabel(size),
                        trailing = {
                            RadioButton(selected = size == current, onClick = { onSelect(size) })
                        },
                        onClick = { onSelect(size) },
                    )
                }
            }
        }
    }
}

/** 通用 → 语言。 */
@Composable
fun LanguageScreen(
    current: AppLanguage,
    onSelect: (AppLanguage) -> Unit,
    onBack: () -> Unit,
) {
    SettingsPage(title = stringResource(R.string.settings_language), onBack = onBack) {
        item {
            SettingsSection {
                AppLanguage.entries.forEachIndexed { index, language ->
                    if (index > 0) SettingsRowDivider()
                    SettingsRow(
                        title = languageLabel(language),
                        trailing = {
                            RadioButton(selected = language == current, onClick = { onSelect(language) })
                        },
                        onClick = { onSelect(language) },
                    )
                }
            }
        }
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
