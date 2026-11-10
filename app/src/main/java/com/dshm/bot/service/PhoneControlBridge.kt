package com.dshm.bot.service

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.IdentityHashMap

/**
 * 用户指定的手机操控 Bot 的执行器。它把无障碍树压成带编号的“屏幕”，
 * 后续点按/输入/滚动都引用同一屏的编号，避免模型在屏幕变化后误点。
 */
object PhoneControlBridge {
    private data class Element(
        val index: Int,
        val node: AccessibilityNodeInfo,
        val role: String,
        val label: String,
        val bounds: Rect,
        val clickable: Boolean,
        val editable: Boolean,
        val scrollable: Boolean,
        val password: Boolean,
        val checked: Boolean,
    )

    private data class Captured(
        val id: String,
        val packageName: String,
        val elements: List<Element>,
        val at: Long,
    )

    private var sequence = 0
    @Volatile
    private var captured: Captured? = null
    private val taskApprovedUntil = mutableMapOf<String, Long>()

    suspend fun handle(context: Context, request: JSONObject): JSONObject {
        val service = DshmAccessibilityService.current
            ?: return failure("无障碍服务没有连上。请到系统设置 → 无障碍里打开 DSHM Bot。")
        PhoneControlSession.blockedReason()?.let { return failure(it) }
        PhoneOverlay.show(service, "DSH 正在操作手机")
        return try {
            val kind = request.optString("kind")
            val denied = confirmIfNeeded(context, service, kind)
            if (denied != null) return denied
            when (kind) {
                "screen" -> screen(service)
                "handoff" -> handoff(context, service, request)
                "tap" -> tap(service, request)
                "type" -> type(service, request)
                "scroll" -> scroll(service, request)
                "key" -> key(service, request)
                "tap_xy" -> tapXY(service, request)
                "apps" -> apps(context, request)
                "open" -> open(context, service, request)
                "notify" -> notify(context, request)
                "clipboard" -> clipboard(context, request)
                else -> failure("未知的手机操作：$kind")
            }
        } catch (error: Throwable) {
            failure("手机操作失败：${error.message ?: error.javaClass.simpleName}")
        }
    }

    private suspend fun confirmIfNeeded(
        context: Context,
        service: DshmAccessibilityService,
        kind: String,
    ): JSONObject? {
        val packageName = service.rootInActiveWindow?.packageName?.toString().orEmpty()
        val appRule = if (kind == "apps") PhoneAppRule.NORMAL else PhoneAppRules.ruleOf(context, packageName)
        if (appRule == PhoneAppRule.DENY) {
            return failure("这个 App 在你的「不碰」名单里，手机操控 Bot 不能读也不能操作。")
        }
        if (kind !in setOf("tap", "type", "scroll", "key", "tap_xy", "open")) return null
        val mode = context.getSharedPreferences("dshm_settings", Context.MODE_PRIVATE)
            .getString("phone_confirm_mode", "ASK")
            ?: "ASK"
        if (mode == "ALWAYS" && appRule != PhoneAppRule.CONFIRM) return null
        val key = packageName.ifBlank { "unknown" }
        if (mode == "TASK" && appRule != PhoneAppRule.CONFIRM &&
            (taskApprovedUntil[key] ?: 0L) > System.currentTimeMillis()
        ) {
            return null
        }
        val allowed = PhoneApprovalGate.request(
            context = context,
            title = context.getString(com.dshm.bot.R.string.phone_approval_title),
            body = context.getString(com.dshm.bot.R.string.phone_approval_body, describe(kind)),
            allowLabel = context.getString(com.dshm.bot.R.string.phone_approval_allow),
            denyLabel = context.getString(com.dshm.bot.R.string.phone_approval_deny),
            timeoutMs = 30_000L,
        )
        if (allowed == null) {
            return failure(context.getString(com.dshm.bot.R.string.phone_approval_unavailable))
        }
        if (!allowed) return failure(context.getString(com.dshm.bot.R.string.phone_approval_denied))
        if (mode == "TASK") {
            taskApprovedUntil[key] = System.currentTimeMillis() + 10 * 60 * 1000L
        }
        return null
    }

    private fun describe(kind: String): String = when (kind) {
        "tap" -> "点按屏幕上的一个元素"
        "type" -> "在屏幕输入框里输入文字"
        "scroll" -> "滚动屏幕"
        "key" -> "按系统键"
        "tap_xy" -> "按坐标点按屏幕"
        "open" -> "打开应用或链接"
        else -> kind
    }

    private suspend fun handoff(
        context: Context,
        service: DshmAccessibilityService,
        request: JSONObject,
    ): JSONObject {
        val reason = request.optString("reason").ifBlank { "请完成当前步骤。" }
        val allowed = PhoneApprovalGate.request(
            context = context,
            title = context.getString(com.dshm.bot.R.string.phone_handoff_title),
            body = context.getString(com.dshm.bot.R.string.phone_handoff_body, reason),
            allowLabel = context.getString(com.dshm.bot.R.string.phone_handoff_continue),
            denyLabel = context.getString(com.dshm.bot.R.string.phone_handoff_stop),
            timeoutMs = request.optLong("timeoutMs", 10 * 60 * 1000L).coerceIn(5_000L, 10 * 60 * 1000L),
        )
        if (allowed == null) return failure(context.getString(com.dshm.bot.R.string.phone_approval_unavailable))
        if (!allowed) return failure(context.getString(com.dshm.bot.R.string.phone_handoff_stopped))
        PhoneActionLog.record(context, "交给用户操作手机")
        delay(150)
        return success(
            context.getString(com.dshm.bot.R.string.phone_handoff_done) + "\n\n" +
                render(capture(service) ?: return failure("用户完成后，新的屏幕读不到。")),
        )
    }

    private suspend fun screen(service: DshmAccessibilityService): JSONObject {
        val fresh = capture(service)
            ?: return failure("当前屏幕读不到内容。请确认无障碍服务已连接，且屏幕没有锁屏。")
        return success(render(fresh))
    }

    private suspend fun tap(service: DshmAccessibilityService, request: JSONObject): JSONObject {
        val element = resolve(request)
            ?: return failure("屏幕已经变化或编号不存在，请重新调用 phone_screen。")
        if (element.password || isSensitive(element.label)) {
            return failure("这是密码 / 验证码 / 支付相关控件，手机操控 Bot 不能代点。请使用 phone_handoff 让用户自己操作。")
        }
        val long = request.optBoolean("long", false)
        val action = if (long) {
            AccessibilityNodeInfo.ACTION_LONG_CLICK
        } else {
            AccessibilityNodeInfo.ACTION_CLICK
        }
        val accepted = performOnNode(element.node, action) ||
            service.tap(
                element.bounds.exactCenterX(),
                element.bounds.exactCenterY(),
                if (long) 550L else 60L,
            )
        if (!accepted) return failure("系统没有接受这次点按：${element.label.ifBlank { element.role }}")
        PhoneActionLog.record(service, "点按「${element.label.ifBlank { element.role }}」")
        delay(280)
        return success(render(capture(service) ?: return failure("动作已执行，但新的屏幕读不到。")))
    }

    private suspend fun type(service: DshmAccessibilityService, request: JSONObject): JSONObject {
        val element = resolve(request)
            ?: return failure("屏幕已经变化或编号不存在，请重新调用 phone_screen。")
        val target = editableNode(element.node)
            ?: return failure("[${
                element.index
            }] 不是输入框；请先点输入框，再使用 phone_type。")
        if (element.password || target.isPassword || isSensitive(element.label)) {
            return failure("这是密码 / 验证码 / 支付输入框，手机操控 Bot 不能代填。请使用 phone_handoff 让用户自己完成。")
        }
        val text = request.optString("text")
        if (text.length > 2_000) return failure("一次最多输入 2000 个字。")
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (!target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            return failure("这个输入框不接受代填。请让用户自己输入，或先点一下输入框再试。")
        }
        PhoneActionLog.record(service, "在输入框输入了 ${text.length} 个字符")
        if (request.optBoolean("submit", false)) {
            val submitted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                target.performAction(AccessibilityAction.ACTION_IME_ENTER.id)
            } else {
                false
            }
            if (!submitted) {
                delay(150)
                return success(
                    "文字已输入，但当前输入法不支持自动提交；请让用户按发送/搜索，或点页面上的对应按钮。\n\n" +
                        render(capture(service) ?: return failure("文字已输入，但新的屏幕读不到。")),
                )
            }
        }
        delay(220)
        return success(render(capture(service) ?: return failure("动作已执行，但新的屏幕读不到。")))
    }

    private suspend fun scroll(service: DshmAccessibilityService, request: JSONObject): JSONObject {
        val direction = request.optString("direction", "down")
        val id = request.optInt("id", -1)
        val current = captured
        val element = if (id >= 0 && current != null && current.id == request.optString("screen")) {
            current.elements.firstOrNull { it.index == id }
        } else {
            null
        }
        val target = element?.node?.let(::scrollableNode)
            ?: service.rootInActiveWindow?.let(::scrollableNode)
        val accepted = target?.let {
            val action = when (direction) {
                "up", "left" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                else -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            }
            it.performAction(action)
        } ?: false
        if (!accepted) {
            val root = service.rootInActiveWindow
            val bounds = Rect().also { root?.getBoundsInScreen(it) }
            val cx = bounds.exactCenterX()
            val cy = bounds.exactCenterY()
            val dx = bounds.width() * 0.25f
            val dy = bounds.height() * 0.20f
            val swiped = when (direction) {
                "up" -> service.swipe(cx, cy - dy, cx, cy + dy)
                "left" -> service.swipe(cx - dx, cy, cx + dx, cy)
                "right" -> service.swipe(cx + dx, cy, cx - dx, cy)
                else -> service.swipe(cx, cy + dy, cx, cy - dy)
            }
            if (!swiped) return failure("没有可滚动的区域，可能已经到头了。")
        }
        PhoneActionLog.record(service, "滚动屏幕（$direction）")
        delay(280)
        return success(render(capture(service) ?: return failure("动作已执行，但新的屏幕读不到。")))
    }

    private suspend fun key(service: DshmAccessibilityService, request: JSONObject): JSONObject {
        val accepted = when (request.optString("key")) {
            "home" -> service.home()
            "recents" -> service.recents()
            "notifications" -> service.notifications()
            else -> service.back()
        }
        if (!accepted) return failure("系统没有执行这个按键。")
        PhoneActionLog.record(service, "按了 ${request.optString("key").ifBlank { "back" }} 键")
        delay(320)
        return success(render(capture(service) ?: return failure("动作已执行，但新的屏幕读不到。")))
    }

    private suspend fun tapXY(service: DshmAccessibilityService, request: JSONObject): JSONObject {
        val current = captured
        if (current != null && current.id != request.optString("screen")) {
            return failure("屏幕已经变化，请重新调用 phone_screen。")
        }
        val accepted = service.tap(
            request.optDouble("x", -1.0).toFloat(),
            request.optDouble("y", -1.0).toFloat(),
            60L,
        )
        if (!accepted) return failure("系统没有接受这次坐标点按。")
        PhoneActionLog.record(service, "按坐标点按")
        delay(280)
        return success(render(capture(service) ?: return failure("动作已执行，但新的屏幕读不到。")))
    }

    private fun apps(context: Context, request: JSONObject): JSONObject {
        val query = request.optString("query").trim().lowercase()
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val apps = pm.queryIntentActivities(intent, 0)
            .mapNotNull { info ->
                val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
                val label = info.loadLabel(pm)?.toString().orEmpty().ifBlank { packageName }
                label to packageName
            }
            .distinctBy { it.second }
            .filter { query.isEmpty() || it.first.lowercase().contains(query) || it.second.lowercase().contains(query) }
            .sortedBy { it.first.lowercase() }
        if (apps.isEmpty()) return success("没有找到匹配的 App。")
        return success(apps.joinToString("\n") { (label, packageName) -> "$label · $packageName" })
    }

    private suspend fun open(
        context: Context,
        service: DshmAccessibilityService,
        request: JSONObject,
    ): JSONObject {
        val url = request.optString("url").trim()
        val app = request.optString("app").trim()
        if (url.isEmpty() && app.isEmpty()) return failure("请给出 app 或 url。")
        val intent = if (url.isNotEmpty()) {
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
        } else {
            val pm = context.packageManager
            val target = pm.getLaunchIntentForPackage(app) ?: run {
                @Suppress("DEPRECATION")
                val infos = pm.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                    0,
                )
                val match = infos.firstOrNull {
                    val packageName = it.activityInfo?.packageName.orEmpty()
                    val label = it.loadLabel(pm)?.toString().orEmpty()
                    packageName.equals(app, true) || label.equals(app, true) ||
                        label.contains(app, ignoreCase = true)
                }
                match?.activityInfo?.packageName?.let(pm::getLaunchIntentForPackage)
            } ?: return failure("没找到 App「$app」。可以先调用 phone_apps 查包名。")
            target
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { return failure("打开失败：${it.message ?: "系统拒绝了这个 Intent"}") }
        PhoneActionLog.record(context, if (url.isNotEmpty()) "打开了链接" else "打开了「$app」")
        delay(700)
        return success(render(capture(service) ?: return failure("已发出打开请求，但新的屏幕读不到。")))
    }

    private fun notify(context: Context, request: JSONObject): JSONObject {
        val title = request.optString("title").trim()
        val text = request.optString("text").trim()
        if (title.isEmpty() && text.isEmpty()) return failure("通知标题和正文不能都为空。")
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return failure("需要通知权限才能发送手机通知。请先在系统设置里允许 DSHM Bot 发送通知。")
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = "phone_control_message"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            manager.getNotificationChannel(channel) == null
        ) {
            manager.createNotificationChannel(
                NotificationChannel(channel, "手机操控消息", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val notification = Notification.Builder(context, channel)
            .setSmallIcon(com.dshm.bot.R.drawable.ic_lucide_smartphone)
            .setContentTitle(title.ifBlank { "DSHM Bot" })
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        manager.notify((System.currentTimeMillis() and 0x7fffffff).toInt(), notification)
        PhoneActionLog.record(context, "发送了手机通知")
        return success("通知已发送。")
    }

    private fun clipboard(context: Context, request: JSONObject): JSONObject {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = request.optString("set")
        if (text.isNotEmpty()) {
            manager.setPrimaryClip(ClipData.newPlainText("DSH", text))
            PhoneActionLog.record(context, "写入了剪贴板")
            return success("已写入剪贴板。")
        }
        if (request.optBoolean("get", false)) {
            val clip = manager.primaryClip ?: return success("剪贴板为空。")
            val value = clip.getItemAt(0).coerceToText(context).toString()
            PhoneActionLog.record(context, "读取了剪贴板")
            return success(value.ifBlank { "剪贴板为空。" })
        }
        return failure("请给出 set 或 get:true。")
    }

    private fun capture(service: DshmAccessibilityService): Captured? {
        val root = service.rootInActiveWindow ?: return null
        root.refresh()
        val elements = mutableListOf<Element>()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        val seen = IdentityHashMap<AccessibilityNodeInfo, Boolean>()
        queue.add(root to 0)
        var next = 0
        while (queue.isNotEmpty() && elements.size < 260) {
            val (node, depth) = queue.removeFirst()
            if (seen.put(node, true) != null || depth > 30) {
                continue
            }
            val label = labelOf(node)
            val clickable = node.isClickable
            val editable = node.isEditable
            val scrollable = node.isScrollable
            if (node.isVisibleToUser && (label.isNotBlank() || clickable || editable || scrollable)) {
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                if (!bounds.isEmpty) {
                    elements += Element(
                        index = next++,
                        node = node,
                        role = roleOf(node),
                        label = label,
                        bounds = bounds,
                        clickable = clickable,
                        editable = editable,
                        scrollable = scrollable,
                        password = node.isPassword,
                        checked = node.isChecked,
                    )
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                queue.add(child to depth + 1)
            }
        }
        val id = "s${++sequence}"
        val result = Captured(
            id = id,
            packageName = root.packageName?.toString().orEmpty(),
            elements = elements,
            at = System.currentTimeMillis(),
        )
        captured = result
        return result
    }

    private fun render(screen: Captured): String {
        val header = "屏幕 ${screen.id} · ${screen.packageName.ifBlank { "未知应用" }}"
        if (screen.elements.isEmpty()) return "$header\n（没有可读元素）"
        val lines = screen.elements.joinToString("\n") { element ->
            val flags = buildList {
                if (element.clickable) add("可点")
                if (element.editable) add("可输入")
                if (element.scrollable) add("可滚动")
                if (element.checked) add("已选")
            }.joinToString("/")
            val bounds = "${element.bounds.left},${element.bounds.top},${element.bounds.right},${element.bounds.bottom}"
            val flagText = if (flags.isEmpty()) "" else " [$flags]"
            "[${element.index}] ${element.role}$flagText ${element.label.take(180)} ($bounds)"
        }
        return "$header\n$lines"
    }

    private fun resolve(request: JSONObject): Element? {
        val current = captured ?: return null
        if (current.id != request.optString("screen")) return null
        if (System.currentTimeMillis() - current.at > 90_000) return null
        return current.elements.firstOrNull { it.index == request.optInt("id", -1) }
    }

    private fun performOnNode(start: AccessibilityNodeInfo, action: Int): Boolean {
        var node: AccessibilityNodeInfo? = start
        repeat(8) {
            val current = node ?: return false
            if (current.isClickable && current.performAction(action)) return true
            node = current.parent
        }
        return false
    }

    private fun editableNode(start: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (start.isEditable) return start
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(start)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 100) {
            val node = queue.removeFirst()
            if (node.isEditable) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
        }
        return null
    }

    private fun scrollableNode(start: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (start.isScrollable) return start
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(start)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 200) {
            val node = queue.removeFirst()
            if (node.isScrollable) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
        }
        return null
    }

    private fun labelOf(node: AccessibilityNodeInfo): String =
        listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(" · ")

    private fun roleOf(node: AccessibilityNodeInfo): String =
        node.className?.toString()?.substringAfterLast('.') ?: "View"

    private fun isSensitive(label: String): Boolean {
        val text = label.lowercase()
        return listOf(
            "密码", "验证码", "支付密码", "安全码", "付款密码", "确认密码",
            "password", "passcode", "verification code", "verification-code", "cvv", "cvc",
        ).any(text::contains)
    }

    private fun success(text: String): JSONObject =
        JSONObject().put("ok", true).put("result", JSONObject().put("text", text))

    private fun failure(text: String): JSONObject =
        JSONObject().put("ok", false).put("error", text)
}
