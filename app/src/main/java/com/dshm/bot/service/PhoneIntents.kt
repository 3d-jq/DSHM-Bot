package com.dshm.bot.service

import android.content.Intent
import androidx.core.net.toUri
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import org.json.JSONObject

/**
 * 「系统 intent」白名单。
 *
 * **不让模型传裸 action / component / extras**（提示词注入会把手机变成任意跳转器），
 * 只暴露一组固定语义，由这里拼 Intent。对齐 dsh-Go `SystemActions` 的做法。
 *
 * [validate] 是纯 JSON 校验，可在 JVM 单测里覆盖；[build] 只在 Android 运行时构造 Intent。
 */
internal object PhoneIntents {

    enum class Kind(val wire: String) {
        DIAL("dial"),
        SMS_DRAFT("sms_draft"),
        SHARE("share"),
        SETTINGS("settings"),
        ALARM("alarm"),
        TIMER("timer"),
        CALENDAR("calendar"),
        NAVIGATE("navigate"),
    }

    /** 纯校验：返回类型，或失败原因（给模型看）。不碰 Android 类，JVM 可测。 */
    fun validate(request: JSONObject): Pair<Kind?, String?> {
        val type = request.optString("type").trim()
        val kind = Kind.entries.firstOrNull { it.wire == type }
            ?: return null to "不支持的 intent 类型：$type（可用：${Kind.entries.joinToString { it.wire }}）"
        val error = when (kind) {
            Kind.DIAL -> if (request.optString("number").trim().isEmpty()) "dial 需要 number" else null
            Kind.SMS_DRAFT -> if (request.optString("text").isEmpty() &&
                request.optString("number").trim().isEmpty()
            ) "sms_draft 需要 text 或 number" else null
            Kind.SHARE -> if (request.optString("text").isEmpty()) "share 需要 text" else null
            Kind.SETTINGS -> validateSettings(request)
            Kind.ALARM -> validateAlarm(request)
            Kind.TIMER -> if (request.optInt("seconds", -1) <= 0) "timer 需要正整数 seconds" else null
            Kind.CALENDAR -> validateCalendar(request)
            Kind.NAVIGATE -> validateNavigate(request)
        }
        return if (error != null) null to error else kind to null
    }

    /** 构造 Intent；调用前先过 [validate]。 */
    fun build(request: JSONObject): Pair<Intent?, String?> {
        val (kind, error) = validate(request)
        if (kind == null) return null to error
        return when (kind) {
            Kind.DIAL -> dial(request) to null
            Kind.SMS_DRAFT -> smsDraft(request) to null
            Kind.SHARE -> share(request) to null
            Kind.SETTINGS -> settings(request) to null
            Kind.ALARM -> alarm(request) to null
            Kind.TIMER -> timer(request) to null
            Kind.CALENDAR -> calendar(request) to null
            Kind.NAVIGATE -> navigate(request) to null
        }
    }

    private val APP_PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_.]+$")

    /** 只暴露「自己能确认」的设置页，排除无障碍/开发者选项等敏感面。 */
    private val SETTINGS_PAGES = mapOf(
        "wifi" to Settings.ACTION_WIFI_SETTINGS,
        "bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS,
        "display" to Settings.ACTION_DISPLAY_SETTINGS,
        "sound" to Settings.ACTION_SOUND_SETTINGS,
        "location" to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
        "battery" to Settings.ACTION_BATTERY_SAVER_SETTINGS,
        "apps" to Settings.ACTION_APPLICATION_SETTINGS,
        "date" to Settings.ACTION_DATE_SETTINGS,
        "locale" to Settings.ACTION_LOCALE_SETTINGS,
        "app_notifications" to Settings.ACTION_APP_NOTIFICATION_SETTINGS,
    )

    private fun validateSettings(request: JSONObject): String? {
        val page = request.optString("page").trim()
        if (!SETTINGS_PAGES.containsKey(page)) {
            return "不支持的设置页：$page（可用：${SETTINGS_PAGES.keys.sorted().joinToString()}）"
        }
        if (page == "app_notifications" &&
            !APP_PACKAGE.matches(request.optString("app").trim())
        ) {
            return "app_notifications 需要合法 app 包名"
        }
        return null
    }

    private fun validateAlarm(request: JSONObject): String? {
        val hour = request.optInt("hour", -1)
        val minute = request.optInt("minute", -1)
        return if (hour !in 0..23 || minute !in 0..59) "alarm 需要 hour(0-23) 和 minute(0-59)" else null
    }

    private fun validateCalendar(request: JSONObject): String? {
        if (request.optString("title").trim().isEmpty()) return "calendar 需要 title"
        if (request.optLong("begin", -1L) <= 0L) return "calendar 需要 begin（毫秒时间戳）"
        return null
    }

    private fun validateNavigate(request: JSONObject): String? {
        val destination = request.optString("destination").trim()
        val lat = request.optDouble("lat", Double.NaN)
        val lng = request.optDouble("lng", Double.NaN)
        return if (destination.isEmpty() && (lat.isNaN() || lng.isNaN())) {
            "navigate 需要 destination 或 lat+lng"
        } else {
            null
        }
    }

    private fun dial(request: JSONObject): Intent =
        // ACTION_DIAL 只预填拨号盘，不用 ACTION_CALL（那会真的拨出去）。
        Intent(Intent.ACTION_DIAL, ("tel:" + request.optString("number").trim()).toUri())

    private fun smsDraft(request: JSONObject): Intent {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = ("smsto:" + request.optString("number").trim()).toUri()
        }
        val text = request.optString("text")
        if (text.isNotEmpty()) intent.putExtra("sms_body", text)
        return intent
    }

    private fun share(request: JSONObject): Intent {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, request.optString("text"))
        }
        val app = request.optString("app").trim()
        if (app.isNotEmpty() && APP_PACKAGE.matches(app)) intent.setPackage(app)
        return Intent.createChooser(intent, null)
    }

    private fun settings(request: JSONObject): Intent {
        val page = request.optString("page").trim()
        val intent = Intent(SETTINGS_PAGES.getValue(page))
        if (page == "app_notifications") {
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, request.optString("app").trim())
        }
        return intent
    }

    private fun alarm(request: JSONObject): Intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
        putExtra(AlarmClock.EXTRA_HOUR, request.optInt("hour"))
        putExtra(AlarmClock.EXTRA_MINUTES, request.optInt("minute"))
        request.optString("message").takeIf { it.isNotBlank() }?.let {
            putExtra(AlarmClock.EXTRA_MESSAGE, it)
        }
        val days = request.optJSONArray("days")
        if (days != null && days.length() > 0) {
            val out = ArrayList<Int>()
            for (i in 0 until days.length()) {
                val d = days.optInt(i, -1)
                if (d in 1..7) out += d
            }
            if (out.isNotEmpty()) putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, out)
        }
        putExtra(AlarmClock.EXTRA_SKIP_UI, false)
    }

    private fun timer(request: JSONObject): Intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
        putExtra(AlarmClock.EXTRA_LENGTH, request.optInt("seconds"))
        request.optString("message").takeIf { it.isNotBlank() }?.let {
            putExtra(AlarmClock.EXTRA_MESSAGE, it)
        }
        putExtra(AlarmClock.EXTRA_SKIP_UI, false)
    }

    private fun calendar(request: JSONObject): Intent = Intent(Intent.ACTION_INSERT).apply {
        data = CalendarContract.Events.CONTENT_URI
        putExtra(CalendarContract.Events.TITLE, request.optString("title").trim())
        putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, request.optLong("begin"))
        request.optLong("end", -1L).takeIf { it > 0 }?.let {
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it)
        }
        putExtra(CalendarContract.Events.ALL_DAY, request.optBoolean("all_day", false))
        listOf(
            CalendarContract.Events.EVENT_LOCATION to "location",
            CalendarContract.Events.DESCRIPTION to "description",
        ).forEach { (key, field) ->
            request.optString(field).takeIf { it.isNotBlank() }?.let { putExtra(key, it) }
        }
    }

    private fun navigate(request: JSONObject): Intent {
        val destination = request.optString("destination").trim()
        val lat = request.optDouble("lat", Double.NaN)
        val lng = request.optDouble("lng", Double.NaN)
        val geo = if (destination.isNotEmpty()) {
            "geo:0,0?q=" + Uri.encode(destination)
        } else {
            "geo:$lat,$lng"
        }
        return Intent(Intent.ACTION_VIEW, geo.toUri())
    }
}
