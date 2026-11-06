package com.dshm.bot.ui.settings

import android.app.NotificationManager
import android.os.PowerManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/** 电池优化白名单状态；从系统设置返回时自动重读。 */
@Composable
fun rememberBatteryExempt(): Boolean? {
    val context = LocalContext.current
    var value by remember { mutableStateOf<Boolean?>(null) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, context) {
        fun read() {
            value = context.getSystemService(PowerManager::class.java)
                ?.isIgnoringBatteryOptimizations(context.packageName)
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) read()
        }
        owner.lifecycle.addObserver(observer)
        read()
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return value
}

/** 系统通知总开关状态；从系统设置返回时自动重读。 */
@Composable
fun rememberNotificationsAllowed(): Boolean? {
    val context = LocalContext.current
    var value by remember { mutableStateOf<Boolean?>(null) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, context) {
        fun read() {
            value = context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled()
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) read()
        }
        owner.lifecycle.addObserver(observer)
        read()
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return value
}
