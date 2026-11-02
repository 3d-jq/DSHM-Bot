package com.dshm.bot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dshm.bot.core.design.DshmTheme
import com.dshm.bot.ui.settings.SettingsViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settingsViewModel: SettingsViewModel = viewModel(
                factory = SettingsViewModel.factory(applicationContext),
            )
            val settings by settingsViewModel.uiState.collectAsStateWithLifecycle()
            DshmTheme(darkTheme = settings.themeMode.isDark(isSystemInDarkTheme())) {
                DshmBotApp(settingsViewModel = settingsViewModel)
            }
        }
    }
}
