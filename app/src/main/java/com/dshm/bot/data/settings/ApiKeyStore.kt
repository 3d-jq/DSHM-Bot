package com.dshm.bot.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.dshm.bot.data.runtime.EngineLog
import java.security.KeyStore

/**
 * DeepSeek API Key 的本地安全存储（对齐 dsh-Go：Key 存在 App 侧，引擎启动时注入）。
 *
 * 优先用 EncryptedSharedPreferences；KeyStore 损坏（备份恢复 / 系统升级后常见）
 * 时先删掉密文文件与主密钥自愈重试一次（自愈会丢旧 Key，需要重新录入），
 * 仍失败才退回普通私有 SharedPreferences（仅本机、MODE_PRIVATE），并在
 * engine.log 里告警 —— 不静默降级，也不把 Key 本身写进日志。
 *
 * 中期迁移备注：androidx.security-crypto 1.1.0 stable 已废弃整库
 * （EncryptedSharedPreferences / MasterKey），官方建议直用 Tink 或 Android
 * Keystore；本轮保持现状，迁移是独立任务。
 */
class ApiKeyStore(context: Context) {

    private val prefs: SharedPreferences = openPrefs(context)

    fun key(): String? = prefs.getString(KEY, null)?.trim()?.ifEmpty { null }

    fun save(value: String) {
        prefs.edit { putString(KEY, value.trim()) }
    }

    fun clear() {
        prefs.edit { remove(KEY) }
    }

    /** 展示用脱敏（保留前 4 后 4）。 */
    fun masked(value: String = key().orEmpty()): String = mask(value)

    private fun buildEncrypted(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** 自愈时把 KeyStore 里的主密钥一并删掉（损坏的密文留着也读不出）。 */
    private fun deleteMasterKey() {
        runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(MasterKey.DEFAULT_MASTER_KEY_ALIAS)) {
                keyStore.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            }
        }
    }

    private fun openPrefs(context: Context): SharedPreferences {
        val first = runCatching { buildEncrypted(context) }
        if (first.isSuccess) return first.getOrThrow()
        EngineLog.log("apikey: EncryptedSharedPreferences 打开失败（${first.exceptionOrNull()?.message}），删除密文自愈重试")
        runCatching { context.deleteSharedPreferences(FILE_NAME) }
        deleteMasterKey()
        val retry = runCatching { buildEncrypted(context) }
        if (retry.isSuccess) return retry.getOrThrow()
        // 自愈也不行（极少数设备不支持 Keystore）：退回普通私有 prefs，只本机可见。
        EngineLog.log("apikey: EncryptedSharedPreferences 自愈失败（${retry.exceptionOrNull()?.message}），回退明文 prefs")
        return context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    }

    companion object {
        /** 展示用脱敏：保留前 4 后 4，短的整串打码。拆出来供 JVM 单测直接断言。 */
        internal fun mask(value: String): String = when {
            value.isEmpty() -> ""
            value.length <= 10 -> "••••"
            else -> value.take(4) + "…" + value.takeLast(4)
        }

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val FILE_NAME = "dshm_secure"
        private const val KEY = "deepseek_api_key"
    }
}
