package com.dshm.bot.data.settings

import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/**
 * 内存版 [SharedPreferences]：JVM 单测用（Android 的真实实现在单测 classpath 上只是
 * stub，不能构造）。只实现设置仓库用到的部分：字符串 / 布尔读写与编辑器链式调用。
 */
class FakeSharedPreferences(seed: Map<String, Any?> = emptyMap()) : SharedPreferences {

    val values = ConcurrentHashMap<String, Any?>(seed)

    override fun getAll(): MutableMap<String, Any> =
        values.entries.filter { it.value != null }.associate { it.key to it.value!! }.toMutableMap()

    override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue

    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        (values[key] as? Set<*>)?.map { it.toString() }?.toMutableSet() ?: defValues

    override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue

    override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue

    override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue

    override fun contains(key: String): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    private inner class FakeEditor : SharedPreferences.Editor {
        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            values[key] = value
            return this
        }

        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor {
            this@FakeSharedPreferences.values[key] = values
            return this
        }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor {
            values[key] = value
            return this
        }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor {
            values[key] = value
            return this
        }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
            values[key] = value
            return this
        }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
            values[key] = value
            return this
        }

        override fun remove(key: String): SharedPreferences.Editor {
            values.remove(key)
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            values.clear()
            return this
        }

        override fun commit(): Boolean = true

        override fun apply() = Unit
    }
}
