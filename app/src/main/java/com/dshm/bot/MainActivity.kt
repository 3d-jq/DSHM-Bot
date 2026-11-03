package com.dshm.bot

import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalResources
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dshm.bot.core.design.DshmColors
import com.dshm.bot.core.design.DshmTheme
import com.dshm.bot.domain.model.AppLanguage
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
            val dark = settings.themeMode.isDark(isSystemInDarkTheme())

            // 语言：用本地化 Context 提供字符串资源，不重建 Activity，切换即时且不闪屏。
            val localizedContext = remember(settings.language) {
                val locales = when (settings.language) {
                    AppLanguage.SYSTEM -> null
                    AppLanguage.CHINESE -> LocaleList.forLanguageTags("zh-CN")
                    AppLanguage.ENGLISH -> LocaleList.forLanguageTags("en")
                }
                if (locales == null) {
                    applicationContext
                } else {
                    val configuration = Configuration(applicationContext.resources.configuration)
                    configuration.setLocales(locales)
                    applicationContext.createConfigurationContext(configuration)
                }
            }

            // 窗口底色与状态栏/导航栏图标跟随 App 主题（不只是系统主题），
            // 避免深色 App 配浅色系统时，状态栏区域和转场缝隙露出白色。
            LaunchedEffect(dark) {
                val colors = if (dark) DshmColors.Dark else DshmColors.Light
                window.setBackgroundDrawable(ColorDrawable(colors.background.toArgb()))
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }

            // 只覆盖字符串资源（LocalResources），保留 Activity 的 LocalContext，
                // 否则 rememberLauncherForActivityResult 会因拿不到 Activity 而崩溃。
                CompositionLocalProvider(LocalResources provides localizedContext.resources) {
                    DshmTheme(darkTheme = dark) {
                        DshmBotApp(settingsViewModel = settingsViewModel)
                    }
                }
        }
    }
}

