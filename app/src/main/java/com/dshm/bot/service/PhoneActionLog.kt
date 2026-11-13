package com.dshm.bot.service

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.core.content.edit

/** 手机操控的操作记录：只存动作和对象，不存屏幕文本、输入内容或密钥。 */
object PhoneActionLog {
    private const val PREFS = "dshm_settings"
    private const val KEY = "phone_action_log"
    private const val MAX = 50

    fun record(context: Context, action: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val list = read(prefs.getString(KEY, null)).getOrNull() ?: JSONArray()
        val next = JSONArray()
        next.put(
            JSONObject()
                .put("at", System.currentTimeMillis())
                .put("text", action.take(180)),
        )
        for (index in 0 until minOf(list.length(), MAX - 1)) {
            list.optJSONObject(index)?.let(next::put)
        }
        prefs.edit { putString(KEY, next.toString()) }
    }

    fun recent(context: Context, limit: Int = 5): List<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val array = read(prefs.getString(KEY, null)).getOrNull() ?: return emptyList()
        val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        return buildList {
            for (index in 0 until minOf(array.length(), limit)) {
                val item = array.optJSONObject(index) ?: continue
                val text = item.optString("text").ifBlank { continue }
                val at = item.optLong("at")
                add("${formatter.format(Date(at))}  $text")
            }
        }
    }

    private fun read(raw: String?): Result<JSONArray> = runCatching {
        if (raw.isNullOrBlank()) JSONArray() else JSONArray(raw)
    }
}
