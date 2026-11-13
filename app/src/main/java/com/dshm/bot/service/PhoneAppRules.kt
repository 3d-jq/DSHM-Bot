package com.dshm.bot.service

import android.content.Context
import org.json.JSONObject
import androidx.core.content.edit

enum class PhoneAppRule {
    NORMAL,
    CONFIRM,
    DENY;

    companion object {
        fun parse(raw: String?): PhoneAppRule =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: NORMAL
    }
}

object PhoneAppRules {
    private const val PREFS = "dshm_settings"
    private const val KEY = "phone_app_rules"

    fun ruleOf(context: Context, packageName: String): PhoneAppRule {
        if (packageName.isBlank()) return PhoneAppRule.NORMAL
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)
            ?: return PhoneAppRule.NORMAL
        return runCatching { PhoneAppRule.parse(JSONObject(raw).optString(packageName)) }
            .getOrDefault(PhoneAppRule.NORMAL)
    }

    fun setRule(context: Context, packageName: String, rule: PhoneAppRule) {
        if (packageName.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = runCatching { JSONObject(prefs.getString(KEY, null).orEmpty()) }
            .getOrDefault(JSONObject())
        if (rule == PhoneAppRule.NORMAL) json.remove(packageName) else json.put(packageName, rule.name)
        prefs.edit { putString(KEY, json.toString()) }
    }
}
