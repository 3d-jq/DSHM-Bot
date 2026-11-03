package com.dshm.bot.ui.settings

import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.dshm.bot.R
import com.dshm.bot.domain.model.PetSize
import com.dshm.bot.domain.model.PetStyle
import com.dshm.bot.ui.components.PetSprite

/** 外观 › 宠物：使用 dsh-Go 的 Deepy / 鲸娘形象。 */
@Composable
fun PetsSettingsScreen(
    enabled: Boolean,
    style: PetStyle,
    size: PetSize,
    onEnabledChange: (Boolean) -> Unit,
    onStyleChange: (PetStyle) -> Unit,
    onSizeChange: (PetSize) -> Unit,
    onBack: () -> Unit,
) {
    val language = settingsLanguage()
    val zh = SettingsCopy.isChinese(language)
    SettingsPage(
        title = stringResource(R.string.settings_pets),
        onBack = onBack,
    ) {
        item {
            SettingsSection(
                title = stringResource(R.string.pets_display),
                footer = stringResource(R.string.pets_hint),
            ) {
                SettingsSwitchRow(
                    title = stringResource(R.string.pets_enable),
                    subtitle = stringResource(R.string.pets_enable_hint),
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                )
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.pets_style)) {
                PetStyle.entries.forEachIndexed { index, pet ->
                    if (index > 0) SettingsRowDivider()
                    SettingsRow(
                        leading = {
                            PetSprite(
                                style = pet,
                                width = 52.dp,
                                reduceMotion = true,
                            )
                        },
                        title = SettingsCopy.petLabel(pet, language),
                        enabled = enabled,
                        trailing = {
                            RadioButton(
                                selected = pet == style,
                                onClick = if (enabled) {
                                    { onStyleChange(pet) }
                                } else {
                                    null
                                },
                            )
                        },
                        onClick = if (enabled) {
                            { onStyleChange(pet) }
                        } else {
                            null
                        },
                    )
                }
            }
        }

        item {
            SettingsSection(title = stringResource(R.string.pets_size)) {
                PetSize.entries.forEachIndexed { index, petSize ->
                    if (index > 0) SettingsRowDivider()
                    SettingsRow(
                        title = petSizeLabel(petSize, zh),
                        enabled = enabled,
                        trailing = {
                            RadioButton(
                                selected = petSize == size,
                                onClick = if (enabled) {
                                    { onSizeChange(petSize) }
                                } else {
                                    null
                                },
                            )
                        },
                        onClick = if (enabled) {
                            { onSizeChange(petSize) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}

private fun petSizeLabel(size: PetSize, zh: Boolean): String = when (size) {
    PetSize.SMALL -> if (zh) "小" else "Small"
    PetSize.MEDIUM -> if (zh) "中" else "Medium"
    PetSize.LARGE -> if (zh) "大" else "Large"
}
