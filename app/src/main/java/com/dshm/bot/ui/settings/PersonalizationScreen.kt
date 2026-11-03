package com.dshm.bot.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.dshm.bot.R
import com.dshm.bot.core.design.Dshm
import com.dshm.bot.data.settings.PersonalizationStore

/** 个性化 → 个人偏好：读写引擎 rootfs 里的个人 AGENTS.md（对齐 dsh-Go reply prefs）。 */
@Composable
fun PersonalizationScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { PersonalizationStore(context) }
    var text by remember { mutableStateOf(store.read().orEmpty()) }
    var saved by remember { mutableStateOf(false) }

    SettingsPageScaffold(title = stringResource(R.string.settings_personalization), onBack = onBack) {
        Text(
            text = stringResource(R.string.personalization_hint),
            style = Dshm.typography.rowPreview,
            color = Dshm.colors.textSecondary,
        )
        Spacer(Modifier.height(Dshm.spacing.sm))
        BasicTextField(
            value = text,
            onValueChange = {
                text = it.take(20_000)
                saved = false
            },
            textStyle = Dshm.typography.rowPreview.copy(color = Dshm.colors.textPrimary),
            modifier = Modifier
                .fillMaxWidth()
                .height(Dshm.spacing.xxl * 8)
                .clip(RoundedCornerShape(Dshm.radius.lg))
                .background(Dshm.colors.surface)
                .padding(Dshm.spacing.lg)
                .verticalScroll(rememberScrollState()),
        )
        Spacer(Modifier.height(Dshm.spacing.md))
        Button(
            onClick = {
                store.save(text)
                saved = true
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(if (saved) R.string.personalization_saved else R.string.personalization_save))
        }
        if (!store.rootfsAvailable()) {
            Text(
                text = stringResource(R.string.info_engine_required),
                style = Dshm.typography.rowPreview,
                color = Dshm.colors.textTertiary,
            )
        }
    }
}
