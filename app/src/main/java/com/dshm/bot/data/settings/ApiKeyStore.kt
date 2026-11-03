package com.dshm.bot.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * DeepSeek API Key 的本地安全存储（对齐 dsh-Go：Key 存在 App 侧，引擎启动时注入）。
 * 优先用 EncryptedSharedPreferences；设备不支持时退回普通私有 SharedPreferences（仅本机、不写日志）。
 */
class ApiKeyStore(context: Context) {

    private val prefs: SharedPreferences = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    }

    fun key(): String? = prefs.getString(KEY, null)?.trim()?.ifEmpty { null }

    fun save(value: String) {
        prefs.edit().putString(KEY, value.trim()).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    /** 展示用脱敏（保留前 4 后 4）。 */
    fun masked(value: String = key().orEmpty()): String = when {
        value.isEmpty() -> ""
        value.length <= 10 -> "••••"
        else -> value.take(4) + "…" + value.takeLast(4)
    }

    private companion object {
        const val FILE_NAME = "dshm_secure"
        const val KEY = "deepseek_api_key"
    }
}
